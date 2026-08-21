package run

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"

	"github.com/harnessapk/remote/internal/agent"
	"github.com/harnessapk/remote/internal/commandcache"
	"github.com/harnessapk/remote/internal/workspace"
)

func TestRunStartUsesTypedRuntimeWithoutRawRPC(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{outcome: agent.Outcome{Threads: &agent.ThreadPage{}}})
	runtime.script(agent.OperationStartThread, scriptedStep{outcome: agent.Outcome{StartedThread: &agent.ThreadRef{ID: "thread-new"}}})
	runtime.script(agent.OperationStartTurn, scriptedStep{outcome: agent.Outcome{StartedTurn: &agent.TurnRef{ID: "turn-1"}}})

	result, err := coordinator.Start(context.Background(), startCommand())
	if err != nil {
		t.Fatal(err)
	}
	if result.ThreadID != "thread-new" || result.TurnID != "turn-1" {
		t.Fatalf("result = %#v", result)
	}
	calls := runtime.Calls()
	if got, want := operationKinds(calls), []agent.OperationKind{
		agent.OperationListThreads,
		agent.OperationStartThread,
		agent.OperationStartTurn,
	}; !reflect.DeepEqual(got, want) {
		t.Fatalf("kinds = %v, want %v", got, want)
	}
	list := calls[0].(agent.ListThreads)
	if list.Query.CWD != "/workspace" {
		t.Fatalf("ListThreads query = %#v", list.Query)
	}
	turn := calls[2].(agent.StartTurn)
	if turn.ThreadID != "thread-new" || turn.Text != "实现 M2" ||
		turn.ClientMessageID != "command-1" || turn.CompletionSchema == nil {
		t.Fatalf("StartTurn = %#v", turn)
	}
}

func TestRunStartUsesInjectedTurnGate(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{outcome: threadPage("thread-1")})
	var gated []agent.Operation
	coordinator.ExecuteTurn = func(_ context.Context, operation agent.Operation) (agent.Outcome, error) {
		gated = append(gated, operation)
		return agent.Outcome{StartedTurn: &agent.TurnRef{ID: "turn-gated"}}, nil
	}

	result, err := coordinator.Start(context.Background(), startCommand())
	if err != nil {
		t.Fatal(err)
	}
	if len(gated) != 1 {
		t.Fatalf("gated calls = %#v", gated)
	}
	if turn, ok := gated[0].(agent.StartTurn); !ok || turn.ThreadID != "thread-1" {
		t.Fatalf("gated operation = %#v", gated[0])
	}
	if result.TurnID != "turn-gated" {
		t.Fatalf("result = %#v", result)
	}
	if got, want := operationKinds(runtime.Calls()), []agent.OperationKind{agent.OperationListThreads}; !reflect.DeepEqual(got, want) {
		t.Fatalf("runtime kinds = %v, want %v", got, want)
	}
}

func TestRunStartCreatesAtMostOneTurnForDuplicateCommand(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{outcome: threadPage("thread-1")})
	runtime.script(agent.OperationStartTurn, scriptedStep{outcome: agent.Outcome{StartedTurn: &agent.TurnRef{ID: "turn-1"}}})
	emitted := 0
	coordinator.Emit = func(_ context.Context, deviceID, runID, eventType string, payload json.RawMessage) (string, error) {
		emitted++
		return "event-started-1", nil
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

	if runtime.count(agent.OperationStartTurn) != 1 || emitted != 2 {
		t.Fatalf("start_turn calls=%d emitted=%d", runtime.count(agent.OperationStartTurn), emitted)
	}
	if first.ThreadID != "thread-1" || first.TurnID != "turn-1" || second != first {
		t.Fatalf("first=%#v second=%#v", first, second)
	}
	route, ok := coordinator.Routes.ByRun("run-1")
	if !ok || route.BackendID != "dsh" || route.ThreadID != "thread-1" || route.TurnID != "turn-1" || route.BaselineJSON == "" {
		t.Fatalf("persisted route = %#v ok=%v", route, ok)
	}
	turn := runtime.Calls()[1].(agent.StartTurn)
	if turn.ClientMessageID != "command-1" || turn.CompletionSchema == nil {
		t.Fatalf("StartTurn = %#v", turn)
	}
}

