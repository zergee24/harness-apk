package run

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"reflect"
	"syscall"
	"testing"

	"github.com/harnessapk/remote/internal/agent"
	"github.com/harnessapk/remote/internal/commandcache"
)

func TestDuplicateSteerSendsTypedOperationAndEmitsOnce(t *testing.T) {
	coordinator, runtime, emitted := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, steerOutcome("turn-2"))
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-1", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	if runtime.count(agent.OperationSteerTurn) != 1 || *emitted != 1 {
		t.Fatalf("steer calls=%d emitted=%d", runtime.count(agent.OperationSteerTurn), *emitted)
	}
	steer := runtime.Calls()[0].(agent.SteerTurn)
	if steer.ThreadID != "thread-1" || steer.ExpectedTurnID != "turn-1" || steer.Text != "补充测试" {
		t.Fatalf("SteerTurn = %#v", steer)
	}
}

func TestControlMigratesLegacyCacheIdentityOnlyForRouteBackend(t *testing.T) {
	coordinator, runtime, emitted := controlFixture(t)
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-legacy", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if _, execute, err := coordinator.Cache.Begin(command.CommandID, command.Type, command.legacyPayloadSHA256()); err != nil || !execute {
		t.Fatalf("legacy begin execute=%v err=%v", execute, err)
	}
	if _, err := coordinator.Cache.Complete(command.CommandID, "event-legacy", json.RawMessage(`{"runId":"run-1","eventId":"event-legacy"}`)); err != nil {
		t.Fatal(err)
	}
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatalf("legacy replay: %v", err)
	}
	if len(runtime.Calls()) != 0 || *emitted != 0 {
		t.Fatalf("cached control replayed side effects: calls=%d emitted=%d", len(runtime.Calls()), *emitted)
	}
	otherBackend := command
	otherBackend.BackendID = "codex"
	if err := coordinator.Execute(context.Background(), otherBackend); err == nil {
		t.Fatal("migrated control cache was reusable by another backend")
	}
}

func TestControlDoesNotMigrateLegacyCacheToWrongBackend(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-legacy-wrong", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if _, execute, err := coordinator.Cache.Begin(command.CommandID, command.Type, command.legacyPayloadSHA256()); err != nil || !execute {
		t.Fatalf("legacy begin execute=%v err=%v", execute, err)
	}
	if _, err := coordinator.Cache.Complete(command.CommandID, "event-legacy", json.RawMessage(`{"runId":"run-1","eventId":"event-legacy"}`)); err != nil {
		t.Fatal(err)
	}
	wrong := command
	wrong.BackendID = "codex"
	if err := coordinator.Execute(context.Background(), wrong); err == nil {
		t.Fatal("wrong backend adopted legacy control cache")
	}
	runtime.script(agent.OperationSteerTurn, steerOutcome("turn-2"))
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatalf("route backend could not migrate legacy control: %v", err)
	}
}

func TestSteerUsesRefreshedDispatchTurnWithoutChangingStableIdentity(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	if err := coordinator.Routes.AdvanceTurn("run-1", "thread-1", "turn-1", "turn-2"); err != nil {
		t.Fatal(err)
	}
	runtime.script(agent.OperationSteerTurn, scriptedStep{outcome: agent.Outcome{StartedTurn: &agent.TurnRef{ID: "turn-3"}}})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-refresh", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", DispatchTurnID: "turn-2", Text: "补充测试",
	}
	stableHash := command.PayloadSHA256()
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	if command.PayloadSHA256() != stableHash {
		t.Fatal("dispatch turn changed stable command identity")
	}
	steer := runtime.Calls()[0].(agent.SteerTurn)
	if steer.ExpectedTurnID != "turn-2" {
		t.Fatalf("dispatch expected turn=%#v", steer.ExpectedTurnID)
	}
	route, _ := coordinator.Routes.ByRun("run-1")
	if route.TurnID != "turn-3" {
		t.Fatalf("route turn=%q", route.TurnID)
	}
}

