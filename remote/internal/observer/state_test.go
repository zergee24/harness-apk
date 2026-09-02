package observer

import (
	"testing"
	"time"
)

func line(payloadType string) string {
	return `{"type":"event_msg","payload":{"type":"` + payloadType + `"}}`
}

var base = time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)

func TestStateMachineTransitions(t *testing.T) {
	cases := []struct {
		name       string
		events     []string
		wantStatus Status
	}{
		{"无事件为 idle", nil, StatusIdle},
		{"task_started 即 running", []string{"task_started"}, StatusRunning},
		{"仅 reasoning 流为 thinking", []string{"task_started", "agent_reasoning", "agent_reasoning"}, StatusThinking},
		{"出现工具产物转 running", []string{"task_started", "agent_reasoning", "item_completed"}, StatusRunning},
		{"patch_apply_end 算工具活动", []string{"task_started", "patch_apply_end"}, StatusRunning},
		{"sub_agent_activity 算工具活动", []string{"task_started", "sub_agent_activity"}, StatusRunning},
		{"mcp_tool_call_end 算工具活动", []string{"task_started", "mcp_tool_call_end"}, StatusRunning},
		{"task_complete 终态 done", []string{"task_started", "item_completed", "task_complete"}, StatusDone},
		{"turn_aborted 终态 error", []string{"task_started", "turn_aborted"}, StatusError},
		{"token_count 不改变状态", []string{"task_started", "token_count", "token_count"}, StatusRunning},
		{"agent_message 不改变 thinking", []string{"task_started", "agent_reasoning", "agent_message"}, StatusThinking},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			m := NewMachine()
			for i, e := range tc.events {
				m.ObserveLine(line(e), base.Add(time.Duration(i)*time.Second))
			}
			if got := m.Snapshot(); got.Status != tc.wantStatus {
				t.Fatalf("事件 %v: 期望 %s，得到 %s", tc.events, tc.wantStatus, got.Status)
			}
		})
	}
}

func TestStateMachineRobustness(t *testing.T) {
	t.Run("未知事件只算活性不崩", func(t *testing.T) {
		m := NewMachine()
		m.ObserveLine(`{"type":"event_msg","payload":{"type":"brand_new_future_event"}}`, base)
		if got := m.Snapshot(); got.Status != StatusIdle {
			t.Fatalf("期望 idle，得到 %s", got.Status)
		}
	})
	t.Run("非法行忽略不崩", func(t *testing.T) {
		m := NewMachine()
		m.ObserveLine("这不是 JSON", base)
		m.ObserveLine(`{"type":"unknown_top_level"}`, base)
		m.ObserveLine("", base)
		if got := m.Snapshot(); got.Status != StatusIdle {
			t.Fatalf("期望 idle，得到 %s", got.Status)
		}
	})
	t.Run("顶层 response_item 也算活性", func(t *testing.T) {
		m := NewMachine()
		m.ObserveLine(`{"type":"response_item","payload":{"type":"reasoning"}}`, base)
		m.Tick(base.Add(2 * time.Minute))
		if m.Snapshot().Approx {
			t.Fatalf("2 分钟内的活性不应触发启发式: %+v", m.Snapshot())
		}
	})
}

func TestQuietHeuristic(t *testing.T) {
	m := NewMachine()
	m.ObserveLine(line("task_started"), base)
	// 3 分钟内：精确运行
	m.Tick(base.Add(2 * time.Minute))
	if s := m.Snapshot(); s.Approx || s.Note != "" || s.Status != StatusRunning {
		t.Fatalf("静默 2min 不应触发启发式: %+v", s)
	}
	// ≥3 分钟：running + 长时间无输出（非精确）
	m.Tick(base.Add(3 * time.Minute))
	if s := m.Snapshot(); s.Status != StatusRunning || !s.Approx || s.Note == "" {
		t.Fatalf("静默 3min 应为 running+approx+注记: %+v", s)
	}
	// 新事件到达：恢复精确
	m.ObserveLine(line("token_count"), base.Add(4*time.Minute))
	if s := m.Snapshot(); s.Approx || s.Note != "" {
		t.Fatalf("新活性应清除启发式标记: %+v", s)
	}
}

