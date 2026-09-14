package observer

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ZCode 会话目录：~/.zcode/cli/db/db.sqlite 的 session 表。
// 运行状态启发式：rollout 文件（model-io-sess_<id>.jsonl）最近
// zcodeRunningWindow 内有写入 = running，否则 done（近似已完成）。

const zcodeRunningWindow = 120 * time.Second

type zcodeSessionRow struct {
	ID          string          `json:"id"`
	Title       string          `json:"title"`
	Directory   string          `json:"directory"`
	TimeUpdated json.RawMessage `json:"time_updated"` // 真实库 INTEGER 输出 number，老值可能是 TEXT 字符串
	TaskType    string          `json:"task_type"`
}

// ZcodeSessions 查询窗口内有更新的非子代理 zcode 会话，映射为
// Source=zcode 的 ThreadInfo（RolloutPath 指向其 model-io 日志）。
func ZcodeSessions(ctx context.Context, dbPath, rolloutDir string, windowStartMs int64) ([]ThreadInfo, bool) {
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	query := "SELECT id, title, directory, time_updated, task_type FROM session" +
		" WHERE time_updated > '" + strconv.FormatInt(windowStartMs, 10) + "'" +
		" AND (task_type IS NULL OR task_type = '' OR task_type != 'subagent_child')" +
		" ORDER BY time_updated DESC"
	cmd := execCommandContext(cctx, sqlite3Bin, "-readonly", "-json", dbPath, query)
	var stdout strings.Builder
	cmd.Stdout = &stdout
	if err := cmd.Run(); err != nil {
		return nil, false
	}
	out := strings.TrimSpace(stdout.String())
	if out == "" {
		return []ThreadInfo{}, true
	}
	var rows []zcodeSessionRow
	if err := json.Unmarshal([]byte(out), &rows); err != nil {
		return nil, false
	}
	threads := make([]ThreadInfo, 0, len(rows))
	for _, r := range rows {
		if r.ID == "" {
			continue
		}
		updated := parseMsOrZero(r.TimeUpdated)
		// 日志文件名是 model-io-sess_<uuid>.jsonl：id 去掉 sess_ 前缀再拼，
		// 否则路径多一截 sess_ 导致 mtime 永远 miss。
		sid := strings.TrimPrefix(r.ID, "sess_")
		title := r.Title
		if title == "" {
			title = filepath.Base(r.Directory)
		}
		threads = append(threads, ThreadInfo{
			ID:          ZcodeThreadIDPrefix + r.ID,
			Title:       title,
			CWD:         r.Directory,
			UpdatedAtMs: updated,
			Source:      SourceZcode,
			RolloutPath: rolloutDir + "/model-io-sess_" + sid + ".jsonl",
		})
	}
	return threads, true
}

// parseMsOrZero 兼容 number 与 string 两种 JSON 形态的毫秒时间戳。
func parseMsOrZero(raw json.RawMessage) int64 {
	var n int64
	if err := json.Unmarshal(raw, &n); err == nil {
		return n
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		if v, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64); err == nil {
			return v
		}
	}
	return 0
}

// ZcodeStatusFromMtime 按 rollout 文件新鲜度给出运行状态：
// 最近 zcodeRunningWindow 内有写入 = running，否则 done。
func ZcodeStatusFromMtime(path string, now time.Time) Status {
	st, err := os.Stat(path)
	if err != nil {
		return StatusIdle
	}
	if now.Sub(st.ModTime()) <= zcodeRunningWindow {
		return StatusRunning
	}
	return StatusDone
}

// zcodeActCache 按 rollout mtime 缓存摘要，避免 5s 轮询反复读大文件。
type zcodeActCache struct {
	modNs int64
	text  string
}

// ZcodeThreadState 一次 stat 给出状态与摘要；文件未变时直接复用缓存文本。
// prevModNs/prevText 来自上一次调用（首次传 0/""）。
func ZcodeThreadState(path string, now time.Time, prevModNs int64, prevText string) (Status, int64, string) {
	st, err := os.Stat(path)
	if err != nil {
		return StatusIdle, 0, ""
	}
	modNs := st.ModTime().UnixNano()
	status := StatusDone
	if now.Sub(st.ModTime()) <= zcodeRunningWindow {
		status = StatusRunning
	}
	text := prevText
	if modNs != prevModNs {
		text = ActivitySnippet(ZcodeTailActivity(path))
	}
	return status, modNs, text
}

// zcodeTailWindow 是摘要提取读取的日志尾窗；单条 model_io 记录（整轮
// LLM 请求）可能数百 KB，窗口太小会切在行中间拿不到完整记录。
const zcodeTailWindow = 1 << 20

// ZcodeTailActivity 从 model-io 日志尾部取最近一条记录的活动摘要：
// 优先模型最终文本，纯工具轮退化为工具名。取不到返回空串。
func ZcodeTailActivity(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return ""
	}
	window := int64(zcodeTailWindow)
	if st.Size() < window {
		window = st.Size()
	}
	buf := make([]byte, window)
	if _, err := f.ReadAt(buf, st.Size()-window); err != nil {
		return ""
	}
	// 从尾往前找完整行（末段可能是正在写的半行），最多回退 3 段。
	end := len(buf)
	for try := 0; try < 3; try++ {
		start := bytes.LastIndexByte(buf[:end], '\n') + 1
		line := bytes.TrimSpace(buf[start:end])
		if len(line) > 0 {
			if text := zcodeActivityFromRecord(line); text != "" {
				return text
			}
		}
		if start == 0 {
			return ""
		}
		end = start - 1
	}
	return ""
}

// zcodeActivityFromRecord 解析单条 model_io 记录：response.text 优先，
// 空文本轮退化为最后一个工具调用名（「调用 Read」）。
func zcodeActivityFromRecord(line []byte) string {
	var rec struct {
		Response *struct {
			Text      string `json:"text"`
			ToolCalls []struct {
				Name string `json:"name"`
			} `json:"toolCalls"`
		} `json:"response"`
	}
	if err := json.Unmarshal(line, &rec); err != nil || rec.Response == nil {
		return ""
	}
	if s := strings.TrimSpace(rec.Response.Text); s != "" {
		return s
	}
	if n := len(rec.Response.ToolCalls); n > 0 {
		return "调用 " + rec.Response.ToolCalls[n-1].Name
	}
	return ""
}

func execCommandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, name, args...)
}
