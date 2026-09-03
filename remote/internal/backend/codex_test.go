package backend

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"
)

// TestMain re-executes this binary as a stdio app-server stub when
// HARNESS_BACKEND_STUB is set, so Codex is tested against a real process.
func TestMain(m *testing.M) {
	if os.Getenv("HARNESS_BACKEND_STUB") == "1" {
		runStub()
		os.Exit(0)
	}
	os.Exit(m.Run())
}

// runStub answers the canonical app-server surface on stdio: initialize,
// thread/start, turn/start, and one turn/completed notification after a turn.
func runStub() {
	scanner := bufio.NewScanner(os.Stdin)
	writer := bufio.NewWriter(os.Stdout)
	for scanner.Scan() {
		var message struct {
			ID     any             `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		if json.Unmarshal(scanner.Bytes(), &message) != nil {
			continue
		}
		if message.Method == "" {
			continue
		}
		switch message.Method {
		case "initialize":
			_, _ = writer.WriteString(`{"id":` + rawID(message.ID) + `,"result":{"protocolVersion":1,"capabilities":{},"serverInfo":{"name":"stub","version":"0"}}}` + "\n")
		case "thread/start":
			_, _ = writer.WriteString(`{"id":` + rawID(message.ID) + `,"result":{"thread":{"id":"stub-thread","cwd":"/stub"}}}` + "\n")
		case "turn/start":
			_, _ = writer.WriteString(`{"id":` + rawID(message.ID) + `,"result":{"turn":{"id":"stub-turn","threadId":"stub-thread","status":"completed"}}}` + "\n")
			_, _ = writer.WriteString(`{"method":"turn/completed","params":{"threadId":"stub-thread","turn":{"id":"stub-turn","status":"completed"}}}` + "\n")
		default:
			_, _ = writer.WriteString(`{"id":` + rawID(message.ID) + `,"result":{"echo":"` + message.Method + `"}}` + "\n")
		}
		_ = writer.Flush()
	}
}

func rawID(id any) string {
	raw, err := json.Marshal(id)
	if err != nil {
		return "null"
	}
	return string(raw)
}

func stubSpec(t *testing.T) Spec {
	t.Helper()
	return Spec{
		ID: "codex", Name: "Stub Codex", Capabilities: CodexCapabilities(),
		Exec: os.Args[0],
		Args: []string{"--stub"},
	}
}

func TestCodexCallAndNotificationRoundTrip(t *testing.T) {
	t.Setenv("HARNESS_BACKEND_STUB", "1")
	c, err := StartCodex(stubSpec(t))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c.Start(ctx)

	result, err := c.Call(ctx, "initialize", map[string]any{"clientInfo": map[string]string{"name": "test"}})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(result), "stub") {
		t.Fatalf("initialize result = %s", result)
	}
	thread, err := c.Call(ctx, "thread/start", map[string]any{"cwd": "/tmp"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(thread), "stub-thread") {
		t.Fatalf("thread/start result = %s", thread)
	}
	turn, err := c.Call(ctx, "turn/start", map[string]any{"threadId": "stub-thread", "input": []map[string]string{{"type": "text", "text": "hi"}}})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(turn), "stub-turn") {
		t.Fatalf("turn/start result = %s", turn)
	}

	select {
	case message := <-c.Messages():
		if message.BackendID != "codex" || message.Method != "turn/completed" {
			t.Fatalf("message = %+v", message)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for turn/completed notification")
	}
}

func TestCodexCloseResolvesDoneAndClosesMessages(t *testing.T) {
	t.Setenv("HARNESS_BACKEND_STUB", "1")
	c, err := StartCodex(stubSpec(t))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c.Start(ctx)
	if c.ProcessEpoch() == "" {
		t.Fatal("process epoch is empty")
	}
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if _, ok := <-c.Messages(); ok {
		t.Fatal("messages channel should be closed")
	}
	select {
	case <-c.Done():
		// Close tears down the process, so Done resolves; the supervisor uses
		// its own shutdown flag to tell a requested stop from a crash.
	case <-time.After(5 * time.Second):
		t.Fatal("done should resolve after close")
	}
}