func TestTerminalFallback(t *testing.T) {
	m := NewMachine()
	m.ObserveLine(line("task_started"), base)
	m.Tick(base.Add(29 * time.Minute))
	if s := m.Snapshot(); s.Status != StatusRunning {
		t.Fatalf("29min 仍应为 running: %+v", s)
	}
	m.Tick(base.Add(30 * time.Minute))
	if s := m.Snapshot(); s.Status != StatusDone || !s.Approx || s.Note == "" {
		t.Fatalf("30min 无终态应兜底 done(approx): %+v", s)
	}
	// 终态后 Tick 不再改写
	m.Tick(base.Add(time.Hour))
	if s := m.Snapshot(); s.Status != StatusDone {
		t.Fatalf("终态应保持: %+v", s)
	}
	// done 后新 turn 开始 → 重新 running
	m.ObserveLine(line("task_started"), base.Add(time.Hour+time.Second))
	if s := m.Snapshot(); s.Status != StatusRunning || s.Approx {
		t.Fatalf("新 turn 应回到 running: %+v", s)
	}
}

func TestDoneWithoutTask(t *testing.T) {
	// 线程从未跑任务但已存在（如刚建会话）→ idle，不触发兜底
	m := NewMachine()
	m.Tick(base.Add(time.Hour))
	if s := m.Snapshot(); s.Status != StatusIdle {
		t.Fatalf("无任务线程应保持 idle: %+v", s)
	}
}

func TestSeedWindowRecovery(t *testing.T) {
	t.Run("种子窗口落在长 turn 中段：工作事件确立 running", func(t *testing.T) {
		m := NewMachine()
		// 长 turn 的 task_started 在 64KB 窗口之外，窗口里只有进行中事件
		m.ObserveLine(line("item_completed"), base)
		m.ObserveLine(line("token_count"), base.Add(time.Second))
		if s := m.Snapshot(); s.Status != StatusRunning {
			t.Fatalf("窗口内工作事件应确立 running: %+v", s)
		}
		// 恢复出的 inTurn 同样受终态兜底约束（最后活性在 base+1s，31min 时静默已超 30min）
		m.Tick(base.Add(31 * time.Minute))
		if s := m.Snapshot(); s.Status != StatusDone || !s.Approx {
			t.Fatalf("恢复 turn 30min 无事件应兜底 done: %+v", s)
		}
	})
	t.Run("done 之后 token_count 不复活状态", func(t *testing.T) {
		m := NewMachine()
		m.ObserveLine(line("task_started"), base)
		m.ObserveLine(line("task_complete"), base.Add(time.Minute))
		m.ObserveLine(line("token_count"), base.Add(2*time.Minute))
		m.ObserveLine(line("agent_reasoning"), base.Add(3*time.Minute))
		if s := m.Snapshot(); s.Status != StatusDone {
			t.Fatalf("done 后的活性事件不得复活状态: %+v", s)
		}
	})
	t.Run("仅 reasoning 无工作事件且无边界不确定立 turn", func(t *testing.T) {
		m := NewMachine()
		m.ObserveLine(line("agent_reasoning"), base)
		m.Tick(base.Add(2 * time.Minute))
		if s := m.Snapshot(); s.Status != StatusIdle {
			t.Fatalf("无边界时 reasoning 不应确立 turn: %+v", s)
		}
	})
	t.Run("response_item 层的工具调用确立 running", func(t *testing.T) {
		m := NewMachine()
		m.ObserveLine(`{"type":"response_item","payload":{"type":"custom_tool_call_output"}}`, base)
		m.ObserveLine(line("token_count"), base.Add(time.Second))
		if s := m.Snapshot(); s.Status != StatusRunning {
			t.Fatalf("response_item 工具调用应确立 running: %+v", s)
		}
	})
	t.Run("response_item 层 reasoning 单独不确立 turn", func(t *testing.T) {
		m := NewMachine()
		m.ObserveLine(`{"type":"response_item","payload":{"type":"reasoning"}}`, base)
		if s := m.Snapshot(); s.Status != StatusIdle {
			t.Fatalf("response_item reasoning 不应确立 turn: %+v", s)
		}
	})
}
