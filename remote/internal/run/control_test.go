package run

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"

	"github.com/harnessapk/remote/internal/commandcache"
)

type controlApp struct {
	calls   int
	methods []string
	params  []any
	result  json.RawMessage
	err     error
}

func (a *controlApp) Call(_ context.Context, method string, params any) (json.RawMessage, error) {
	a.calls++
	a.methods = append(a.methods, method)
	a.params = append(a.params, params)
	result := a.result
	if len(result) == 0 {
		result = json.RawMessage(`{}`)
	}
	return result, a.err
}

func TestDuplicateSteerCallsAppServerAndEmitsOnce(t *testing.T) {
	coordinator, app, emitted := controlFixture(t)
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
	if app.calls != 1 || *emitted != 1 || app.methods[0] != "turn/steer" {
		t.Fatalf("calls=%d emitted=%d methods=%v", app.calls, *emitted, app.methods)
	}
}

func TestControlMigratesLegacyCacheIdentityOnlyForRouteBackend(t *testing.T) {
	coordinator, app, emitted := controlFixture(t)
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
	if app.calls != 0 || *emitted != 0 {
		t.Fatalf("cached control replayed side effects: calls=%d emitted=%d", app.calls, *emitted)
	}
	otherBackend := command
	otherBackend.BackendID = "codex"
	if err := coordinator.Execute(context.Background(), otherBackend); err == nil {
		t.Fatal("migrated control cache was reusable by another backend")
	}
}

func TestControlDoesNotMigrateLegacyCacheToWrongBackend(t *testing.T) {
	coordinator, _, _ := controlFixture(t)
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
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatalf("route backend could not migrate legacy control: %v", err)
	}
}

func TestSteerUsesRefreshedDispatchTurnWithoutChangingStableIdentity(t *testing.T) {
	coordinator, app, _ := controlFixture(t)
	if err := coordinator.Routes.AdvanceTurn("run-1", "thread-1", "turn-1", "turn-2"); err != nil {
		t.Fatal(err)
	}
	app.result = json.RawMessage(`{"turn":{"id":"turn-3"}}`)
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
	params := app.params[0].(map[string]any)
	if params["expectedTurnId"] != "turn-2" {
		t.Fatalf("dispatch expected turn=%#v", params["expectedTurnId"])
	}
	route, _ := coordinator.Routes.ByRun("run-1")
	if route.TurnID != "turn-3" {
		t.Fatalf("route turn=%q", route.TurnID)
	}
}

func TestSteerAdvancesRouteToReturnedTurn(t *testing.T) {
	coordinator, app, _ := controlFixture(t)
	app.result = json.RawMessage(`{"turn":{"id":"turn-2"}}`)
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
	coordinator, app, _ := controlFixture(t)
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-wrong-backend", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "codex", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); err == nil {
		t.Fatal("cross-backend control was accepted")
	}
	if app.calls != 0 {
		t.Fatalf("cross-backend control reached appserver: %d", app.calls)
	}
}

func TestInterruptEmitsAcceptedWithoutCompletingRun(t *testing.T) {
	coordinator, app, emitted := controlFixture(t)
	command := ControlCommand{
		Type: "run.interrupt", CommandID: "command-2", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1",
	}
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	if app.calls != 1 || *emitted != 1 || app.methods[0] != "turn/interrupt" {
		t.Fatalf("calls=%d emitted=%d methods=%v", app.calls, *emitted, app.methods)
	}
}

func TestUnknownSteerReconcilesOnlyAfterAuthoritativeNextTurnAppears(t *testing.T) {
	coordinator, app, emitted := controlFixture(t)
	app.err = errors.New("connection lost after write")
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-reconcile", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	app.err = nil
	app.result = json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`)

	result, err := coordinator.ReconcileUnknown(context.Background(), command.CommandID)
	if err != nil || !result.Resolved || result.TurnID != "turn-2" || result.ThreadID != "thread-1" {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	if app.methods[len(app.methods)-1] != "thread/read" {
		t.Fatalf("methods=%v", app.methods)
	}
	params := app.params[len(app.params)-1].(map[string]any)
	if params["threadId"] != "thread-1" || params["includeTurns"] != true {
		t.Fatalf("thread/read params=%#v", params)
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
	coordinator, app, emitted := controlFixture(t)
	app.err = errors.New("connection lost after write")
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
	app.err = nil
	app.result = json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`)

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
	coordinator, app, emitted := controlFixture(t)
	app.err = errors.New("connection lost after write")
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-still-unknown", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), command); !errors.Is(err, ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	app.err = nil
	app.result = json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"}]}}`)

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
	coordinator, app, emitted := controlFixture(t)
	app.err = errors.New("connection lost after write")
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
	if app.calls != 1 || *emitted != 0 {
		t.Fatalf("calls=%d emitted=%d", app.calls, *emitted)
	}
}

func controlFixture(t *testing.T) (ControlCoordinator, *controlApp, *int) {
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
	app := &controlApp{result: json.RawMessage(`{"turn":{"id":"turn-2"}}`)}
	emitted := 0
	coordinator := ControlCoordinator{
		Cache: cache, Routes: routes, App: app,
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
	return coordinator, app, &emitted
}