func TestRunStartReplaysCacheWrittenBeforeBackendIdentityWasAdded(t *testing.T) {
	cache, _ := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	command := startCommand()
	command.BackendID = "codex"
	legacyHash := command.legacyPayloadSHA256()
	if _, execute, err := cache.Begin(command.CommandID, "run.start", legacyHash); err != nil || !execute {
		t.Fatalf("legacy begin execute=%v err=%v", execute, err)
	}
	if _, err := cache.Complete(command.CommandID, "event-legacy", json.RawMessage(`{"runId":"run-1","threadId":"thread-1","turnId":"turn-1","eventId":"event-legacy"}`)); err != nil {
		t.Fatal(err)
	}
	routes, _ := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err := routes.Put(Route{RunID: command.RunID, BackendID: "codex", HostID: "host-1", DeviceID: command.DeviceID, ThreadID: "thread-1", TurnID: "turn-1"}); err != nil {
		t.Fatal(err)
	}
	coordinator := Coordinator{Cache: cache, Routes: routes, HostID: "host-1"}

	result, err := coordinator.Start(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	if result.ThreadID != "thread-1" || result.TurnID != "turn-1" {
		t.Fatalf("replayed result=%#v", result)
	}
	migrated, ok := routes.ByRun(command.RunID)
	if !ok || migrated.BackendID != "codex" {
		t.Fatalf("cached route backend owner changed: %#v ok=%v", migrated, ok)
	}
}

func TestRunStartMigratesLegacyCacheIdentityOnlyOnce(t *testing.T) {
	cache, _ := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	command := startCommand()
	command.BackendID = "codex"
	if _, execute, err := cache.Begin(command.CommandID, "run.start", command.legacyPayloadSHA256()); err != nil || !execute {
		t.Fatalf("legacy begin execute=%v err=%v", execute, err)
	}
	if _, err := cache.Complete(command.CommandID, "event-legacy", json.RawMessage(`{"runId":"run-1","threadId":"thread-1","turnId":"turn-1","eventId":"event-legacy"}`)); err != nil {
		t.Fatal(err)
	}
	routes, _ := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err := routes.Put(Route{RunID: command.RunID, BackendID: "codex", HostID: "host-1", DeviceID: command.DeviceID, ThreadID: "thread-1", TurnID: "turn-1"}); err != nil {
		t.Fatal(err)
	}
	coordinator := Coordinator{Cache: cache, Routes: routes, HostID: "host-1"}
	if _, err := coordinator.Start(context.Background(), command); err != nil {
		t.Fatalf("first migration: %v", err)
	}
	otherBackend := command
	otherBackend.BackendID = "dsh"
	if _, err := coordinator.Start(context.Background(), otherBackend); err == nil {
		t.Fatal("migrated cache identity was reusable by another backend")
	}
	route, _ := routes.ByRun(command.RunID)
	if route.BackendID != "codex" {
		t.Fatalf("route changed to backend %q", route.BackendID)
	}
}

func TestRunStartMigratesLegacyCacheIdentityWithoutRoute(t *testing.T) {
	cache, _ := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	command := startCommand()
	command.BackendID = "codex"
	if _, execute, err := cache.Begin(command.CommandID, "run.start", command.legacyPayloadSHA256()); err != nil || !execute {
		t.Fatalf("legacy begin execute=%v err=%v", execute, err)
	}
	if _, err := cache.Complete(command.CommandID, "event-legacy", json.RawMessage(`{"runId":"run-1","threadId":"thread-1","turnId":"turn-1","eventId":"event-legacy"}`)); err != nil {
		t.Fatal(err)
	}
	coordinator := Coordinator{Cache: cache, HostID: "host-1"}
	if _, err := coordinator.Start(context.Background(), command); err != nil {
		t.Fatalf("first migration: %v", err)
	}
	otherBackend := command
	otherBackend.BackendID = "dsh"
	if _, err := coordinator.Start(context.Background(), otherBackend); err == nil {
		t.Fatal("legacy cache without route was reusable by another backend")
	}
}

func TestRunStartCacheRejectsSameCommandIDOnAnotherBackend(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{outcome: threadPage("thread-1")})
	runtime.script(agent.OperationStartTurn, scriptedStep{outcome: agent.Outcome{StartedTurn: &agent.TurnRef{ID: "turn-1"}}})
	command := startCommand()
	if _, err := coordinator.Start(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	command.BackendID = "codex"
	if _, err := coordinator.Start(context.Background(), command); err == nil {
		t.Fatal("same command id was reused across backends")
	}
}

func TestFreshCommandCannotClobberExistingRunRouteBeforeValidation(t *testing.T) {
	coordinator, runtime := runFixture(t)
	existing := Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-existing", TurnID: "turn-existing", BaselineJSON: `{"cwd":"/existing"}`,
	}
	if err := coordinator.Routes.Put(existing); err != nil {
		t.Fatal(err)
	}
	coordinator.ResolveWorkspace = func(deviceID, workspaceID string) (workspace.Candidate, bool) {
		return workspace.Candidate{}, false
	}
	command := startCommand()
	command.CommandID = "command-fresh"
	if _, err := coordinator.Start(context.Background(), command); err == nil {
		t.Fatal("fresh command reused an existing run route")
	}
	preserved, _ := coordinator.Routes.ByRun(existing.RunID)
	if preserved.ThreadID != existing.ThreadID || preserved.TurnID != existing.TurnID || preserved.BaselineJSON != existing.BaselineJSON {
		t.Fatalf("existing route was clobbered: %#v", preserved)
	}
	if len(runtime.Calls()) != 0 {
		t.Fatalf("runtime was called: %#v", runtime.Calls())
	}
}

