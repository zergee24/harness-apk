package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/harnessapk/remote/internal/observer"
	"github.com/harnessapk/remote/internal/protocol"
)

type recordedFrame struct {
	deviceID string
	event    protocol.Event
}

func newDashboardTestBridge(t *testing.T) (*bridge, *[]recordedFrame) {
	t.Helper()
	frames := &[]recordedFrame{}
	b := &bridge{
		state: bridgeState{
			HostID:        "host-1",
			DeviceSecrets: map[string]string{"device-1": "bogus-secret"},
		},
		dashboardSender: func(_ context.Context, deviceID string, event protocol.Event) error {
			*frames = append(*frames, recordedFrame{deviceID: deviceID, event: event})
			return nil
		},
	}
	return b, frames
}

func framesByType(t *testing.T, frames *[]recordedFrame, deviceID, eventType string) []protocol.Event {
	t.Helper()
	var got []protocol.Event
	for _, f := range *frames {
		if f.deviceID == deviceID && f.event.Type == eventType {
			got = append(got, f.event)
		}
	}
	return got
}

func TestPublishDashboardThreadsSendsPlainFrames(t *testing.T) {
	b, frames := newDashboardTestBridge(t)
	snap := observer.ThreadSnapshot{
		ThreadInfo: observer.ThreadInfo{ID: "tid-1", Title: "服务团队", UpdatedAtMs: 123},
		Status:     observer.StatusRunning,
	}
	b.publishDashboardThreads(context.Background(), []observer.ThreadSnapshot{snap})

	events := framesByType(t, frames, "device-1", "dashboard.thread")
	if len(events) != 1 {
		t.Fatalf("期望 1 条 dashboard.thread，得到 %d", len(events))
	}
	var payload observer.ThreadSnapshot
	if err := json.Unmarshal(events[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload.ID != "tid-1" || payload.Title != "服务团队" || payload.Status != observer.StatusRunning {
		t.Fatalf("payload = %#v", payload)
	}

	// 同帧重发（内容未变）不再发送
	before := len(framesByType(t, frames, "device-1", "dashboard.thread"))
	b.publishDashboardThreads(context.Background(), []observer.ThreadSnapshot{snap})
	if after := len(framesByType(t, frames, "device-1", "dashboard.thread")); after != before {
		t.Fatalf("未变快照不应重发: before=%d after=%d", before, after)
	}

	// 状态变化后重发
	snap.Status = observer.StatusDone
	b.publishDashboardThreads(context.Background(), []observer.ThreadSnapshot{snap})
	events = framesByType(t, frames, "device-1", "dashboard.thread")
	if len(events) != 2 {
		t.Fatalf("状态变化应再发一条，得到 %d", len(events))
	}
}

func TestDashboardSnapshotSendsFullFrame(t *testing.T) {
	b, frames := newDashboardTestBridge(t)
	b.sendDashboardSnapshot(context.Background(), "device-1")
	events := framesByType(t, frames, "device-1", "dashboard.threads")
	if len(events) != 1 {
		t.Fatalf("期望 1 条 dashboard.threads，得到 %d", len(events))
	}
	var frame struct {
		Threads []observer.ThreadSnapshot `json:"threads"`
	}
	if err := json.Unmarshal(events[0].Payload, &frame); err != nil {
		t.Fatal(err)
	}
	if len(frame.Threads) != 0 {
		t.Fatalf("无 supervisor 时应为空帧: %#v", frame)
	}

	// 注入一帧后应整表下发
	sup := observer.NewSupervisor(observer.DefaultOptions(), 8)
	sup.Seed([]observer.ThreadSnapshot{{
		ThreadInfo: observer.ThreadInfo{ID: "tid-9", Title: "demo07", UpdatedAtMs: 456},
		Status:     observer.StatusThinking,
	}})
	b.dashboard = sup
	b.sendDashboardSnapshot(context.Background(), "device-1")
	events = framesByType(t, frames, "device-1", "dashboard.threads")
	if len(events) != 2 {
		t.Fatalf("期望第 2 条 dashboard.threads，得到 %d", len(events))
	}
	if err := json.Unmarshal(events[1].Payload, &frame); err != nil {
		t.Fatal(err)
	}
	if len(frame.Threads) != 1 || frame.Threads[0].ID != "tid-9" || frame.Threads[0].Status != observer.StatusThinking {
		t.Fatalf("快照帧 = %#v", frame)
	}
}

func TestThreadFocusRunsCaffeinateThenOpen(t *testing.T) {
	b, frames := newDashboardTestBridge(t)
	var calls [][]string
	b.focusRunner = func(_ context.Context, name string, args ...string) error {
		calls = append(calls, append([]string{name}, args...))
		return nil
	}
	id := "019f5080-3e9a-7342-9afe-2f86db89a664"
	err := b.focusDashboardThread(context.Background(), "device-1", protocol.Command{
		Type: "thread.focus", RequestID: "req-1", ThreadID: id,
	})
	if err != nil {
		t.Fatal(err)
	}
	want := [][]string{
		{"/usr/bin/caffeinate", "-u", "-t", "2"},
		{"/usr/bin/open", "codex://threads/" + id},
	}
	if !reflect.DeepEqual(calls, want) {
		t.Fatalf("聚焦命令序列 = %v，期望 %v（不得出现 -g）", calls, want)
	}
	acks := framesByType(t, frames, "device-1", "dashboard.focus")
	if len(acks) != 1 {
		t.Fatalf("期望 1 条 dashboard.focus 回执，得到 %d", len(acks))
	}
	var payload struct {
		ThreadID string `json:"threadId"`
		OK       bool   `json:"ok"`
	}
	if err := json.Unmarshal(acks[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload.ThreadID != id || !payload.OK {
		t.Fatalf("回执 = %#v", payload)
	}
}

func TestThreadFocusRejectsInvalidID(t *testing.T) {
	b, frames := newDashboardTestBridge(t)
	runnerCalled := false
	b.focusRunner = func(context.Context, string, ...string) error {
		runnerCalled = true
		return nil
	}
	badIDs := []string{"", "short", "含大写-ABCDEF", "id; rm -rf /", string(make([]byte, 65))}
	for _, bad := range badIDs {
		if err := b.focusDashboardThread(context.Background(), "device-1", protocol.Command{
			Type: "thread.focus", ThreadID: bad,
		}); err != nil {
			t.Fatal(err)
		}
	}
	if runnerCalled {
		t.Fatal("非法 ID 不应执行聚焦命令")
	}
	acks := framesByType(t, frames, "device-1", "dashboard.focus")
	if len(acks) != len(badIDs) {
		t.Fatalf("每个非法 ID 都应有失败回执: %d", len(acks))
	}
}

func TestThreadFocusRunnerErrorAcksFailure(t *testing.T) {
	b, frames := newDashboardTestBridge(t)
	b.focusRunner = func(_ context.Context, name string, args ...string) error {
		if name == "/usr/bin/open" {
			return context.DeadlineExceeded
		}
		return nil
	}
	if err := b.focusDashboardThread(context.Background(), "device-1", protocol.Command{
		Type: "thread.focus", ThreadID: "019f5080-3e9a-7342-9afe-2f86db89a664",
	}); err != nil {
		t.Fatal(err)
	}
	acks := framesByType(t, frames, "device-1", "dashboard.focus")
	if len(acks) != 1 {
		t.Fatalf("期望 1 条失败回执，得到 %d", len(acks))
	}
	var payload struct {
		OK      bool   `json:"ok"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(acks[0].Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload.OK || payload.Message == "" {
		t.Fatalf("失败回执 = %#v", payload)
	}
}

func TestSupervisorPollOnceMergesCatalogAndTail(t *testing.T) {
	dir := t.TempDir()
	sessions := filepath.Join(dir, "sessions")
	dayDir := filepath.Join(sessions, "2026", "09", "02")
	if err := os.MkdirAll(dayDir, 0o755); err != nil {
		t.Fatal(err)
	}
	rollout := filepath.Join(dayDir, "rollout-2026-09-02T10-00-00-aaaaaaaa-2222-3333-4444-555555555555.jsonl")
	content := `{"type":"event_msg","payload":{"type":"task_started"}}` + "\n" +
		`{"type":"event_msg","payload":{"type":"agent_reasoning"}}` + "\n"
	if err := os.WriteFile(rollout, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	index := filepath.Join(dir, "session_index.jsonl")
	if err := os.WriteFile(index, []byte(`{"id":"aaaaaaaa-2222-3333-4444-555555555555","thread_name":"观察线程","updated_at":"2026-09-02T10:00:00.000000Z"}`+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	sup := observer.NewSupervisor(observer.Options{
		Now: now, Window: 24 * time.Hour, Limit: 32,
		SQLitePath: filepath.Join(dir, "missing.sqlite"), IndexPath: index, SessionsDir: sessions,
	}, 8)
	frame := sup.PollOnce(context.Background(), now)
	if len(frame) != 1 || frame[0].ID != "aaaaaaaa-2222-3333-4444-555555555555" {
		t.Fatalf("frame = %#v", frame)
	}
	if frame[0].Status != observer.StatusThinking || frame[0].Title != "观察线程" {
		t.Fatalf("合并帧 = %#v", frame[0])
	}
	if sup.Current() == nil || len(sup.Current()) != 1 {
		t.Fatalf("Current 应保留上一帧")
	}
}
