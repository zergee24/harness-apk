package observer

import (
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
	ID          string `json:"id"`
	Title       string `json:"title"`
	Directory   string `json:"directory"`
	TimeUpdated string `json:"time_updated"`
	TaskType    string `json:"task_type"`
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

func parseMsOrZero(s string) int64 {
	v, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	if err != nil {
		return 0
	}
	return v
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

func execCommandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, name, args...)
}
