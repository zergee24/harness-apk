package backend

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"reflect"
	"syscall"
	"testing"

	"github.com/harnessapk/remote/internal/agent"
)

func TestAppServerAdapterStartThread(t *testing.T) {
	raw := NewFake("codex")
	raw.OnScript("thread/start", func(method string, params any) (json.RawMessage, error) {
		assertJSONParams(t, params, `{"cwd":"/worktree"}`)
		return json.RawMessage(`{"thread":{"id":"thread-1"}}`), nil
	})

	out, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.StartThread{CWD: "/worktree"})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if out.StartedThread == nil || out.StartedThread.ID != "thread-1" {
		t.Fatalf("Execute() outcome = %#v", out)
	}
	assertCalls(t, raw, "thread/start")
}

func TestAppServerAdapterStartTurn(t *testing.T) {
	raw := NewFake("codex")
	schema := map[string]any{"type": "object", "required": []any{"answer"}}
	raw.OnScript("turn/start", func(method string, params any) (json.RawMessage, error) {
		assertJSONParams(t, params, `{"threadId":"thread-1","input":[{"type":"text","text":"hello"}],"clientUserMessageId":"message-1","outputSchema":{"type":"object","required":["answer"]}}`)
		return json.RawMessage(`{"turn":{"id":"turn-1"}}`), nil
	})

	out, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.StartTurn{
		ThreadID: "thread-1", Text: "hello", ClientMessageID: "message-1", CompletionSchema: schema,
	})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if out.StartedTurn == nil || out.StartedTurn.ID != "turn-1" {
		t.Fatalf("Execute() outcome = %#v", out)
	}
	assertCalls(t, raw, "turn/start")
}

func TestAppServerAdapterSteerTurnResponseShapes(t *testing.T) {
	tests := []struct {
		name string
		raw  string
		want string
	}{
		{name: "nested turn", raw: `{"turn":{"id":"turn-2"}}`, want: "turn-2"},
		{name: "top-level turn id", raw: `{"turnId":"turn-3"}`, want: "turn-3"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			raw := NewFake("codex")
			raw.OnScript("turn/steer", func(method string, params any) (json.RawMessage, error) {
				assertJSONParams(t, params, `{"threadId":"thread-1","expectedTurnId":"turn-1","input":[{"type":"text","text":"more"}]}`)
				return json.RawMessage(tt.raw), nil
			})

			out, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.SteerTurn{
				ThreadID: "thread-1", ExpectedTurnID: "turn-1", Text: "more",
			})
			if err != nil {
				t.Fatalf("Execute() error = %v", err)
			}
			if out.StartedTurn == nil || out.StartedTurn.ID != tt.want {
				t.Fatalf("Execute() outcome = %#v", out)
			}
		})
	}
}

func TestAppServerAdapterSteerTurnMalformedResponse(t *testing.T) {
	raw := NewFake("codex")
	raw.OnScript("turn/steer", func(string, any) (json.RawMessage, error) {
		return json.RawMessage(`{"turn":{}}`), nil
	})

	_, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.SteerTurn{
		ThreadID: "thread-1", ExpectedTurnID: "turn-1", Text: "more",
	})
	if !errors.Is(err, agent.ErrProtocol) {
		t.Fatalf("Execute() error = %v, want ErrProtocol", err)
	}
}

func TestAppServerAdapterInterruptTurn(t *testing.T) {
	raw := NewFake("codex")
	raw.OnScript("turn/interrupt", func(method string, params any) (json.RawMessage, error) {
		assertJSONParams(t, params, `{"threadId":"thread-1","turnId":"turn-1"}`)
		return json.RawMessage(`{}`), nil
	})

	out, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.InterruptTurn{ThreadID: "thread-1", TurnID: "turn-1"})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if !out.Empty {
		t.Fatalf("Execute() outcome = %#v, want Empty", out)
	}
}

func TestAppServerAdapterListThreads(t *testing.T) {
	raw := NewFake("codex")
	raw.OnScript("thread/list", func(method string, params any) (json.RawMessage, error) {
		assertJSONParams(t, params, `{"limit":50,"sortKey":"updated_at","sortDirection":"desc","sourceKinds":["cli","vscode","exec","appServer"]}`)
		return json.RawMessage(`{"data":[{"id":"thread-1","cwd":"/wanted"},{"id":"thread-2","cwd":"/other"},{"id":"thread-3","cwd":"/wanted"}]}`), nil
	})

	out, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.ListThreads{Query: agent.ThreadQuery{CWD: "/wanted"}})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	want := []agent.ThreadSummary{{ID: "thread-1", CWD: "/wanted"}, {ID: "thread-3", CWD: "/wanted"}}
	if out.Threads == nil || !reflect.DeepEqual(out.Threads.Threads, want) {
		t.Fatalf("Execute() outcome = %#v, want %#v", out, want)
	}
}

