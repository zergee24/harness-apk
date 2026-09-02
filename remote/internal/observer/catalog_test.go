package observer

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// 测试依赖 macOS 自带的 /usr/bin/sqlite3（bridge 生产环境同样用它，见 catalog.go）。
func requireSQLite3(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath(sqlite3Bin); err != nil {
		t.Fatalf("sqlite3 CLI 不可用: %v", err)
	}
}

func mustRunSQLite(t *testing.T, dbPath string, sql string) {
	t.Helper()
	cmd := exec.Command(sqlite3Bin, dbPath, sql)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("sqlite3 %s 失败: %v\n%s", sql, err, out)
	}
}

// 固定基准时间，避免测试依赖真实时钟。
var testNow = time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)

func msAgo(d time.Duration) int64 { return testNow.Add(-d).UnixMilli() }

func TestCatalogSQLitePrimary(t *testing.T) {
	requireSQLite3(t)
	db := filepath.Join(t.TempDir(), "state_5.sqlite")
	mustRunSQLite(t, db, `CREATE TABLE threads (
		id TEXT PRIMARY KEY, title TEXT NOT NULL, cwd TEXT NOT NULL, git_branch TEXT,
		updated_at_ms INTEGER NOT NULL, rollout_path TEXT NOT NULL,
		archived INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT 'vscode',
		name TEXT NOT NULL DEFAULT '');`)
	mustRunSQLite(t, db, `INSERT INTO threads (id, title, cwd, git_branch, updated_at_ms, rollout_path, archived, source, name) VALUES
		('t-main',  '首条用户消息', '/tmp/proj', 'main', `+itoa(msAgo(time.Hour))+`, '/r/rollout-t-main.jsonl',  0, 'vscode', '应用显示名'),
		('t-noname','无名字线程',   '/tmp/p2',    NULL,   `+itoa(msAgo(2*time.Hour))+`, '/r/rollout-t-noname.jsonl', 0, 'vscode', ''),
		('t-sub',   'sub',         '/tmp/proj',  NULL,   `+itoa(msAgo(time.Hour))+`, '/r/rollout-t-sub.jsonl',  0, '{"subagent":{"thread_spawn":{"parent_thread_id":"t-main"}}}', ''),
		('t-arch',  'archived',    '/tmp/proj',  NULL,   `+itoa(msAgo(time.Hour))+`, '/r/rollout-t-arch.jsonl', 1, 'vscode', ''),
		('t-old',   'stale',       '/tmp/proj',  NULL,   `+itoa(msAgo(30*24*time.Hour))+`, '/r/rollout-t-old.jsonl', 0, 'vscode', '');`)

	got, err := Catalog(context.Background(), Options{
		Now: testNow, Window: 24 * time.Hour, Limit: 32, SQLitePath: db,
		IndexPath: filepath.Join(t.TempDir(), "missing.jsonl"),
	})
	if err != nil {
		t.Fatalf("Catalog: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("期望 2 条线程（排除 subagent/归档/过期），得到 %d: %+v", len(got), got)
	}
	byID := map[string]ThreadInfo{}
	for _, th := range got {
		byID[th.ID] = th
	}
	main, ok := byID["t-main"]
	if !ok {
		t.Fatalf("缺 t-main: %+v", got)
	}
	if main.Title != "应用显示名" {
		t.Errorf("title 应优先取 name 列，得到 %q", main.Title)
	}
	if main.GitBranch != "main" || main.CWD != "/tmp/proj" || main.RolloutPath != "/r/rollout-t-main.jsonl" {
		t.Errorf("字段映射错误: %+v", main)
	}
	noname := byID["t-noname"]
	if noname.Title != "无名字线程" {
		t.Errorf("name 为空时应回退 title，得到 %q", noname.Title)
	}
	if noname.GitBranch != "" {
		t.Errorf("git_branch NULL 应映射为空串，得到 %q", noname.GitBranch)
	}
	if byID["t-noname"].UpdatedAtMs != msAgo(2*time.Hour) {
		t.Errorf("UpdatedAtMs 映射错误: %+v", noname)
	}
}

func TestCatalogSQLiteLimit(t *testing.T) {
	requireSQLite3(t)
	db := filepath.Join(t.TempDir(), "state_5.sqlite")
	mustRunSQLite(t, db, `CREATE TABLE threads (id TEXT PRIMARY KEY, title TEXT NOT NULL, cwd TEXT NOT NULL,
		git_branch TEXT, updated_at_ms INTEGER NOT NULL, rollout_path TEXT NOT NULL,
		archived INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT 'vscode', name TEXT NOT NULL DEFAULT '');`)
	mustRunSQLite(t, db, `INSERT INTO threads (id, title, cwd, updated_at_ms, rollout_path) VALUES
		('a', 'a', '/x', `+itoa(msAgo(3*time.Hour))+`, '/r/a.jsonl'),
		('b', 'b', '/x', `+itoa(msAgo(1*time.Hour))+`, '/r/b.jsonl'),
		('c', 'c', '/x', `+itoa(msAgo(2*time.Hour))+`, '/r/c.jsonl');`)

	got, err := Catalog(context.Background(), Options{
		Now: testNow, Window: 24 * time.Hour, Limit: 2, SQLitePath: db,
		IndexPath: filepath.Join(t.TempDir(), "missing.jsonl"),
	})
	if err != nil {
		t.Fatalf("Catalog: %v", err)
	}
	if len(got) != 2 || got[0].ID != "b" || got[1].ID != "c" {
		t.Fatalf("Limit 应按 updated_at 倒序截断 [b c]，得到 %+v", got)
	}
}

func TestCatalogFallsBackToIndex(t *testing.T) {
	dir := t.TempDir()
	sessions := filepath.Join(dir, "sessions")
	dayDir := filepath.Join(sessions, "2026", "09", "02")
	if err := os.MkdirAll(dayDir, 0o755); err != nil {
		t.Fatal(err)
	}
	freshRollout := filepath.Join(dayDir, "rollout-2026-09-02T10-00-00-11111111-2222-3333-4444-555555555555.jsonl")
	if err := os.WriteFile(freshRollout, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	index := filepath.Join(dir, "session_index.jsonl")
	content := `{"id":"11111111-2222-3333-4444-555555555555","thread_name":"新鲜线程","updated_at":"2026-09-02T10:00:00.000000Z"}
这不是 JSON
{"id":"99999999-2222-3333-4444-555555555555","thread_name":"过期线程","updated_at":"2026-08-01T10:00:00.000000Z"}
{"id":"broken","thread_name":"缺时间戳"}
`
	if err := os.WriteFile(index, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}

	got, err := Catalog(context.Background(), Options{
		Now: testNow, Window: 24 * time.Hour, Limit: 32,
		SQLitePath: filepath.Join(dir, "missing.sqlite"), IndexPath: index, SessionsDir: sessions,
	})
	if err != nil {
		t.Fatalf("Catalog 降级失败: %v", err)
	}
	if len(got) != 1 || got[0].ID != "11111111-2222-3333-4444-555555555555" {
		t.Fatalf("降级应只含新鲜且可解析的线程: %+v", got)
	}
	if got[0].Title != "新鲜线程" || got[0].RolloutPath != freshRollout {
		t.Fatalf("标题/rollout 定位错误: %+v", got[0])
	}
}

func itoa(n int64) string {
	b, _ := json.Marshal(n)
	return string(b)
}
