package observer

import (
	"encoding/json"
	"strings"
	"time"
)

type Status string

const (
	StatusIdle     Status = "idle"
	StatusThinking Status = "thinking"
	StatusRunning  Status = "running"
	StatusDone     Status = "done"
	StatusError    Status = "error"
)

// 启发式与兜底常量（plan Task 3）：
//   - turn 内静默 ≥ quietAfter：running +「长时间无输出」approx 标记
//     （构建/测试跑几分钟无 rollout 事件是正常的，故阈值不给太小）；
//   - turn 开始后 ≥ fallbackAfter 仍无 task_complete/turn_aborted：
//     兜底 done（approx，注记「无结束事件」），防关线程/崩溃/被杀导致永久 running。
const (
	quietAfter    = 3 * time.Minute
	fallbackAfter = 30 * time.Minute
)

// ThreadStatus 是推给副屏的单线程状态快照。
type ThreadStatus struct {
	ThreadID       string `json:"threadId"`
	Status         Status `json:"status"`
	Approx         bool   `json:"approx,omitempty"`
	Note           string `json:"note,omitempty"`
	LastEventAtMs  int64  `json:"lastEventAtMs,omitempty"`
	ContextPercent int    `json:"contextPercent,omitempty"`
}

// workEvents：出现即视为「在干活」的 event_msg payload.type。
// rollout 是非官方集成面：未知的新事件类型降级为仅活性，不会卡状态机。
var workEvents = map[string]bool{
	"item_completed":       true,
	"patch_apply_end":      true,
	"mcp_tool_call_end":    true,
	"sub_agent_activity":   true,
	"web_search_end":       true,
	"image_generation_end": true,
}

type rolloutLine struct {
	Type    string `json:"type"`
	Payload struct {
		Type string `json:"type"`
		Info *struct {
			LastTokenUsage *struct {
				TotalTokens int64 `json:"total_tokens"`
			} `json:"last_token_usage"`
			TotalTokenUsage *struct {
				TotalTokens int64 `json:"total_tokens"`
			} `json:"total_token_usage"`
			ModelContextWindow int64           `json:"model_context_window"`
			RateLimits         json.RawMessage `json:"rate_limits"`
		} `json:"info"`
	} `json:"payload"`
}

// Machine 按 rollout 事件序列推断单线程状态。
// 活性 = 任何成功解析的新行（含最高频的 token_count）；
// 状态只由 event_msg 的 payload.type 驱动。
type Machine struct {
	terminal       Status // "" 表示无终态；done/error 后直到新 task_started 都冻结
	inTurn         bool   // 处于未终结的 turn 内（task_started 或种子窗口里的工作事件）
	sawTool        bool
	sawReason      bool
	approx         bool
	note           string
	lastActive     time.Time
	contextPercent int           // 上下文占用 %（total_tokens / model_context_window）
	rateLimits     *QuotaSnapshot // token_count 内嵌的账户配额缓存（snake_case 已归一）
}

func NewMachine() *Machine { return &Machine{} }

// ObserveLine 喂入一行 rollout（可为历史回放），now 是观察时刻。
func (m *Machine) ObserveLine(line string, now time.Time) {
	line = strings.TrimSpace(line)
	if line == "" {
		return
	}
	var rl rolloutLine
	if err := json.Unmarshal([]byte(line), &rl); err != nil {
		return
	}
	// 任何新行都是活性，清除上一轮 Tick 留下的启发式标记。
	m.lastActive = now
	m.approx = false
	m.note = ""

	if rl.Type == "response_item" {
		// 桌面 app 把工具调用写在 response_item 层（event_msg 的 item/completed
		// 可能缺位），同样视为工作信号；reasoning 只增强 thinking 位。
		switch pt := rl.Payload.Type; pt {
		case "function_call", "function_call_output", "custom_tool_call", "custom_tool_call_output", "local_shell_call":
			m.sawTool = true
			if !m.inTurn {
				m.inTurn = true
				m.terminal = ""
			}
		case "reasoning":
			m.sawReason = true
		}
		return
	}
	if rl.Type != "event_msg" {
		return
	}
	switch pt := rl.Payload.Type; pt {
	case "token_count":
		if info := rl.Payload.Info; info != nil {
			// 上下文占用 = 最近一次请求的 token / 模型上下文窗口。
			// total_token_usage 是 turn 内跨请求累计值，长对话会远超窗口，
			// 不能当上下文口径（实测 53167%）。
			tokens := int64(-1)
			if info.LastTokenUsage != nil {
				tokens = info.LastTokenUsage.TotalTokens
			} else if info.TotalTokenUsage != nil {
				tokens = info.TotalTokenUsage.TotalTokens
			}
			if tokens >= 0 && info.ModelContextWindow > 0 {
				pct := int(float64(tokens) / float64(info.ModelContextWindow) * 100)
				if pct > 100 {
					pct = 100
				}
				m.contextPercent = pct
			}
			if q, ok := ParseQuotaSnake(info.RateLimits); ok {
				m.rateLimits = &q
			}
		}
	case "task_started":
		m.inTurn = true
		m.sawTool = false
		m.sawReason = false
		m.terminal = ""
	case "task_complete":
		m.inTurn = false
		m.terminal = StatusDone
	case "turn_aborted":
		m.inTurn = false
		m.terminal = StatusError
	case "agent_reasoning":
		m.sawReason = true
	default:
		if workEvents[pt] {
			m.sawTool = true
			// 种子窗口落在长 turn 中段：没有 task_started 边界，工作事件本身
			// 证明 turn 还在进行（副屏打开时最常见的形态）。
			if !m.inTurn {
				m.inTurn = true
				m.terminal = ""
			}
		}
	}
}

// Tick 按 now 与最后活性时间的间隔应用启发式与终态兜底。
func (m *Machine) Tick(now time.Time) {
	if m.lastActive.IsZero() || m.terminal != "" {
		return
	}
	quiet := now.Sub(m.lastActive)
	if !m.inTurn {
		return
	}
	if quiet >= fallbackAfter {
		m.terminal = StatusDone
		m.approx = true
		m.note = "无结束事件"
		return
	}
	if quiet >= quietAfter {
		m.approx = true
		m.note = "长时间无输出"
	}
}

func (m *Machine) derive() Status {
	switch {
	case m.terminal != "":
		return m.terminal
	case m.inTurn && m.sawTool:
		return StatusRunning
	case m.inTurn && m.sawReason:
		return StatusThinking
	case m.inTurn:
		return StatusRunning
	default:
		return StatusIdle
	}
}

func (m *Machine) Snapshot() ThreadStatus {
	var last int64
	if !m.lastActive.IsZero() {
		last = m.lastActive.UnixMilli()
	}
	return ThreadStatus{
		Status:         m.derive(),
		Approx:         m.approx,
		Note:           m.note,
		LastEventAtMs:  last,
		ContextPercent: m.contextPercent,
	}
}

// CachedQuota 返回 token_count 内嵌缓存的账户配额（最新一次）；无则 nil。
func (m *Machine) CachedQuota() *QuotaSnapshot {
	return m.rateLimits
}