func TestAppServerAdapterListThreadsWithoutCWDReturnsAllThreads(t *testing.T) {
	raw := NewFake("codex")
	raw.OnScript("thread/list", func(method string, params any) (json.RawMessage, error) {
		assertJSONParams(t, params, `{"limit":50,"sortKey":"updated_at","sortDirection":"desc","sourceKinds":["cli","vscode","exec","appServer"]}`)
		return json.RawMessage(`{"data":[{"id":"thread-1","cwd":"/one"},{"id":"thread-2","cwd":"/two"}]}`), nil
	})

	out, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.ListThreads{})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	want := []agent.ThreadSummary{{ID: "thread-1", CWD: "/one"}, {ID: "thread-2", CWD: "/two"}}
	if out.Threads == nil || !reflect.DeepEqual(out.Threads.Threads, want) {
		t.Fatalf("Execute() outcome = %#v, want %#v", out, want)
	}
}

func TestAppServerAdapterReadThread(t *testing.T) {
	raw := NewFake("codex")
	raw.OnScript("thread/read", func(method string, params any) (json.RawMessage, error) {
		assertJSONParams(t, params, `{"threadId":"thread-1","includeTurns":true}`)
		return json.RawMessage(`{"thread":{"id":"thread-1","cwd":"/worktree","turns":[{"id":"turn-1","status":"completed"},{"id":"turn-2","status":"inProgress"}]}}`), nil
	})

	out, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.ReadThread{ThreadID: "thread-1", IncludeTurns: true})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	want := &agent.ThreadSnapshot{
		ID: "thread-1", CWD: "/worktree",
		Turns: []agent.TurnSnapshot{{ID: "turn-1", Status: "completed"}, {ID: "turn-2", Status: "inProgress"}},
	}
	if !reflect.DeepEqual(out.Thread, want) {
		t.Fatalf("Execute() Thread = %#v, want %#v", out.Thread, want)
	}
}

func TestAppServerAdapterManifestAndUnsupportedOperation(t *testing.T) {
	all := []agent.OperationKind{
		agent.OperationListThreads, agent.OperationReadThread, agent.OperationStartThread,
		agent.OperationStartTurn, agent.OperationSteerTurn, agent.OperationInterruptTurn,
	}

	codex := NewFake("codex").SetCapabilities([]string{"mobile.one", "mobile.two"})
	adapter := NewAppServerAdapter(codex)
	manifest := adapter.Manifest()
	if manifest.BackendID != codex.ID() || manifest.Name != codex.Name() || manifest.ProcessEpoch != codex.ProcessEpoch() {
		t.Fatalf("Manifest() identity = %#v", manifest)
	}
	if !reflect.DeepEqual(manifest.MobileCapabilities, []string{"mobile.one", "mobile.two"}) {
		t.Fatalf("Manifest() capabilities = %#v", manifest.MobileCapabilities)
	}
	for _, kind := range all {
		if !manifest.Operations[kind] {
			t.Errorf("Manifest() operation %q = false, want true", kind)
		}
	}

	manifest.MobileCapabilities[0] = "mutated"
	manifest.Operations[agent.OperationStartThread] = false
	again := adapter.Manifest()
	if again.MobileCapabilities[0] != "mobile.one" || !again.Operations[agent.OperationStartThread] {
		t.Fatalf("Manifest() did not return defensive copies: %#v", again)
	}

	dsh := NewFake("dsh")
	dshAdapter := NewAppServerAdapter(dsh)
	if dshAdapter.Manifest().Operations[agent.OperationInterruptTurn] {
		t.Fatal("dsh Manifest() interrupt_turn = true, want false")
	}
	_, err := dshAdapter.Execute(context.Background(), agent.InterruptTurn{ThreadID: "thread-1", TurnID: "turn-1"})
	if !errors.Is(err, agent.ErrUnsupported) {
		t.Fatalf("dsh Execute() error = %v, want ErrUnsupported", err)
	}
	if got := len(dsh.Calls()); got != 0 {
		t.Fatalf("dsh raw calls = %d, want 0", got)
	}
}

