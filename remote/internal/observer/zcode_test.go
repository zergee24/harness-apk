package observer

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

// ZcodeSessions 走真实 sqlite3 CLI（与 catalog_test 同一口径）：
// 建临时库灌两行（含 subagent_child 与空标题），断言过滤/映射/日志路径。
func TestZcodeSessions(t *testing.T) {
	if _, err := exec.LookPath(sqlite3Bin); err != nil {
		t.Skip("sqlite3 CLI 不可用")
	}
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "db.sqlite")
	rolloutDir := filepath.Join(dir, "rollout")
	if err := os.MkdirAll(rolloutDir, 0o755); err != nil {
		t.Fatal(err)
	}
	nowMs := time.Now().UnixMilli()
	// 真实库 time_updated 是 INTEGER（-json 输出 number）；额外插一行
	// TEXT 形态覆盖老值兼容路径。
	sql := `CREATE TABLE session (id TEXT, title TEXT, directory TEXT, time_updated INTEGER, task_type TEXT);
INSERT INTO session VALUES ('sess_abc123-4', '主会话', '/tmp/proj', ` + strconv.FormatInt(nowMs-1000, 10) + `, 'interactive');
INSERT INTO session VALUES ('sess_child-9', '子代理', '/tmp/proj', ` + strconv.FormatInt(nowMs-2000, 10) + `, 'subagent_child');
INSERT INTO session VALUES ('sess_notitle-7', '', '/tmp/other', ` + strconv.FormatInt(nowMs-3000, 10) + `, NULL);
INSERT INTO session VALUES ('sess_old-1', '过期', '/tmp/old', 1000, 'interactive');
INSERT INTO session VALUES ('sess_text-2', '文本时间', '/tmp/text', '` + strconv.FormatInt(nowMs-4000, 10) + `', 'interactive');`
	cmd := exec.Command(sqlite3Bin, dbPath, sql)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("建库失败: %v %s", err, out)
	}

	got, ok := ZcodeSessions(context.Background(), dbPath, rolloutDir, nowMs-10000)
	if !ok {
		t.Fatal("ZcodeSessions 返回 ok=false")
	}
	if len(got) != 3 {
		t.Fatalf("应过滤 subagent 与过期会话，得 %d 条: %+v", len(got), got)
	}
	if got[0].ID != ZcodeThreadIDPrefix+"sess_abc123-4" {
		t.Fatalf("首条 id 错: %s", got[0].ID)
	}
	if got[0].Source != SourceZcode {
		t.Fatalf("source 应为 zcode: %q", got[0].Source)
	}
	wantPath := filepath.Join(rolloutDir, "model-io-sess_abc123-4.jsonl")
	if got[0].RolloutPath != wantPath {
		t.Fatalf("rollout 路径应去 sess_ 前缀:\n got %s\nwant %s", got[0].RolloutPath, wantPath)
	}
	if got[1].Title != "other" {
		t.Fatalf("空标题应回退目录名，得 %q", got[1].Title)
	}
}

func TestZcodeStatusFromMtime(t *testing.T) {
	dir := t.TempDir()
	fresh := filepath.Join(dir, "fresh.jsonl")
	if err := os.WriteFile(fresh, []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	if got := ZcodeStatusFromMtime(fresh, now); got != StatusRunning {
		t.Fatalf("新鲜日志应为 running，得 %s", got)
	}
	if got := ZcodeStatusFromMtime(filepath.Join(dir, "missing.jsonl"), now); got != StatusIdle {
		t.Fatalf("无日志应为 idle，得 %s", got)
	}
	old := filepath.Join(dir, "old.jsonl")
	if err := os.WriteFile(old, []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	past := now.Add(-2 * zcodeRunningWindow)
	if err := os.Chtimes(old, past, past); err != nil {
		t.Fatal(err)
	}
	if got := ZcodeStatusFromMtime(old, now); got != StatusDone {
		t.Fatalf("陈旧日志应为 done，得 %s", got)
	}
}

func TestZcodeFocusPlan(t *testing.T) {
	plans, err := ZcodeFocusPlan("/Users/tony/Documents/harness-apk")
	if err != nil {
		t.Fatal(err)
	}
	if len(plans) != 2 {
		t.Fatalf("应为 caffeinate+open 两步，得 %d", len(plans))
	}
	open := plans[1][1]
	want := "zcode://workspace/open?path=%2FUsers%2Ftony%2FDocuments%2Fharness-apk"
	if open != want {
		t.Fatalf("深链错误:\n got %s\nwant %s", open, want)
	}
	for _, bad := range []string{"", "relative/path", "/tmp/\nxxx"} {
		if _, err := ZcodeFocusPlan(bad); err == nil {
			t.Fatalf("路径 %q 应被拒绝", bad)
		}
	}
}