func TestRunStartingCanResolveBackendBeforeWorkspaceValidation(t *testing.T) {
	coordinator, _ := runFixture(t)
	seenBackend := ""
	coordinator.ResolveWorkspace = func(deviceID, workspaceID string) (workspace.Candidate, bool) {
		return workspace.Candidate{}, false
	}
	coordinator.Emit = func(_ context.Context, _, runID, eventType string, _ json.RawMessage) (string, error) {
		if eventType == "run.starting" {
			route, ok := coordinator.Routes.ByRun(runID)
			if ok {
				seenBackend = route.BackendID
			}
		}
		return "event-starting", nil
	}
	_, _ = coordinator.Start(context.Background(), startCommand())
	if seenBackend != "dsh" {
		t.Fatalf("run.starting backend=%q", seenBackend)
	}
}

func TestFingerprintMismatchStopsBeforeThreadStart(t *testing.T) {
	coordinator, runtime := runFixture(t)
	coordinator.ResolveWorkspace = func(deviceID, workspaceID string) (workspace.Candidate, bool) {
		return candidate("old-fingerprint"), true
	}

	_, err := coordinator.Start(context.Background(), startCommand())

	if !errors.Is(err, ErrBindingMismatch) {
		t.Fatalf("error=%v, want binding mismatch", err)
	}
	if len(runtime.Calls()) != 0 {
		t.Fatalf("runtime was called: %#v", runtime.Calls())
	}
}

