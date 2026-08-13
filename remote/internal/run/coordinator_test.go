package run

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"

	"github.com/harnessapk/remote/internal/commandcache"
	"github.com/harnessapk/remote/internal/workspace"
)

func TestRunStartCreatesAtMostOneTurnForDuplicateCommand(t *testing.T) {
	cache, _ := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	routes, _ := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	app := &fakeRunAppServer{responses: map[string]json.RawMessage{
		"thread/list": json.RawMessage(`{"data":[{"id":"thread-1","cwd":"/workspace","updatedAt":10}]}`),
		"turn/start":  json.RawMessage(`{"turn":{"id":"turn-1"}}`),
	}}
	emitted := 0
	coordinator := Coordinator{
		Cache: cache, Routes: routes, App: app, HostID: "host-1",
		ResolveWorkspace: func(deviceID, workspaceID string) (workspace.Candidate, bool) {
			return candidate("fingerprint-1"), true
		},
		InspectWorkspace: func(cwd string) (workspace.Candidate, error) { return candidate("fingerprint-1"), nil },
		Emit: func(_ context.Context, deviceID, runID, eventType string, payload json.RawMessage) (string, error) {
			emitted++
			return "event-started-1", nil
		},
	}
	command := startCommand()

	first, err := coordinator.Start(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	second, err := coordinator.Start(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}

	if app.calls["turn/start"] != 1 || emitted != 2 {
		t.Fatalf("turn/start calls=%d emitted=%d", app.calls["turn/start"], emitted)
	}
	if first.ThreadID != "thread-1" || first.TurnID != "turn-1" || second != first {
		t.Fatalf("first=%#v second=%#v", first, second)
	}
	route, ok := routes.ByRun("run-1")
	if !ok || route.ThreadID != "thread-1" || route.TurnID != "turn-1" || route.BaselineJSON == "" {
		t.Fatalf("persisted route = %#v ok=%v", route, ok)
	}
	turnParams := app.params["turn/start"].(map[string]any)
	if turnParams["clientUserMessageId"] != "command-1" || turnParams["outputSchema"] == nil {
		t.Fatalf("turn/start params = %#v", turnParams)
	}
}

func TestFingerprintMismatchStopsBeforeThreadStart(t *testing.T) {
	cache, _ := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	routes, _ := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	app := &fakeRunAppServer{}
	coordinator := Coordinator{
		Cache: cache, Routes: routes, App: app, HostID: "host-1",
		ResolveWorkspace: func(deviceID, workspaceID string) (workspace.Candidate, bool) {
			return candidate("old-fingerprint"), true
		},
		InspectWorkspace: func(cwd string) (workspace.Candidate, error) { return candidate("new-fingerprint"), nil },
		Emit:             func(context.Context, string, string, string, json.RawMessage) (string, error) { return "", nil },
	}

	_, err := coordinator.Start(context.Background(), startCommand())

	if !errors.Is(err, ErrBindingMismatch) {
		t.Fatalf("error=%v, want binding mismatch", err)
	}
	if len(app.calls) != 0 {
		t.Fatalf("app-server was called: %#v", app.calls)
	}
}

func TestRunStartReplacesStaleRecentThreadBeforeStartingTurn(t *testing.T) {
	cache, _ := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	routes, _ := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	app := &fakeRunAppServer{script: map[string][]fakeRunAppResponse{
		"thread/list": {{result: json.RawMessage(`{"data":[{"id":"thread-stale","cwd":"/workspace","updatedAt":10}]}`)}},
		"turn/start": {
			{err: errors.New(`app-server error: {"code":-32600,"message":"thread not found: thread-stale"}`)},
			{result: json.RawMessage(`{"turn":{"id":"turn-new"}}`)},
		},
		"thread/start": {{result: json.RawMessage(`{"thread":{"id":"thread-new"}}`)}},
	}}
	coordinator := Coordinator{
		Cache: cache, Routes: routes, App: app, HostID: "host-1",
		ResolveWorkspace: func(deviceID, workspaceID string) (workspace.Candidate, bool) {
			return candidate("fingerprint-1"), true
		},
		InspectWorkspace: func(cwd string) (workspace.Candidate, error) { return candidate("fingerprint-1"), nil },
		Emit:             func(context.Context, string, string, string, json.RawMessage) (string, error) { return "event-1", nil },
	}

	result, err := coordinator.Start(context.Background(), startCommand())
	if err != nil {
		t.Fatal(err)
	}

	if result.ThreadID != "thread-new" || result.TurnID != "turn-new" {
		t.Fatalf("result = %#v", result)
	}
	if app.calls["turn/start"] != 2 || app.calls["thread/start"] != 1 {
		t.Fatalf("calls = %#v", app.calls)
	}
	turnParams := app.paramsHistory["turn/start"]
	if turnParams[0].(map[string]any)["threadId"] != "thread-stale" ||
		turnParams[1].(map[string]any)["threadId"] != "thread-new" {
		t.Fatalf("turn/start params = %#v", turnParams)
	}
	route, ok := routes.ByRun("run-1")
	if !ok || route.ThreadID != "thread-new" || route.TurnID != "turn-new" {
		t.Fatalf("route = %#v ok=%v", route, ok)
	}
}

func TestRunStartDoesNotRetryAmbiguousTurnFailure(t *testing.T) {
	cache, _ := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	routes, _ := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	app := &fakeRunAppServer{script: map[string][]fakeRunAppResponse{
		"thread/list": {{result: json.RawMessage(`{"data":[{"id":"thread-1","cwd":"/workspace","updatedAt":10}]}`)}},
		"turn/start":  {{err: errors.New("connection closed before response")}},
	}}
	coordinator := Coordinator{
		Cache: cache, Routes: routes, App: app, HostID: "host-1",
		ResolveWorkspace: func(deviceID, workspaceID string) (workspace.Candidate, bool) {
			return candidate("fingerprint-1"), true
		},
		InspectWorkspace: func(cwd string) (workspace.Candidate, error) { return candidate("fingerprint-1"), nil },
		Emit:             func(context.Context, string, string, string, json.RawMessage) (string, error) { return "event-1", nil },
	}

	_, err := coordinator.Start(context.Background(), startCommand())

	if !errors.Is(err, ErrCommandUnknown) {
		t.Fatalf("error = %v, want command unknown", err)
	}
	if app.calls["turn/start"] != 1 || app.calls["thread/start"] != 0 {
		t.Fatalf("ambiguous failure was retried: %#v", app.calls)
	}
}

func TestUnknownTurnStartReconcilesWithoutAutomaticReplay(t *testing.T) {
	path := filepath.Join(t.TempDir(), "commands.json")
	cache, _ := commandcache.Open(path)
	command := startCommand()
	if _, execute, err := cache.Begin(command.CommandID, "run.start", command.PayloadSHA256()); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	reopened, err := commandcache.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	routes, _ := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	app := &fakeRunAppServer{}
	coordinator := Coordinator{Cache: reopened, Routes: routes, App: app, HostID: "host-1"}

	_, err = coordinator.Start(context.Background(), command)

	if !errors.Is(err, ErrCommandUnknown) {
		t.Fatalf("error=%v, want unknown", err)
	}
	if len(app.calls) != 0 {
		t.Fatalf("unknown command was replayed: %#v", app.calls)
	}
}

func startCommand() StartCommand {
	return StartCommand{
		CommandID: "command-1", RunID: "run-1", BindingID: "binding-1",
		WorkspaceID: "workspace-1", DeviceID: "device-1",
		RepositoryFingerprint: "fingerprint-1", Objective: "实现 M2",
	}
}

func candidate(fingerprint string) workspace.Candidate {
	return workspace.Candidate{
		WorkspaceID: "workspace-1", DisplayName: "workspace", CWD: "/workspace",
		RepositoryLabel: "github.com/acme/workspace", RepositoryFingerprint: fingerprint,
	}
}

type fakeRunAppServer struct {
	responses     map[string]json.RawMessage
	script        map[string][]fakeRunAppResponse
	calls         map[string]int
	params        map[string]any
	paramsHistory map[string][]any
}

type fakeRunAppResponse struct {
	result json.RawMessage
	err    error
}

func (f *fakeRunAppServer) Call(_ context.Context, method string, params any) (json.RawMessage, error) {
	if f.calls == nil {
		f.calls = map[string]int{}
	}
	f.calls[method]++
	if f.params == nil {
		f.params = map[string]any{}
	}
	f.params[method] = params
	if f.paramsHistory == nil {
		f.paramsHistory = map[string][]any{}
	}
	f.paramsHistory[method] = append(f.paramsHistory[method], params)
	if scripted := f.script[method]; len(scripted) > 0 {
		response := scripted[0]
		f.script[method] = scripted[1:]
		return response.result, response.err
	}
	if response := f.responses[method]; response != nil {
		return response, nil
	}
	return nil, errors.New("unexpected app-server call: " + method)
}
