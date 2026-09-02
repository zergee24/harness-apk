package observer

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTailPoll(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "rollout-test.jsonl")

	tl := newTailer(path)
	defer tl.close()

	// 空文件：idle，无回调
	if err := os.WriteFile(path, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	var got []ThreadStatus
	tl.poll(base)
	if s := tl.m.Snapshot(); s.Status != StatusIdle {
		t.Fatalf("空 rollout 应为 idle: %+v", s)
	}

	// 追加一个进行中的 turn
	appendLines(t, path, line("task_started"), line("agent_reasoning"))
	tl.poll(base.Add(10 * time.Second))
	if s := tl.m.Snapshot(); s.Status != StatusThinking {
		t.Fatalf("追加事件后应为 thinking: %+v", s)
	}

	// 结束
	appendLines(t, path, line("task_complete"))
	tl.poll(base.Add(20 * time.Second))
	if s := tl.m.Snapshot(); s.Status != StatusDone || s.Approx {
		t.Fatalf("task_complete 应为精确 done: %+v", s)
	}

	// 文件被截断（轮转/异常）：重置不崩
	if err := os.WriteFile(path, []byte(line("task_started")+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	tl.poll(base.Add(30 * time.Second))
	if s := tl.m.Snapshot(); s.Status != StatusRunning {
		t.Fatalf("截断后应按新内容重置为 running: %+v", s)
	}
	_ = got
}

func TestTailSeedsFromHistory(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "rollout-history.jsonl")
	content := line("task_started") + "\n" + line("item_completed") + "\n" + line("task_complete") + "\n"
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	tl := newTailer(path)
	defer tl.close()
	tl.poll(base)
	if s := tl.m.Snapshot(); s.Status != StatusDone {
		t.Fatalf("打开已有 rollout 应从历史推得 done: %+v", s)
	}
}

func appendLines(t *testing.T, path string, lines ...string) {
	t.Helper()
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	for _, l := range lines {
		if _, err := f.WriteString(l + "\n"); err != nil {
			t.Fatal(err)
		}
	}
}