func TestSteerAdvancesRouteToReturnedTurn(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, steerOutcome("turn-2"))
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-advance", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	route, _ := coordinator.Routes.ByRun("run-1")
	if route.TurnID != "turn-2" {
		t.Fatalf("route turn=%q", route.TurnID)
	}
}

func TestControlRejectsBackendDifferentFromRouteOwner(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-wrong-backend", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "codex", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); err == nil {
		t.Fatal("cross-backend control was accepted")
	}
	if len(runtime.Calls()) != 0 {
		t.Fatalf("cross-backend control reached runtime: %#v", runtime.Calls())
	}
}

func TestInterruptSendsTypedOperationWithoutCompletingRun(t *testing.T) {
	coordinator, runtime, emitted := controlFixture(t)
	eventTypes := []string{}
	coordinator.EmitStable = func(_ context.Context, _, _, _, eventType string, _ json.RawMessage) (string, error) {
		eventTypes = append(eventTypes, eventType)
		*emitted++
		return "event-interrupt", nil
	}
	command := ControlCommand{
		Type: "run.interrupt", CommandID: "command-2", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1",
	}
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	if runtime.count(agent.OperationInterruptTurn) != 1 || *emitted != 1 {
		t.Fatalf("interrupt calls=%d emitted=%d", runtime.count(agent.OperationInterruptTurn), *emitted)
	}
	interrupt := runtime.Calls()[0].(agent.InterruptTurn)
	if interrupt.ThreadID != "thread-1" || interrupt.TurnID != "turn-1" {
		t.Fatalf("InterruptTurn = %#v", interrupt)
	}
	if !reflect.DeepEqual(eventTypes, []string{"run.interrupt.accepted"}) {
		t.Fatalf("event types = %v", eventTypes)
	}
	route, _ := coordinator.Routes.ByRun("run-1")
	if route.TurnID != "turn-1" {
		t.Fatalf("interrupt completed the run route: %#v", route)
	}
}

func TestUnsupportedSteerFailsDeterministicallyWithoutUnknown(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	runtime.script(agent.OperationSteerTurn,
		scriptedStep{err: fmt.Errorf("%w: backend has no steer", agent.ErrUnsupported)})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-unsupported", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	err := coordinator.Execute(context.Background(), command)
	if err == nil || errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("error = %v, want deterministic failure", err)
	}
	if !errors.Is(err, agent.ErrUnsupported) {
		t.Fatalf("error = %v, want ErrUnsupported cause", err)
	}
	if executeErr := coordinator.Execute(context.Background(), command); executeErr == nil {
		t.Fatal("failed control command was replayed as success")
	}
	if runtime.count(agent.OperationSteerTurn) != 1 {
		t.Fatalf("unsupported steer was retried: %d", runtime.count(agent.OperationSteerTurn))
	}
	record, ok := coordinator.Cache.Lookup(command.CommandID)
	if !ok || record.Status != commandcache.StatusFailed {
		t.Fatalf("record = %#v ok=%v", record, ok)
	}
}

func TestProviderRejectionFailsDeterministically(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	runtime.script(agent.OperationSteerTurn,
		scriptedStep{err: errors.New(`app-server error: {"code":-32000,"message":"provider rejected"}`)})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-rejected", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	err := coordinator.Execute(context.Background(), command)
	if err == nil || errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("error = %v, want deterministic failure", err)
	}
	record, ok := coordinator.Cache.Lookup(command.CommandID)
	if !ok || record.Status != commandcache.StatusFailed {
		t.Fatalf("record = %#v ok=%v", record, ok)
	}
}

func TestSteerMissingTurnIDMarksOutcomeUnknown(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, scriptedStep{outcome: agent.Outcome{}})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-missing-turn", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	record, ok := coordinator.Cache.Lookup(command.CommandID)
	if !ok || record.Status != commandcache.StatusUnknown {
		t.Fatalf("record = %#v ok=%v", record, ok)
	}
}