func TestRunStartReplacesStaleRecentThreadBeforeStartingTurn(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{outcome: threadPage("thread-stale")})
	runtime.script(agent.OperationStartThread, scriptedStep{outcome: agent.Outcome{StartedThread: &agent.ThreadRef{ID: "thread-new"}}})
	runtime.script(agent.OperationStartTurn,
		scriptedStep{err: errors.New(`app-server error: {"code":-32600,"message":"thread not found: thread-stale"}`)},
		scriptedStep{outcome: agent.Outcome{StartedTurn: &agent.TurnRef{ID: "turn-new"}}},
	)

	result, err := coordinator.Start(context.Background(), startCommand())
	if err != nil {
		t.Fatal(err)
	}

	if result.ThreadID != "thread-new" || result.TurnID != "turn-new" {
		t.Fatalf("result = %#v", result)
	}
	if runtime.count(agent.OperationStartTurn) != 2 || runtime.count(agent.OperationStartThread) != 1 {
		t.Fatalf("calls = %v", operationKinds(runtime.Calls()))
	}
	turns := []string{}
	for _, call := range runtime.Calls() {
		if turn, ok := call.(agent.StartTurn); ok {
			turns = append(turns, turn.ThreadID)
		}
	}
	if !reflect.DeepEqual(turns, []string{"thread-stale", "thread-new"}) {
		t.Fatalf("turn thread ids = %v", turns)
	}
	route, ok := coordinator.Routes.ByRun("run-1")
	if !ok || route.ThreadID != "thread-new" || route.TurnID != "turn-new" {
		t.Fatalf("route = %#v ok=%v", route, ok)
	}
}

func TestRunStartDoesNotRetryAmbiguousTurnFailure(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{outcome: threadPage("thread-1")})
	runtime.script(agent.OperationStartTurn,
		scriptedStep{err: errors.New("connection closed before response")},
	)

	_, err := coordinator.Start(context.Background(), startCommand())

	if !errors.Is(err, ErrCommandUnknown) {
		t.Fatalf("error = %v, want command unknown", err)
	}
	if runtime.count(agent.OperationStartTurn) != 1 || runtime.count(agent.OperationStartThread) != 0 {
		t.Fatalf("ambiguous failure was retried: %v", operationKinds(runtime.Calls()))
	}
}

func TestRunStartFailsDeterministicallyWhenThreadListFails(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{err: errors.New("provider list failure")})

	_, err := coordinator.Start(context.Background(), startCommand())

	if err == nil || !strings.Contains(err.Error(), "provider list failure") {
		t.Fatalf("error = %v", err)
	}
	record, ok := coordinator.Cache.Lookup(startCommand().CommandID)
	if !ok || record.Status != commandcache.StatusFailed {
		t.Fatalf("record = %#v ok=%v", record, ok)
	}
}