func TestAppServerAdapterRejectsInvalidOperationsBeforeRawCall(t *testing.T) {
	tests := []struct {
		name string
		op   agent.Operation
	}{
		{name: "read thread id", op: agent.ReadThread{}},
		{name: "start cwd", op: agent.StartThread{}},
		{name: "start turn thread id", op: agent.StartTurn{Text: "hello", ClientMessageID: "message-1"}},
		{name: "start turn text", op: agent.StartTurn{ThreadID: "thread-1", ClientMessageID: "message-1"}},
		{name: "start turn client message id", op: agent.StartTurn{ThreadID: "thread-1", Text: "hello"}},
		{name: "steer thread id", op: agent.SteerTurn{ExpectedTurnID: "turn-1", Text: "more"}},
		{name: "steer expected turn id", op: agent.SteerTurn{ThreadID: "thread-1", Text: "more"}},
		{name: "steer text", op: agent.SteerTurn{ThreadID: "thread-1", ExpectedTurnID: "turn-1"}},
		{name: "interrupt thread id", op: agent.InterruptTurn{TurnID: "turn-1"}},
		{name: "interrupt turn id", op: agent.InterruptTurn{ThreadID: "thread-1"}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			raw := NewFake("codex")
			_, err := NewAppServerAdapter(raw).Execute(context.Background(), tt.op)
			if !errors.Is(err, agent.ErrInvalid) {
				t.Fatalf("Execute() error = %v, want ErrInvalid", err)
			}
			if got := len(raw.Calls()); got != 0 {
				t.Fatalf("raw calls = %d, want 0", got)
			}
		})
	}

	_, err := NewAppServerAdapter(nil).Execute(context.Background(), agent.StartThread{CWD: "/worktree"})
	if !errors.Is(err, agent.ErrInvalid) {
		t.Fatalf("nil backend Execute() error = %v, want ErrInvalid", err)
	}
}

func TestAppServerAdapterMapsUnavailableErrors(t *testing.T) {
	for _, cause := range []error{context.Canceled, context.DeadlineExceeded, io.EOF, io.ErrClosedPipe, syscall.EPIPE} {
		t.Run(cause.Error(), func(t *testing.T) {
			raw := NewFake("codex")
			raw.OnScript("thread/start", func(string, any) (json.RawMessage, error) { return nil, cause })
			_, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.StartThread{CWD: "/worktree"})
			if !errors.Is(err, agent.ErrUnavailable) {
				t.Fatalf("Execute() error = %v, want ErrUnavailable", err)
			}
		})
	}
}

func TestAppServerAdapterPreservesProviderErrors(t *testing.T) {
	cause := errors.New("provider rejected request")
	raw := NewFake("codex")
	raw.OnScript("thread/start", func(string, any) (json.RawMessage, error) { return nil, cause })

	_, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.StartThread{CWD: "/worktree"})
	if err != cause {
		t.Fatalf("Execute() error = %v, want original error %v", err, cause)
	}
	if errors.Is(err, agent.ErrUnavailable) {
		t.Fatalf("Execute() error = %v, must not be ErrUnavailable", err)
	}
}

func TestAppServerAdapterMapsMalformedResponsesToProtocol(t *testing.T) {
	tests := []struct {
		name   string
		method string
		op     agent.Operation
		raw    string
	}{
		{name: "bad json", method: "thread/start", op: agent.StartThread{CWD: "/worktree"}, raw: `{`},
		{name: "missing thread id", method: "thread/start", op: agent.StartThread{CWD: "/worktree"}, raw: `{"thread":{}}`},
		{name: "missing turn id", method: "turn/start", op: agent.StartTurn{ThreadID: "thread-1", Text: "hello", ClientMessageID: "message-1"}, raw: `{"turn":{}}`},
		{name: "list item missing id", method: "thread/list", op: agent.ListThreads{Query: agent.ThreadQuery{CWD: "/worktree"}}, raw: `{"data":[{"cwd":"/worktree"}]}`},
		{name: "read missing id", method: "thread/read", op: agent.ReadThread{ThreadID: "thread-1"}, raw: `{"thread":{}}`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			raw := NewFake("codex")
			raw.OnScript(tt.method, func(string, any) (json.RawMessage, error) { return json.RawMessage(tt.raw), nil })
			_, err := NewAppServerAdapter(raw).Execute(context.Background(), tt.op)
			if !errors.Is(err, agent.ErrProtocol) {
				t.Fatalf("Execute() error = %v, want ErrProtocol", err)
			}
		})
	}
}

func assertJSONParams(t *testing.T, got any, want string) {
	t.Helper()
	gotJSON, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("json.Marshal(params) error = %v", err)
	}
	var gotValue, wantValue any
	if err := json.Unmarshal(gotJSON, &gotValue); err != nil {
		t.Fatalf("json.Unmarshal(got params) error = %v", err)
	}
	if err := json.Unmarshal([]byte(want), &wantValue); err != nil {
		t.Fatalf("json.Unmarshal(want params) error = %v", err)
	}
	if !reflect.DeepEqual(gotValue, wantValue) {
		t.Fatalf("params = %s, want %s", gotJSON, want)
	}
}

func assertCalls(t *testing.T, raw *Fake, methods ...string) {
	t.Helper()
	calls := raw.Calls()
	if len(calls) != len(methods) {
		t.Fatalf("calls = %#v, want methods %v", calls, methods)
	}
	for i, method := range methods {
		if calls[i].Method != method {
			t.Fatalf("calls[%d].Method = %q, want %q", i, calls[i].Method, method)
		}
	}
}