func TestUnknownSteerReconcilesOnlyAfterAuthoritativeNextTurnAppears(t *testing.T) {
	coordinator, runtime, emitted := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, scriptedStep{err: fmt.Errorf("turn steer: %w", io.EOF)})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-reconcile", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	runtime.script(agent.OperationReadThread, scriptedStep{outcome: readThreadOutcome("thread-1", "turn-1", "turn-2")})

	result, err := coordinator.ReconcileUnknown(context.Background(), command.CommandID)
	if err != nil || !result.Resolved || result.TurnID != "turn-2" || result.ThreadID != "thread-1" {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	calls := runtime.Calls()
	read, ok := calls[len(calls)-1].(agent.ReadThread)
	if !ok || read.ThreadID != "thread-1" || !read.IncludeTurns {
		t.Fatalf("ReadThread = %#v", calls[len(calls)-1])
	}
	route, _ := coordinator.Routes.ByRunBackend("dsh", "run-1")
	if route.TurnID != "turn-2" || *emitted != 1 {
		t.Fatalf("route=%#v emitted=%d", route, *emitted)
	}
	record, _ := coordinator.Cache.Lookup(command.CommandID)
	if record.Status != commandcache.StatusSucceeded {
		t.Fatalf("record=%#v", record)
	}
}

func TestUnknownSteerReconciliationReusesPreviouslyAttachedEvent(t *testing.T) {
	coordinator, runtime, emitted := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, scriptedStep{err: fmt.Errorf("turn steer: %w", io.EOF)})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-attached", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	attachedResult := json.RawMessage(`{"runId":"run-1","eventId":"event-attached","threadId":"thread-1","turnId":"turn-2"}`)
	if _, err := coordinator.Cache.AttachResult(command.CommandID, "event-attached", attachedResult); err != nil {
		t.Fatal(err)
	}
	runtime.script(agent.OperationReadThread, scriptedStep{outcome: readThreadOutcome("thread-1", "turn-1", "turn-2")})

	result, err := coordinator.ReconcileUnknown(context.Background(), command.CommandID)
	if err != nil || !result.Resolved || result.TurnID != "turn-2" {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	if *emitted != 0 {
		t.Fatalf("reconciliation duplicated attached logical event: emitted=%d", *emitted)
	}
	record, _ := coordinator.Cache.Lookup(command.CommandID)
	if record.Status != commandcache.StatusSucceeded || record.ResultEventID != "event-attached" {
		t.Fatalf("record=%#v", record)
	}
}

func TestUnknownSteerRemainsHeldWhenAuthoritativeReadHasNoNextTurn(t *testing.T) {
	coordinator, runtime, emitted := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, scriptedStep{err: fmt.Errorf("turn steer: %w", io.EOF)})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-still-unknown", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	runtime.script(agent.OperationReadThread, scriptedStep{outcome: readThreadOutcome("thread-1", "turn-1")})

	result, err := coordinator.ReconcileUnknown(context.Background(), command.CommandID)
	if err != nil || result.Resolved {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	route, _ := coordinator.Routes.ByRunBackend("dsh", "run-1")
	if route.TurnID != "turn-1" || *emitted != 0 {
		t.Fatalf("route=%#v emitted=%d", route, *emitted)
	}
	record, _ := coordinator.Cache.Lookup(command.CommandID)
	if record.Status != commandcache.StatusUnknown {
		t.Fatalf("record=%#v", record)
	}
}

func TestUnknownSteerOutcomeIsNotReplayed(t *testing.T) {
	coordinator, runtime, emitted := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, scriptedStep{err: fmt.Errorf("turn steer: %w", agent.ErrUnavailable)})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-unknown", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("first error=%v", err)
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("second error=%v", err)
	}
	if runtime.count(agent.OperationSteerTurn) != 1 || *emitted != 0 {
		t.Fatalf("steer calls=%d emitted=%d", runtime.count(agent.OperationSteerTurn), *emitted)
	}
}

