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
	err     error
}

func (a *controlApp) Call(_ context.Context, method string, _ any) (json.RawMessage, error) {
	a.calls++
	a.methods = append(a.methods, method)
	return json.RawMessage(`{}`), a.err
}

func TestDuplicateSteerCallsAppServerAndEmitsOnce(t *testing.T) {
	coordinator, app, emitted := controlFixture(t)
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-1", RunID: "run-1", DeviceID: "phone-1",
		ExpectedTurnID: "turn-1", Text: "补充测试",
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

func TestInterruptEmitsAcceptedWithoutCompletingRun(t *testing.T) {
	coordinator, app, emitted := controlFixture(t)
	command := ControlCommand{
		Type: "run.interrupt", CommandID: "command-2", RunID: "run-1", DeviceID: "phone-1",
		ExpectedTurnID: "turn-1",
	}
	if err := coordinator.Execute(context.Background(), command); err != nil {
		t.Fatal(err)
	}
	if app.calls != 1 || *emitted != 1 || app.methods[0] != "turn/interrupt" {
		t.Fatalf("calls=%d emitted=%d methods=%v", app.calls, *emitted, app.methods)
	}
}

func TestUnknownSteerOutcomeIsNotReplayed(t *testing.T) {
	coordinator, app, emitted := controlFixture(t)
	app.err = errors.New("connection lost after write")
	command := ControlCommand{
		Type: "run.steer", CommandID: "command-unknown", RunID: "run-1", DeviceID: "phone-1",
		ExpectedTurnID: "turn-1", Text: "补充测试",
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
		RunID: "run-1", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	app := &controlApp{}
	emitted := 0
	coordinator := ControlCoordinator{
		Cache: cache, Routes: routes, App: app,
		Emit: func(_ context.Context, _, _, _ string, _ json.RawMessage) (string, error) {
			emitted++
			return "event-1", nil
		},
	}
	return coordinator, app, &emitted
}