func TestRunStartMarksUnknownWhenTurnOutcomeMissingTurnID(t *testing.T) {
	coordinator, runtime := runFixture(t)
	runtime.script(agent.OperationListThreads, scriptedStep{outcome: threadPage("thread-1")})
	runtime.script(agent.OperationStartTurn, scriptedStep{outcome: agent.Outcome{}})

	_, err := coordinator.Start(context.Background(), startCommand())

	if !errors.Is(err, ErrCommandUnknown) || !errors.Is(err, agent.ErrProtocol) {
		t.Fatalf("error = %v, want unknown protocol violation", err)
	}
	record, ok := coordinator.Cache.Lookup(startCommand().CommandID)
	if !ok || record.Status != commandcache.StatusUnknown {
		t.Fatalf("record = %#v ok=%v", record, ok)
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
	coordinator, runtime := runFixture(t)
	coordinator.Cache = reopened
	coordinator.Routes = routes

	_, err = coordinator.Start(context.Background(), command)

	if !errors.Is(err, ErrCommandUnknown) {
		t.Fatalf("error=%v, want unknown", err)
	}
	if len(runtime.Calls()) != 0 {
		t.Fatalf("unknown command was replayed: %#v", runtime.Calls())
	}
}

func startCommand() StartCommand {
	return StartCommand{
		CommandID: "command-1", RunID: "run-1", BindingID: "binding-1",
		WorkspaceID: "workspace-1", DeviceID: "device-1", BackendID: "dsh",
		RepositoryFingerprint: "fingerprint-1", Objective: "实现 M2",
	}
}

func candidate(fingerprint string) workspace.Candidate {
	return workspace.Candidate{
		WorkspaceID: "workspace-1", DisplayName: "workspace", CWD: "/workspace",
		RepositoryLabel: "github.com/acme/workspace", RepositoryFingerprint: fingerprint,
	}
}

func threadPage(ids ...string) agent.Outcome {
	threads := make([]agent.ThreadSummary, 0, len(ids))
	for _, id := range ids {
		threads = append(threads, agent.ThreadSummary{ID: id, CWD: "/workspace"})
	}
	return agent.Outcome{Threads: &agent.ThreadPage{Threads: threads}}
}

func operationKinds(calls []agent.Operation) []agent.OperationKind {
	kinds := make([]agent.OperationKind, 0, len(calls))
	for _, call := range calls {
		kinds = append(kinds, call.Kind())
	}
	return kinds
}

type scriptedStep struct {
	outcome agent.Outcome
	err     error
}

type scriptedRuntime struct {
	mu       sync.Mutex
	manifest agent.Manifest
	steps    map[agent.OperationKind][]scriptedStep
	calls    []agent.Operation
}

func newScriptedRuntime() *scriptedRuntime {
	return &scriptedRuntime{
		manifest: agent.Manifest{BackendID: "dsh", Operations: map[agent.OperationKind]bool{
			agent.OperationListThreads:   true,
			agent.OperationReadThread:    true,
			agent.OperationStartThread:   true,
			agent.OperationStartTurn:     true,
			agent.OperationSteerTurn:     true,
			agent.OperationInterruptTurn: true,
		}},
		steps: map[agent.OperationKind][]scriptedStep{},
	}
}

func (r *scriptedRuntime) script(kind agent.OperationKind, steps ...scriptedStep) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.steps[kind] = append(r.steps[kind], steps...)
}

func (r *scriptedRuntime) Manifest() agent.Manifest {
	return r.manifest
}

func (r *scriptedRuntime) Execute(_ context.Context, operation agent.Operation) (agent.Outcome, error) {
	if operation == nil {
		return agent.Outcome{}, agent.ErrInvalid
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.manifest.Operations[operation.Kind()] {
		return agent.Outcome{}, agent.ErrUnsupported
	}
	r.calls = append(r.calls, operation)
	steps := r.steps[operation.Kind()]
	if len(steps) == 0 {
		return agent.Outcome{}, nil
	}
	step := steps[0]
	r.steps[operation.Kind()] = steps[1:]
	return step.outcome, step.err
}

func (r *scriptedRuntime) Calls() []agent.Operation {
	r.mu.Lock()
	defer r.mu.Unlock()
	calls := make([]agent.Operation, len(r.calls))
	copy(calls, r.calls)
	return calls
}

func (r *scriptedRuntime) count(kind agent.OperationKind) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	total := 0
	for _, call := range r.calls {
		if call.Kind() == kind {
			total++
		}
	}
	return total
}

func runFixture(t *testing.T) (Coordinator, *scriptedRuntime) {
	t.Helper()
	cache, err := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	runtime := newScriptedRuntime()
	coordinator := Coordinator{
		Cache: cache, Routes: routes, Runtime: runtime, HostID: "host-1",
		ResolveWorkspace: func(deviceID, workspaceID string) (workspace.Candidate, bool) {
			return candidate("fingerprint-1"), true
		},
		InspectWorkspace: func(cwd string) (workspace.Candidate, error) { return candidate("fingerprint-1"), nil },
		Emit:             func(context.Context, string, string, string, json.RawMessage) (string, error) { return "event-1", nil },
	}
	return coordinator, runtime
}