func TestControlErrorClassification(t *testing.T) {
	tests := []struct {
		name        string
		err         error
		wantUnknown bool
	}{
		{name: "unavailable", err: fmt.Errorf("call: %w", agent.ErrUnavailable), wantUnknown: true},
		{name: "outcome unknown", err: fmt.Errorf("gate: %w", agent.ErrOutcomeUnknown), wantUnknown: true},
		{name: "context canceled", err: fmt.Errorf("ctx: %w", context.Canceled), wantUnknown: true},
		{name: "context deadline", err: fmt.Errorf("ctx: %w", context.DeadlineExceeded), wantUnknown: true},
		{name: "eof", err: fmt.Errorf("read: %w", io.EOF), wantUnknown: true},
		{name: "closed pipe", err: fmt.Errorf("write: %w", io.ErrClosedPipe), wantUnknown: true},
		{name: "epipe", err: fmt.Errorf("write: %w", syscall.EPIPE), wantUnknown: true},
		{name: "unsupported", err: fmt.Errorf("gate: %w", agent.ErrUnsupported)},
		{name: "invalid", err: fmt.Errorf("gate: %w", agent.ErrInvalid)},
		{name: "protocol", err: fmt.Errorf("decode: %w", agent.ErrProtocol)},
		{name: "provider rejection", err: errors.New(`app-server error: {"code":-32000,"message":"nope"}`)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			coordinator, runtime, _ := controlFixture(t)
			runtime.script(agent.OperationSteerTurn, scriptedStep{err: tt.err})
			command := ControlCommand{
				Type: "run.steer", CommandID: "command-classify", RunID: "run-1", DeviceID: "phone-1",
				BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
			}
			err := coordinator.Execute(context.Background(), command)
			if tt.wantUnknown {
				if !errors.Is(err, ErrControlOutcomeUnknown) {
					t.Fatalf("error = %v, want unknown", err)
				}
				return
			}
			if err == nil || errors.Is(err, ErrControlOutcomeUnknown) {
				t.Fatalf("error = %v, want deterministic failure", err)
			}
			record, ok := coordinator.Cache.Lookup(command.CommandID)
			if !ok || record.Status != commandcache.StatusFailed {
				t.Fatalf("record = %#v ok=%v", record, ok)
			}
		})
	}
}

func TestReconcileUnknownRejectsMissingThreadOutcome(t *testing.T) {
	coordinator, runtime, _ := controlFixture(t)
	runtime.script(agent.OperationSteerTurn, scriptedStep{err: fmt.Errorf("turn steer: %w", io.EOF)})
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-missing-thread", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	runtime.script(agent.OperationReadThread, scriptedStep{outcome: agent.Outcome{}})

	_, err := coordinator.ReconcileUnknown(context.Background(), command.CommandID)
	if !errors.Is(err, agent.ErrProtocol) {
		t.Fatalf("reconcile error = %v, want ErrProtocol", err)
	}
}

func steerOutcome(turnID string) scriptedStep {
	return scriptedStep{outcome: agent.Outcome{StartedTurn: &agent.TurnRef{ID: turnID}}}
}

func readThreadOutcome(threadID string, turnIDs ...string) agent.Outcome {
	turns := make([]agent.TurnSnapshot, 0, len(turnIDs))
	for _, id := range turnIDs {
		turns = append(turns, agent.TurnSnapshot{ID: id, Status: "completed"})
	}
	return agent.Outcome{Thread: &agent.ThreadSnapshot{ID: threadID, CWD: "/workspace", Turns: turns}}
}

func controlFixture(t *testing.T) (ControlCoordinator, *scriptedRuntime, *int) {
	t.Helper()
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	runtime := newScriptedRuntime()
	emitted := 0
	coordinator := ControlCoordinator{
		Cache: cache, Routes: routes, Runtime: runtime,
		Emit: func(_ context.Context, _, _, _ string, _ json.RawMessage) (string, error) {
			emitted++
			return "event-1", nil
		},
		EmitStable: func(_ context.Context, eventID, _, _, _ string, _ json.RawMessage) (string, error) {
			if eventID != "event-attached" {
				emitted++
			}
			return eventID, nil
		},
	}
	return coordinator, runtime, &emitted
}
