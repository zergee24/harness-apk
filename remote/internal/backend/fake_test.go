package backend

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

func TestFakeNormalizesEmptyIDAndScriptsResponses(t *testing.T) {
	f := NewFake("")
	if f.ID() != "codex" {
		t.Fatalf("id = %q, want codex", f.ID())
	}
	if NormalizeID("") != "codex" || NormalizeID("dsh") != "dsh" {
		t.Fatal("normalize failed")
	}
	ctx := context.Background()
	result, err := f.Call(ctx, "thread/list", map[string]any{})
	if err != nil {
		t.Fatal(err)
	}
	if string(result) != `{"result":{"echo":"thread/list"}}` {
		t.Fatalf("default result = %s", result)
	}
	f.OnScript("thread/start", func(method string, params any) (json.RawMessage, error) {
		return json.RawMessage(`{"thread":{"id":"t-1"}}`), nil
	})
	result, err = f.Call(ctx, "thread/start", map[string]any{"cwd": "/x"})
	if err != nil {
		t.Fatal(err)
	}
	if string(result) != `{"thread":{"id":"t-1"}}` {
		t.Fatalf("scripted result = %s", result)
	}
}

func TestFakeEmitAndClose(t *testing.T) {
	f := NewFake("dsh")
	f.Emit("turn/completed", map[string]any{"threadId": "s-1"})
	select {
	case message := <-f.Messages():
		if message.BackendID != "dsh" || message.Method != "turn/completed" {
			t.Fatalf("message = %+v", message)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for emitted message")
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	if _, ok := <-f.Messages(); ok {
		t.Fatal("messages channel should be closed")
	}
}

func TestFakeCrashResolvesDone(t *testing.T) {
	f := NewFake("codex")
	f.Crash(errors.New("boom"))
	select {
	case err := <-f.Done():
		if err == nil || err.Error() != "boom" {
			t.Fatalf("done cause = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("done did not resolve after crash")
	}
}

func TestFakeCapabilitiesOverride(t *testing.T) {
	f := NewFake("dsh").SetCapabilities([]string{"run.lifecycle.v1"})
	caps := f.Capabilities()
	if len(caps) != 1 || caps[0] != "run.lifecycle.v1" {
		t.Fatalf("caps = %v", caps)
	}
}
