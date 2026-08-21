package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	appserverrpc "github.com/harnessapk/remote/internal/appserver"
	"github.com/harnessapk/remote/internal/backend"
	"github.com/harnessapk/remote/internal/commandcache"
	"github.com/harnessapk/remote/internal/completion"
	"github.com/harnessapk/remote/internal/journal"
	"github.com/harnessapk/remote/internal/protocol"
	runstate "github.com/harnessapk/remote/internal/run"
	bridgestate "github.com/harnessapk/remote/internal/state"
	"github.com/harnessapk/remote/internal/workspace"
)

func TestCommandResponseEventCarriesCanonicalBackend(t *testing.T) {
	legacy := commandResponseEvent(protocol.Command{}, protocol.Event{Type: "rpc.response"})
	if legacy.BackendID != "codex" {
		t.Fatalf("legacy response backend=%q", legacy.BackendID)
	}
	dsh := commandResponseEvent(protocol.Command{BackendID: "dsh"}, protocol.Event{Type: "rpc.response"})
	if dsh.BackendID != "dsh" {
		t.Fatalf("dsh response backend=%q", dsh.BackendID)
	}
}

func TestExplicitLogicalEventBackendSurvivesMissingOrConflictingRoute(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes}
	if got := b.logicalEventBackend("run-1", "dsh"); got != "dsh" {
		t.Fatalf("missing-route backend=%q", got)
	}
	if err := routes.Put(runstate.Route{RunID: "run-1", BackendID: "codex", HostID: "host-1", DeviceID: "phone-1"}); err != nil {
		t.Fatal(err)
	}
	if got := b.logicalEventBackend("run-1", "dsh"); got != "dsh" {
		t.Fatalf("conflicting-route backend=%q", got)
	}
}

func TestDuplicateRunStartReturnsCachedResultWithoutCallingAppServer(t *testing.T) {
	cache, err := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	appServerCalls := 0
	call := func() (string, json.RawMessage, error) {
		appServerCalls++
		return "event-result-1", json.RawMessage(`{"runId":"run-1"}`), nil
	}
	first, err := executeCachedCommand(cache, "command-1", "run.start", "payload-hash", call)
	if err != nil {
		t.Fatal(err)
	}
	second, err := executeCachedCommand(cache, "command-1", "run.start", "payload-hash", call)
	if err != nil {
		t.Fatal(err)
	}
	if appServerCalls != 1 || first.ResultEventID != "event-result-1" || second.ResultEventID != first.ResultEventID {
		t.Fatalf("calls=%d first=%#v second=%#v", appServerCalls, first, second)
	}
}

func TestControlRunReplaysSucceededCommandAfterRouteAdvanced(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-2",
	}); err != nil {
		t.Fatal(err)
	}
	command := protocol.Command{
		Type: "run.steer", BackendID: "dsh", CommandID: "command-1", RequestID: "request-1",
		RunID: "run-1", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	identity := runstate.ControlCommand{
		Type: command.Type, CommandID: command.CommandID, RunID: command.RunID, DeviceID: "phone-1",
		BackendID: command.BackendID, ExpectedTurnID: command.ExpectedTurnID, Text: command.Text,
	}
	if _, execute, err := cache.Begin(command.CommandID, command.Type, identity.PayloadSHA256()); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	if _, err := cache.Complete(command.CommandID, "event-1", json.RawMessage(`{"runId":"run-1","eventId":"event-1"}`)); err != nil {
		t.Fatal(err)
	}
	bd := backend.NewFake("dsh")
	b := &bridge{commandCache: cache, routes: routes}
	if err := b.controlRun(context.Background(), "phone-1", command, bd); err != nil {
		t.Fatalf("cached replay after route advance: %v", err)
	}
	waitForRecoveryWorker(t, b)
	if calls := bd.Calls(); len(calls) != 0 {
		t.Fatalf("cached replay called backend: %#v", calls)
	}
}

func TestUnknownControlTransitionReleasesOnlyAfterAuthoritativeNextTurn(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	app := backend.NewFake("dsh").OnScript("turn/steer", func(string, any) (json.RawMessage, error) {
		return nil, io.EOF
	})
	coordinator := runstate.ControlCoordinator{
		Cache: cache, Routes: routes, Runtime: backend.NewAppServerAdapter(app),
		Emit: func(context.Context, string, string, string, json.RawMessage) (string, error) { return "event-1", nil },
	}
	control := runstate.ControlCommand{
		Type: "run.steer", CommandID: "command-1", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), control); !errors.Is(err, runstate.ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	released := make(chan struct{})
	b := &bridge{
		commandCache: cache, routes: routes, backends: map[string]backend.Backend{"dsh": app},
		controlEventEmitter: func(context.Context, string, string, string, string, json.RawMessage) (string, error) {
			return "event-2", nil
		},
	}
	command := protocol.Command{
		Type: "run.steer", CommandID: "command-1", BackendID: "dsh", RunID: "run-1",
		ThreadID: "thread-1", ExpectedTurnID: "turn-1",
	}
	b.beginTurnTransition(command, func() { close(released) })
	app.OnScript("thread/read", func(string, any) (json.RawMessage, error) {
		return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"}]}}`), nil
	})
	if err := b.reconcileUnknownTurnTransitions(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-released:
		t.Fatal("unresolved transition released without authoritative next turn")
	default:
	}
	app.OnScript("thread/read", func(string, any) (json.RawMessage, error) {
		return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`), nil
	})
	if err := b.reconcileUnknownTurnTransitions(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-released:
	case <-time.After(time.Second):
		t.Fatal("resolved transition did not release FIFO gate")
	}
	if route, _ := routes.ByRunBackend("dsh", "run-1"); route.TurnID != "turn-2" {
		t.Fatalf("route=%#v", route)
	}
}

func TestNewRunSteerDoesNotOvertakeDurableUnknownPredecessor(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	contextJSON := json.RawMessage(`{"type":"run.steer","runId":"run-1","deviceId":"phone-1","backendId":"dsh","threadId":"thread-1","expectedTurnId":"turn-1","text":"old"}`)
	if _, execute, err := cache.BeginWithContext("command-old", "run.steer", "hash-old", contextJSON); err != nil || !execute {
		t.Fatalf("begin old execute=%v err=%v", execute, err)
	}
	if _, err := cache.MarkUnknown("command-old", errors.New("lost")); err != nil {
		t.Fatal(err)
	}
	readCalled := make(chan struct{}, 1)
	releaseRead := make(chan struct{})
	app := backend.NewFake("dsh").OnScript("thread/read", func(string, any) (json.RawMessage, error) {
		readCalled <- struct{}{}
		<-releaseRead
		return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"}]}}`), nil
	})
	unknown := make(chan string, 1)
	b := &bridge{
		commandCache: cache, routes: routes, backends: map[string]backend.Backend{"dsh": app},
		controlEventEmitter: func(_ context.Context, _, _, _, eventType string, payload json.RawMessage) (string, error) {
			if eventType == "run.control.unknown" {
				var value struct {
					CommandID string `json:"commandId"`
				}
				_ = json.Unmarshal(payload, &value)
				unknown <- value.CommandID
			}
			return "event-unknown", nil
		},
	}
	command := protocol.Command{
		Type: "run.steer", CommandID: "command-new", RequestID: "request-new", BackendID: "dsh",
		RunID: "run-1", ExpectedTurnID: "turn-1", Text: "must wait",
	}

	if err := b.controlRun(context.Background(), "phone-1", command, app); err != nil {
		t.Fatalf("controlRun error=%v", err)
	}
	select {
	case <-readCalled:
	case <-time.After(time.Second):
		t.Fatal("unknown predecessor reconciliation did not start")
	}
	close(releaseRead)
	deadline := time.Now().Add(time.Second)
	for {
		b.recoveryWorkMu.Lock()
		running := b.recoveryWorkerRunning
		b.recoveryWorkMu.Unlock()
		if !running || time.Now().After(deadline) {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if calls := app.Calls(); len(calls) != 1 || calls[0].Method != "thread/read" {
		t.Fatalf("new steer overtook unknown predecessor: %#v", calls)
	}
	select {
	case commandID := <-unknown:
		if commandID != "command-new" {
			t.Fatalf("unknown command=%q", commandID)
		}
	case <-time.After(time.Second):
		t.Fatal("unresolved successor did not receive a terminal unknown event")
	}
	if _, ok := cache.Lookup("command-new"); ok {
		t.Fatal("blocked successor was persisted as if dispatched")
	}
}

func TestNewRunSteerUsesReconciledTurnAfterDurablePredecessorResolves(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	contextJSON := json.RawMessage(`{"type":"run.steer","runId":"run-1","deviceId":"phone-1","backendId":"dsh","threadId":"thread-1","expectedTurnId":"turn-1","text":"old"}`)
	if _, execute, err := cache.BeginWithContext("command-old", "run.steer", "hash-old", contextJSON); err != nil || !execute {
		t.Fatalf("begin old execute=%v err=%v", execute, err)
	}
	if _, err := cache.MarkUnknown("command-old", errors.New("lost")); err != nil {
		t.Fatal(err)
	}
	steered := make(chan string, 1)
	app := backend.NewFake("dsh").
		OnScript("thread/read", func(string, any) (json.RawMessage, error) {
			return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`), nil
		}).
		OnScript("turn/steer", func(_ string, params any) (json.RawMessage, error) {
			raw, _ := json.Marshal(params)
			var input struct {
				ExpectedTurnID string `json:"expectedTurnId"`
			}
			_ = json.Unmarshal(raw, &input)
			steered <- input.ExpectedTurnID
			return json.RawMessage(`{"turnId":"turn-3"}`), nil
		})
	b := &bridge{
		commandCache: cache, routes: routes, backends: map[string]backend.Backend{"dsh": app},
		controlEventEmitter: func(context.Context, string, string, string, string, json.RawMessage) (string, error) {
			return "event-1", nil
		},
	}
	command := protocol.Command{
		Type: "run.steer", CommandID: "command-new", RequestID: "request-new", BackendID: "dsh",
		RunID: "run-1", ExpectedTurnID: "turn-1", Text: "next",
	}

	if err := b.controlRun(context.Background(), "phone-1", command, app); err != nil {
		t.Fatal(err)
	}
	select {
	case turnID := <-steered:
		if turnID != "turn-2" {
			t.Fatalf("successor dispatched against %q, want reconciled turn-2", turnID)
		}
	case <-time.After(time.Second):
		t.Fatal("successor was not dispatched after predecessor reconciliation")
	}
	deadline := time.Now().Add(time.Second)
	for {
		record, ok := cache.Lookup("command-new")
		route, routeOK := routes.ByRunBackend("dsh", "run-1")
		if ok && record.Status == commandcache.StatusSucceeded && routeOK && route.TurnID == "turn-3" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("successor did not finish durably: record=%#v route=%#v", record, route)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestReconciledSuccessorAcceptsRouteAdvancedAgainBeforeAdmission(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-3",
	}); err != nil {
		t.Fatal(err)
	}
	steered := make(chan string, 1)
	app := backend.NewFake("dsh").OnScript("turn/steer", func(_ string, params any) (json.RawMessage, error) {
		raw, _ := json.Marshal(params)
		var input struct {
			ExpectedTurnID string `json:"expectedTurnId"`
		}
		_ = json.Unmarshal(raw, &input)
		steered <- input.ExpectedTurnID
		return json.RawMessage(`{"turnId":"turn-4"}`), nil
	})
	b := &bridge{
		commandCache: cache, routes: routes,
		controlEventEmitter: func(context.Context, string, string, string, string, json.RawMessage) (string, error) {
			return "event-1", nil
		},
	}
	command := protocol.Command{
		Type: "run.steer", CommandID: "command-new", RequestID: "request-new", BackendID: "dsh",
		RunID: "run-1", ExpectedTurnID: "turn-1", Text: "next",
	}

	if err := b.controlRunWithReconciledTurn(context.Background(), "phone-1", command, app, "turn-2"); err != nil {
		t.Fatalf("admission rejected route that advanced after reconciliation: %v", err)
	}
	select {
	case turnID := <-steered:
		if turnID != "turn-3" {
			t.Fatalf("successor dispatched against %q, want latest turn-3", turnID)
		}
	case <-time.After(time.Second):
		t.Fatal("successor was not dispatched against latest route")
	}
	deadline := time.Now().Add(time.Second)
	for {
		record, ok := cache.Lookup("command-new")
		route, routeOK := routes.ByRunBackend("dsh", "run-1")
		if ok && record.Status == commandcache.StatusSucceeded && routeOK && route.TurnID == "turn-4" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("successor did not finish durably: record=%#v route=%#v", record, route)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestControlRunReservesThreadFIFOBeforeIngressReturns(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	contextJSON := json.RawMessage(`{"type":"run.steer","runId":"run-1","deviceId":"phone-1","backendId":"dsh","threadId":"thread-1","expectedTurnId":"turn-1","text":"old"}`)
	if _, execute, err := cache.BeginWithContext("command-old", "run.steer", "hash-old", contextJSON); err != nil || !execute {
		t.Fatalf("begin old execute=%v err=%v", execute, err)
	}
	if _, err := cache.MarkUnknown("command-old", errors.New("lost")); err != nil {
		t.Fatal(err)
	}
	releaseRead := make(chan struct{})
	app := backend.NewFake("dsh").OnScript("thread/read", func(string, any) (json.RawMessage, error) {
		<-releaseRead
		return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`), nil
	})
	b := &bridge{
		commandCache: cache, routes: routes, backends: map[string]backend.Backend{"dsh": app},
		controlEventEmitter: func(context.Context, string, string, string, string, json.RawMessage) (string, error) {
			return "event-1", nil
		},
	}
	command := protocol.Command{
		Type: "run.steer", CommandID: "command-new", RequestID: "request-new", BackendID: "dsh",
		RunID: "run-1", ExpectedTurnID: "turn-1", Text: "next",
	}

	if err := b.controlRun(context.Background(), "phone-1", command, app); err != nil {
		t.Fatal(err)
	}
	// The reconciliation is blocked inside the worker, so the FIFO gate must
	// already be reserved when ingress returns.
	if !b.hasQueuedTurnCall("dsh", "thread-1") {
		t.Fatal("later turn commands can overtake the run control before ingress returns")
	}
	close(releaseRead)
	waitForRecoveryWorker(t, b)
}

func TestRestartedBridgeReconcilesPersistedUnknownControlWithoutTransition(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	app := backend.NewFake("dsh").OnScript("turn/steer", func(string, any) (json.RawMessage, error) {
		return nil, io.EOF
	})
	coordinator := runstate.ControlCoordinator{
		Cache: cache, Routes: routes, Runtime: backend.NewAppServerAdapter(app),
		Emit: func(context.Context, string, string, string, json.RawMessage) (string, error) {
			return "event-before-restart", nil
		},
	}
	control := runstate.ControlCommand{
		Type: "run.steer", CommandID: "command-restart", RunID: "run-1", DeviceID: "phone-1",
		BackendID: "dsh", ExpectedTurnID: "turn-1", Text: "补充测试",
	}
	if err := coordinator.Execute(context.Background(), control); !errors.Is(err, runstate.ErrControlOutcomeUnknown) {
		t.Fatalf("execute error=%v", err)
	}
	app.OnScript("thread/read", func(string, any) (json.RawMessage, error) {
		return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`), nil
	})
	restarted := &bridge{
		commandCache: cache, routes: routes, backends: map[string]backend.Backend{"dsh": app},
		controlEventEmitter: func(context.Context, string, string, string, string, json.RawMessage) (string, error) {
			return "event-after-restart", nil
		},
	}
	if err := restarted.reconcileUnknownTurnTransitions(context.Background()); err != nil {
		t.Fatal(err)
	}
	record, _ := cache.Lookup("command-restart")
	if record.Status != commandcache.StatusSucceeded {
		t.Fatalf("record=%#v", record)
	}
	if route, _ := routes.ByRunBackend("dsh", "run-1"); route.TurnID != "turn-2" {
		t.Fatalf("route=%#v", route)
	}
}

func TestRunControlCannotBeOvertakenByLaterLegacyTurnSteer(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "legacy:thread-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	contextJSON := json.RawMessage(`{"type":"run.steer","runId":"run-1","deviceId":"phone-1","backendId":"dsh","threadId":"thread-1","expectedTurnId":"turn-1","text":"old"}`)
	if _, execute, err := cache.BeginWithContext("command-old", "run.steer", "hash-old", contextJSON); err != nil || !execute {
		t.Fatalf("begin old execute=%v err=%v", execute, err)
	}
	if _, err := cache.MarkUnknown("command-old", errors.New("lost")); err != nil {
		t.Fatal(err)
	}
	releaseRead := make(chan struct{})
	var callsMu sync.Mutex
	var calls []string
	app := backend.NewFake("dsh").
		OnScript("thread/read", func(string, any) (json.RawMessage, error) {
			<-releaseRead
			return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`), nil
		}).
		OnScript("turn/steer", func(method string, params any) (json.RawMessage, error) {
			raw, _ := json.Marshal(params)
			var input struct {
				ThreadID string `json:"threadId"`
				Input    []struct {
					Text string `json:"text"`
				} `json:"input"`
			}
			_ = json.Unmarshal(raw, &input)
			text := ""
			if len(input.Input) > 0 {
				text = input.Input[0].Text
			}
			callsMu.Lock()
			calls = append(calls, text)
			callsMu.Unlock()
			return json.RawMessage(`{"turnId":"turn-3"}`), nil
		})
	b := &bridge{
		commandCache: cache, routes: routes, backends: map[string]backend.Backend{"dsh": app},
		state: bridgeState{
			HostID: "host-1", Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{},
		},
		controlEventEmitter: func(context.Context, string, string, string, string, json.RawMessage) (string, error) {
			return "event-1", nil
		},
	}
	runCommand := protocol.Command{
		Type: "run.steer", CommandID: "command-new", RequestID: "request-new", BackendID: "dsh",
		RunID: "run-1", ExpectedTurnID: "turn-1", Text: "run-control",
	}
	if err := b.controlRun(context.Background(), "phone-1", runCommand, app); err != nil {
		t.Fatal(err)
	}
	legacyCommand := protocol.Command{
		Type: "turn.steer", BackendID: "dsh", ThreadID: "thread-1", ExpectedTurnID: "turn-1", Text: "legacy-turn",
	}
	if err := b.claimThread(legacyCommand, "phone-1"); err != nil {
		t.Fatal(err)
	}
	if err := b.requestTurnAppServer(context.Background(), "phone-1", legacyCommand, app, "turn/steer", map[string]any{
		"threadId": "thread-1", "expectedTurnId": "turn-1",
		"input": []map[string]string{{"type": "text", "text": "legacy-turn"}},
	}); err != nil {
		t.Fatal(err)
	}
	close(releaseRead)
	waitForRecoveryWorker(t, b)
	deadline := time.Now().Add(time.Second)
	for {
		callsMu.Lock()
		done := len(calls)
		callsMu.Unlock()
		if done >= 2 || time.Now().After(deadline) {
			break
		}
		time.Sleep(time.Millisecond)
	}
	callsMu.Lock()
	defer callsMu.Unlock()
	if len(calls) != 2 || calls[0] != "run-control" || calls[1] != "legacy-turn" {
		t.Fatalf("execution order=%#v", calls)
	}
}

func TestRunSteerStartedNotificationKeepsTransitionUntilCommandCacheResolves(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	released := make(chan struct{})
	b := &bridge{routes: routes}
	command := protocol.Command{
		Type: "run.steer", CommandID: "command-1", BackendID: "dsh", RunID: "run-1",
		ThreadID: "thread-1", ExpectedTurnID: "turn-1",
	}
	generation := b.beginTurnTransition(command, func() { close(released) })
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-2"}}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-released:
		t.Fatal("run.steer notification released FIFO before command cache resolution")
	default:
	}
	if _, ok := b.turnTransitionGeneration("dsh", "thread-1", generation); !ok {
		t.Fatal("run.steer transition retired before command cache resolution")
	}
	if route, _ := routes.ByRunBackend("dsh", "run-1"); route.TurnID != "turn-2" {
		t.Fatalf("route=%#v", route)
	}
}

func TestUnknownLegacyTurnTransitionReleasesAfterAuthoritativeNextTurn(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "legacy:thread-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1",
		ThreadID: "thread-1", TurnID: "turn-1",
	}); err != nil {
		t.Fatal(err)
	}
	app := backend.NewFake("dsh").OnScript("thread/read", func(string, any) (json.RawMessage, error) {
		return json.RawMessage(`{"thread":{"id":"thread-1","turns":[{"id":"turn-1"},{"id":"turn-2"}]}}`), nil
	})
	released := make(chan struct{})
	b := &bridge{routes: routes, backends: map[string]backend.Backend{"dsh": app}}
	command := protocol.Command{
		Type: "turn.steer", BackendID: "dsh", ThreadID: "thread-1", ExpectedTurnID: "turn-1",
	}
	b.beginTurnTransition(command, func() { close(released) })

	if err := b.reconcileUnknownTurnTransitions(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-released:
	case <-time.After(time.Second):
		t.Fatal("legacy resolved transition did not release FIFO gate")
	}
	if route, _ := routes.ByRunBackend("dsh", "legacy:thread-1"); route.TurnID != "turn-2" {
		t.Fatalf("route=%#v", route)
	}
}

func TestHostStatusAdvertisesLegacyCapsAndPerBackendList(t *testing.T) {
	codex := backend.NewFake("codex").SetCapabilities(backend.CodexCapabilities())
	dsh := backend.NewFake("dsh").SetCapabilities([]string{"run.lifecycle.v1", "workspace.candidates.v1"})
	b := &bridge{
		backends:     map[string]backend.Backend{"codex": codex, "dsh": dsh},
		backendOrder: []string{"codex", "dsh"},
	}
	var payload struct {
		SchemaVersion int                    `json:"schemaVersion"`
		Capabilities  []string               `json:"capabilities"`
		Backends      []protocol.BackendInfo `json:"backends"`
	}
	if err := json.Unmarshal(b.hostStatusPayload(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.SchemaVersion != 1 {
		t.Fatalf("schemaVersion=%d", payload.SchemaVersion)
	}
	// Legacy host-level capabilities stay the default backend's, so old
	// clients keep their M2/M3 behavior.
	if !reflect.DeepEqual(payload.Capabilities, backend.CodexCapabilities()) {
		t.Fatalf("capabilities=%#v", payload.Capabilities)
	}
	if len(payload.Backends) != 2 || payload.Backends[0].ID != "codex" || payload.Backends[1].ID != "dsh" {
		t.Fatalf("backends=%#v", payload.Backends)
	}
	if !reflect.DeepEqual(payload.Backends[1].Capabilities, []string{"run.lifecycle.v1", "workspace.candidates.v1"}) {
		t.Fatalf("dsh caps=%#v", payload.Backends[1].Capabilities)
	}
}

func TestHostStatusOmitsUnavailableBackends(t *testing.T) {
	codex := backend.NewFake("codex").SetCapabilities(backend.CodexCapabilities())
	b := &bridge{
		backends:     map[string]backend.Backend{"codex": codex},
		backendOrder: []string{"codex", "dsh"}, // dsh crashed
	}
	var payload protocol.HostStatusPayload
	if err := json.Unmarshal(b.hostStatusPayload(), &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Backends) != 1 || payload.Backends[0].ID != "codex" {
		t.Fatalf("backends=%#v", payload.Backends)
	}
}

func TestBackendRestartDoesNotDuplicateHostStatusEntry(t *testing.T) {
	b := &bridge{}
	b.registerBackend(backend.NewFake("dsh"))
	b.unregisterBackend("dsh")
	b.registerBackend(backend.NewFake("dsh"))

	var payload protocol.HostStatusPayload
	if err := json.Unmarshal(b.hostStatusPayload(), &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Backends) != 1 || payload.Backends[0].ID != "dsh" {
		t.Fatalf("backends after restart=%#v", payload.Backends)
	}
}

func TestBackendRegistrationRacePreservesConfiguredHostStatusOrder(t *testing.T) {
	b := &bridge{}
	b.initializeBackendOrder([]backend.Spec{{ID: "codex"}, {ID: "dsh"}})
	b.registerBackend(backend.NewFake("dsh"))
	b.registerBackend(backend.NewFake("codex"))

	var payload protocol.HostStatusPayload
	if err := json.Unmarshal(b.hostStatusPayload(), &payload); err != nil {
		t.Fatal(err)
	}
	ids := make([]string, 0, len(payload.Backends))
	for _, item := range payload.Backends {
		ids = append(ids, item.ID)
	}
	if !reflect.DeepEqual(ids, []string{"codex", "dsh"}) {
		t.Fatalf("backend order after reversed registration=%#v", ids)
	}
}

func TestBackendRestartPreservesEstablishedHostStatusOrder(t *testing.T) {
	b := &bridge{}
	b.registerBackend(backend.NewFake("codex"))
	b.registerBackend(backend.NewFake("dsh"))
	b.unregisterBackend("codex")
	b.registerBackend(backend.NewFake("codex"))

	var payload protocol.HostStatusPayload
	if err := json.Unmarshal(b.hostStatusPayload(), &payload); err != nil {
		t.Fatal(err)
	}
	ids := make([]string, 0, len(payload.Backends))
	for _, item := range payload.Backends {
		ids = append(ids, item.ID)
	}
	if !reflect.DeepEqual(ids, []string{"codex", "dsh"}) {
		t.Fatalf("backend order after restart=%#v", ids)
	}
}

func TestRuntimeStateSavePreservesPairingCreatedAfterBridgeStartup(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.json")
	initial := bridgeState{
		SchemaVersion: 2, RelayURL: "https://relay.example.com", HostID: "host-1", HostName: "Mac", HostToken: "token",
		Pending: map[string]string{}, DeviceSecrets: map[string]string{"phone-old": protocol.EncodeSecret(bytes.Repeat([]byte{1}, 32))},
		Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{},
	}
	if err := saveBridgeState(path, initial); err != nil {
		t.Fatal(err)
	}
	runningState, err := loadBridgeState(path)
	if err != nil {
		t.Fatal(err)
	}
	bridge := &bridge{state: runningState, path: path}
	external, err := loadBridgeState(path)
	if err != nil {
		t.Fatal(err)
	}
	external.Pending["ticket-new"] = protocol.EncodeSecret(bytes.Repeat([]byte{2}, 32))
	if err := saveBridgeState(path, external); err != nil {
		t.Fatal(err)
	}

	bridge.mu.Lock()
	bridge.state.Sequences["phone-old"] = 3
	err = bridge.persistStateLocked()
	bridge.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}

	saved, err := loadBridgeState(path)
	if err != nil {
		t.Fatal(err)
	}
	if saved.Pending["ticket-new"] == "" || saved.Sequences["phone-old"] != 3 {
		t.Fatalf("pending=%v sequence=%d", saved.Pending["ticket-new"] != "", saved.Sequences["phone-old"])
	}
}

func TestRunningBridgeClaimsPairingCreatedAfterStartup(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.json")
	initial := bridgeState{
		SchemaVersion: 2, RelayURL: "https://relay.example.com", HostID: "host-1", HostName: "Mac", HostToken: "token",
		Pending: map[string]string{}, DeviceSecrets: map[string]string{}, Sequences: map[string]uint64{},
		PendingOutbound: map[string]map[string]string{},
	}
	if err := saveBridgeState(path, initial); err != nil {
		t.Fatal(err)
	}
	runningState, err := loadBridgeState(path)
	if err != nil {
		t.Fatal(err)
	}
	bridge := &bridge{state: runningState, path: path}
	wantSecret := protocol.EncodeSecret(bytes.Repeat([]byte{7}, 32))
	external, err := loadBridgeState(path)
	if err != nil {
		t.Fatal(err)
	}
	external.Pending["ticket-new"] = wantSecret
	if err := saveBridgeState(path, external); err != nil {
		t.Fatal(err)
	}

	bridge.mu.Lock()
	gotSecret, err := bridge.claimPairingSecretLocked("ticket-new", "phone-new")
	bridge.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if gotSecret != wantSecret {
		t.Fatalf("secret mismatch")
	}
	saved, err := loadBridgeState(path)
	if err != nil {
		t.Fatal(err)
	}
	if saved.DeviceSecrets["phone-new"] != wantSecret || saved.Pending["ticket-new"] != "" {
		t.Fatalf("device enrolled=%v pending removed=%v", saved.DeviceSecrets["phone-new"] == wantSecret, saved.Pending["ticket-new"] == "")
	}
}

func TestPairingClaimWriteFailureLeavesRuntimeStateUnchanged(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.json")
	wantSecret := protocol.EncodeSecret(bytes.Repeat([]byte{9}, 32))
	initial := bridgeState{
		SchemaVersion: 2, RelayURL: "https://relay.example.com", HostID: "host-1", HostName: "Mac", HostToken: "token",
		Pending: map[string]string{"ticket-new": wantSecret}, DeviceSecrets: map[string]string{},
		Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{}, JournalKey: "journal-key",
	}
	if err := saveBridgeState(path, initial); err != nil {
		t.Fatal(err)
	}
	runningState, err := loadBridgeState(path)
	if err != nil {
		t.Fatal(err)
	}
	bridge := &bridge{
		state: runningState,
		path:  path,
		updateState: func(_ string, update func(*bridgeState) error) error {
			onDisk, loadErr := loadBridgeState(path)
			if loadErr != nil {
				return loadErr
			}
			if updateErr := update(&onDisk); updateErr != nil {
				return updateErr
			}
			return errors.New("injected atomic write failure")
		},
	}

	bridge.mu.Lock()
	_, err = bridge.claimPairingSecretLocked("ticket-new", "phone-new")
	bridge.mu.Unlock()
	if err == nil {
		t.Fatal("expected persistence failure")
	}
	if bridge.state.DeviceSecrets["phone-new"] != "" || bridge.state.Pending["ticket-new"] != wantSecret {
		t.Fatalf(
			"runtime mutated after failed persistence: device=%v pending=%v",
			bridge.state.DeviceSecrets["phone-new"] != "",
			bridge.state.Pending["ticket-new"] == wantSecret,
		)
	}
}

func TestDuplicateApprovalResponseCallsAppServerAndEmitsResultOnce(t *testing.T) {
	cache, err := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	command := protocol.Command{
		Type: "approval.respond", CommandID: "approval:1:decline", RequestID: "approval:1:decline",
		RunID: "run-1", ApprovalID: "approval-1", ProcessEpoch: "epoch-1",
		ServerRequestID: json.RawMessage(`7`), Decision: "decline",
	}
	respondCalls, emitCalls := 0, 0
	execute := func() error {
		return executeApprovalCommand(
			context.Background(), cache, command,
			func() error { return nil },
			func() error { respondCalls++; return nil },
			func(context.Context, string, string, string, json.RawMessage) (string, error) {
				emitCalls++
				return "event-result-1", nil
			},
		)
	}
	if err := execute(); err != nil {
		t.Fatal(err)
	}
	if err := execute(); err != nil {
		t.Fatal(err)
	}
	if respondCalls != 1 || emitCalls != 1 {
		t.Fatalf("respond=%d emit=%d", respondCalls, emitCalls)
	}
}

func TestApprovalLogicalPayloadRedactsSecretsAndClassifiesHighRisk(t *testing.T) {
	payload := approvalLogicalPayload(backend.Message{BackendID: "codex",
		ID: json.RawMessage(`7`), Method: "item/commandExecution/requestApproval",
		Params: json.RawMessage(`{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","command":"sudo curl https://api.example.com?access_token=top-secret"}`),
	}, "approval-1", "epoch-1")
	if string(payload) == "" || bytes.Contains(payload, []byte("top-secret")) {
		t.Fatalf("payload did not redact secret: %s", payload)
	}
	var decoded map[string]any
	_ = json.Unmarshal(payload, &decoded)
	if decoded["risk"] != "HIGH" || decoded["approvalId"] != "approval-1" {
		t.Fatalf("payload=%s", payload)
	}
}

func TestEventTargetsOnlyThreadOwner(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{RunID: "run-a", HostID: "host-a", DeviceID: "phone-a", ThreadID: "thread-a", TurnID: "turn-a"}); err != nil {
		t.Fatal(err)
	}
	bridge := &bridge{routes: routes}
	params := json.RawMessage(`{"threadId":"thread-a","turn":{"id":"turn-a"}}`)

	if got := bridge.eventTargets("codex", params); !reflect.DeepEqual(got, []string{"phone-a"}) {
		t.Fatalf("targets = %#v", got)
	}
}

func TestUnownedEventIsNotBroadcast(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{RunID: "run-a", HostID: "host-a", DeviceID: "phone-a", ThreadID: "thread-a"}); err != nil {
		t.Fatal(err)
	}
	bridge := &bridge{routes: routes}

	if got := bridge.eventTargets("codex", json.RawMessage(`{"threadId":"thread-b"}`)); len(got) != 0 {
		t.Fatalf("targets = %#v", got)
	}
}

func TestSnapshotStatusMapsAuthoritativeTurnState(t *testing.T) {
	completed, line := snapshotStatus(json.RawMessage(`{"thread":{"turns":[{"id":"turn-1","status":{"type":"completed"}}]}}`), "turn-1")
	if completed != "COMPLETED" || line != "任务已完成" {
		t.Fatalf("completed=%q line=%q", completed, line)
	}
	running, _ := snapshotStatus(json.RawMessage(`{"thread":{"turns":[{"id":"turn-2","status":{"type":"inProgress"}}]}}`), "turn-2")
	if running != "RUNNING" {
		t.Fatalf("running=%q", running)
	}
}

func TestSnapshotStatusKeepsUnknownOrMissingTurnReconciling(t *testing.T) {
	for name, raw := range map[string]json.RawMessage{
		"unknown":        json.RawMessage(`{"thread":{"turns":[{"id":"turn-1","status":{"type":"futureStatus"}}]}}`),
		"missing":        json.RawMessage(`{"thread":{"turns":[{"id":"other-turn","status":{"type":"completed"}}]}}`),
		"empty identity": json.RawMessage(`{"thread":{"turns":[{"id":"latest-turn","status":{"type":"inProgress"}}]}}`),
	} {
		t.Run(name, func(t *testing.T) {
			turnID := "turn-1"
			if name == "empty identity" {
				turnID = ""
			}
			status, _ := snapshotStatus(raw, turnID)
			if status != "RECONCILING" {
				t.Fatalf("status=%q", status)
			}
		})
	}
}

func TestTimelineLogicalPayloadTranslatesUnknownItemAndRedactsSecrets(t *testing.T) {
	eventType, payload, ok := timelineLogicalPayload(
		"item/completed",
		json.RawMessage(`{"threadId":"thread-1","item":{"id":"item-1","type":"futureTool","token":"top-secret","status":"completed"}}`),
	)
	if !ok || eventType != "run.timeline" || !bytes.Contains(payload, []byte(`"presentationKind":"STATUS"`)) {
		t.Fatalf("type=%q payload=%s ok=%v", eventType, payload, ok)
	}
	if bytes.Contains(payload, []byte("top-secret")) {
		t.Fatalf("timeline payload leaked secret: %s", payload)
	}
}

func TestTurnSnapshotExtractsStructuredCompletionAndLastAgentMessage(t *testing.T) {
	turn, ok := turnSnapshot(json.RawMessage(`{"thread":{"turns":[{
		"id":"turn-1","status":{"type":"completed"},
		"items":[{"type":"agentMessage","text":"真实摘要"}],
		"structuredOutput":{"summary":"结构化摘要","unresolved":[]}
	}]}}`), "turn-1")
	if !ok || turn.LastAgentMessage != "真实摘要" || !bytes.Contains(turn.StructuredOutput, []byte("结构化摘要")) {
		t.Fatalf("turn=%#v ok=%v", turn, ok)
	}
}

func TestMobileThreadReadResultKeepsLatestConversationAndBoundsLargeToolOutput(t *testing.T) {
	hugeOutput := strings.Repeat("tool-output-", 180_000)
	raw, err := json.Marshal(map[string]any{
		"thread": map[string]any{
			"id": "thread-1", "cwd": "/workspace/harness-apk",
			"turns": []any{
				map[string]any{"id": "turn-old", "status": map[string]any{"type": "completed"}, "items": []any{
					map[string]any{"id": "tool-old", "type": "commandExecution", "command": "run tests", "aggregatedOutput": hugeOutput, "status": "completed"},
				}},
				map[string]any{"id": "turn-latest", "status": map[string]any{"type": "completed"}, "items": []any{
					map[string]any{"id": "user-latest", "type": "userMessage", "content": []any{map[string]any{"type": "text", "text": "请检查渲染"}}},
					map[string]any{"id": "agent-latest", "type": "agentMessage", "text": "# READY\n\n**渲染正常**", "status": "completed"},
				}},
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}

	projected, err := mobileThreadReadResult(raw)
	if err != nil {
		t.Fatal(err)
	}
	if len(projected) > 640<<10 {
		t.Fatalf("mobile thread response is still too large: %d bytes", len(projected))
	}
	if bytes.Contains(projected, []byte(hugeOutput[:1024])) {
		t.Fatal("unbounded tool output remained in mobile history")
	}
	if !bytes.Contains(projected, []byte("请检查渲染")) || !bytes.Contains(projected, []byte("渲染正常")) {
		t.Fatalf("latest conversation was lost: %s", projected)
	}
	var decoded struct {
		Thread struct {
			Turns []struct {
				Items []map[string]any `json:"items"`
			} `json:"turns"`
		} `json:"thread"`
	}
	if err := json.Unmarshal(projected, &decoded); err != nil {
		t.Fatal(err)
	}
	if len(decoded.Thread.Turns) == 0 || len(decoded.Thread.Turns[len(decoded.Thread.Turns)-1].Items) != 2 {
		t.Fatalf("projected shape is not compatible with thread/read: %s", projected)
	}
}

func TestMobileThreadSummaryResultUsesLatestUserMessageWithoutReadingFullHistory(t *testing.T) {
	raw := json.RawMessage(`{"data":[
		{"id":"turn-latest","items":[
			{"id":"user-latest","type":"userMessage","content":[{"type":"text","text":"这是最新一句用户的话"}]},
			{"id":"agent-latest","type":"agentMessage","text":"最新回复"}
		],"itemsView":"summary"},
		{"id":"turn-older","items":[
			{"id":"user-older","type":"userMessage","text":"这是最早一句用户的话"}
		],"itemsView":"summary"}
	]}`)

	projected, err := mobileThreadSummaryResult("thread-1", raw)
	if err != nil {
		t.Fatal(err)
	}
	var summary struct {
		ThreadID          string `json:"threadId"`
		LatestUserMessage string `json:"latestUserMessage"`
	}
	if err := json.Unmarshal(projected, &summary); err != nil {
		t.Fatal(err)
	}
	if summary.ThreadID != "thread-1" || summary.LatestUserMessage != "这是最新一句用户的话" {
		t.Fatalf("summary=%#v", summary)
	}
}

func TestMobileThreadSummaryResultReportsUnfinishedLatestTurnAsRunning(t *testing.T) {
	raw := json.RawMessage(`{"data":[
		{"id":"turn-active","status":"interrupted","startedAt":1234,"completedAt":null,"items":[
			{"id":"user-active","type":"userMessage","text":"请继续执行"}
		],"itemsView":"summary"},
		{"id":"turn-completed","status":"completed","startedAt":1000,"completedAt":1100,"items":[]}
	]}`)

	projected, err := mobileThreadSummaryResult("thread-1", raw)
	if err != nil {
		t.Fatal(err)
	}
	var summary struct {
		Execution struct {
			State       string `json:"state"`
			TurnID      string `json:"turnId"`
			StartedAt   int64  `json:"startedAt"`
			CompletedAt *int64 `json:"completedAt"`
		} `json:"execution"`
	}
	if err := json.Unmarshal(projected, &summary); err != nil {
		t.Fatal(err)
	}
	if summary.Execution.State != "RUNNING" || summary.Execution.TurnID != "turn-active" ||
		summary.Execution.StartedAt != 1234 || summary.Execution.CompletedAt != nil {
		t.Fatalf("execution=%#v", summary.Execution)
	}
}

func TestMobileThreadSummaryResultMapsPersistedTerminalTurnStates(t *testing.T) {
	for _, test := range []struct {
		status string
		want   string
	}{
		{status: "completed", want: "COMPLETED"},
		{status: "failed", want: "FAILED"},
		{status: "interrupted", want: "INTERRUPTED"},
	} {
		t.Run(test.status, func(t *testing.T) {
			projected, err := mobileThreadSummaryResult("thread-1", json.RawMessage(fmt.Sprintf(
				`{"data":[{"id":"turn-1","status":%q,"startedAt":1000,"completedAt":1100,"items":[]}]}`,
				test.status,
			)))
			if err != nil {
				t.Fatal(err)
			}
			var summary struct {
				Execution struct {
					State string `json:"state"`
				} `json:"execution"`
			}
			if err := json.Unmarshal(projected, &summary); err != nil {
				t.Fatal(err)
			}
			if summary.Execution.State != test.want {
				t.Fatalf("state=%q want=%q", summary.Execution.State, test.want)
			}
		})
	}
}

func TestThreadReadUsesPaginatedSummaryHistoryInsteadOfFullRollout(t *testing.T) {
	reader, serverWriter := io.Pipe()
	requests := make(chan struct {
		Method string
		Params map[string]any
	}, 2)
	ctx, cancel := context.WithCancel(context.Background())
	client := appserverrpc.NewClient(reader, writerFunc(func(requestRaw []byte) (int, error) {
		var request struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params map[string]any  `json:"params"`
		}
		if err := json.Unmarshal(requestRaw, &request); err != nil {
			return 0, err
		}
		requests <- struct {
			Method string
			Params map[string]any
		}{Method: request.Method, Params: request.Params}
		result := `{"thread":{"id":"thread-1","cwd":"/workspace","turns":[]}}`
		if request.Method == "thread/turns/list" {
			result = `{"data":[{"id":"turn-new","items":[]}],"nextCursor":"older-page"}`
		}
		_, err := serverWriter.Write([]byte(fmt.Sprintf(`{"id":%s,"result":%s}`+"\n", request.ID, result)))
		return len(requestRaw), err
	}), "epoch-page")
	client.Start(ctx)
	t.Cleanup(func() {
		cancel()
		_ = serverWriter.Close()
		_ = reader.Close()
	})
	initialState := bridgeState{
		DeviceSecrets:   map[string]string{"device-1": "invalid-on-purpose"},
		Sequences:       map[string]uint64{},
		PendingOutbound: map[string]map[string]string{},
	}
	diskState := cloneBridgeState(initialState)
	responsePersisted := make(chan struct{}, 1)
	b := &bridge{
		backends: map[string]backend.Backend{"codex": newTestBackend(client)}, backendOrder: []string{"codex"}, state: initialState,
		updateState: func(_ string, update func(*bridgeState) error) error {
			if err := update(&diskState); err != nil {
				return err
			}
			select {
			case responsePersisted <- struct{}{}:
			default:
			}
			return nil
		},
	}

	if err := b.executeCommand(ctx, "device-1", protocol.Command{
		Type: "thread.read", RequestID: "request-1", ThreadID: "thread-1",
	}); err != nil {
		t.Fatal(err)
	}

	first := <-requests
	if first.Method != "thread/read" || first.Params["includeTurns"] != false {
		t.Fatalf("first request=%s params=%#v; mobile history must read metadata only", first.Method, first.Params)
	}
	second := <-requests
	if second.Method != "thread/turns/list" || second.Params["itemsView"] != "summary" || second.Params["sortDirection"] != "desc" {
		t.Fatalf("second request=%s params=%#v; mobile history must use descending summary pages", second.Method, second.Params)
	}
	select {
	case <-responsePersisted:
	case <-time.After(time.Second):
		t.Fatal("thread.read did not finish persisting its async response state")
	}
}

func TestThreadSummaryReadsOnlyRecentSummaryTurns(t *testing.T) {
	reader, serverWriter := io.Pipe()
	requests := make(chan struct {
		Method string
		Params map[string]any
	}, 1)
	ctx, cancel := context.WithCancel(context.Background())
	client := appserverrpc.NewClient(reader, writerFunc(func(requestRaw []byte) (int, error) {
		var request struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params map[string]any  `json:"params"`
		}
		if err := json.Unmarshal(requestRaw, &request); err != nil {
			return 0, err
		}
		requests <- struct {
			Method string
			Params map[string]any
		}{Method: request.Method, Params: request.Params}
		result := `{"data":[{"id":"turn-new","items":[{"id":"user-new","type":"userMessage","text":"最新问题"}],"itemsView":"summary"}]}`
		_, err := serverWriter.Write([]byte(fmt.Sprintf(`{"id":%s,"result":%s}`+"\n", request.ID, result)))
		return len(requestRaw), err
	}), "epoch-summary")
	client.Start(ctx)
	t.Cleanup(func() {
		cancel()
		_ = serverWriter.Close()
		_ = reader.Close()
	})
	initialState := bridgeState{
		DeviceSecrets:   map[string]string{"device-1": "invalid-on-purpose"},
		Sequences:       map[string]uint64{},
		PendingOutbound: map[string]map[string]string{},
	}
	diskState := cloneBridgeState(initialState)
	responsePersisted := make(chan struct{}, 1)
	b := &bridge{
		backends: map[string]backend.Backend{"codex": newTestBackend(client)}, backendOrder: []string{"codex"}, state: initialState,
		updateState: func(_ string, update func(*bridgeState) error) error {
			if err := update(&diskState); err != nil {
				return err
			}
			select {
			case responsePersisted <- struct{}{}:
			default:
			}
			return nil
		},
	}

	if err := b.executeCommand(ctx, "device-1", protocol.Command{
		Type: "thread.summary", RequestID: "summary-1", ThreadID: "thread-1",
	}); err != nil {
		t.Fatal(err)
	}

	select {
	case request := <-requests:
		if request.Method != "thread/turns/list" || request.Params["threadId"] != "thread-1" ||
			request.Params["itemsView"] != "summary" || request.Params["sortDirection"] != "desc" ||
			request.Params["limit"] != float64(3) {
			t.Fatalf("request=%s params=%#v", request.Method, request.Params)
		}
	case <-time.After(time.Second):
		t.Fatal("thread.summary did not request a bounded summary page")
	}
	select {
	case <-responsePersisted:
	case <-time.After(time.Second):
		t.Fatal("thread.summary did not finish persisting its async response state")
	}
}

func TestMobileThreadHistoryResultRestoresChronologicalOrderAndCursor(t *testing.T) {
	result, err := mobileThreadHistoryResult(
		json.RawMessage(`{"thread":{"id":"thread-1","cwd":"/workspace","turns":[]}}`),
		json.RawMessage(`{"data":[{"id":"turn-new","items":[{"id":"agent-new","type":"agentMessage","text":"new"}]},{"id":"turn-old","items":[{"id":"user-old","type":"userMessage","text":"old"}]}],"nextCursor":"cursor-older"}`),
	)
	if err != nil {
		t.Fatal(err)
	}
	var decoded struct {
		Thread struct {
			Turns []struct {
				ID string `json:"id"`
			} `json:"turns"`
		} `json:"thread"`
		MobileHistory struct {
			OlderCursor string `json:"olderCursor"`
			HasOlder    bool   `json:"hasOlder"`
		} `json:"mobileHistory"`
	}
	if err := json.Unmarshal(result, &decoded); err != nil {
		t.Fatal(err)
	}
	if got := []string{decoded.Thread.Turns[0].ID, decoded.Thread.Turns[1].ID}; !reflect.DeepEqual(got, []string{"turn-old", "turn-new"}) {
		t.Fatalf("turn order=%#v", got)
	}
	if decoded.MobileHistory.OlderCursor != "cursor-older" || !decoded.MobileHistory.HasOlder {
		t.Fatalf("mobile history=%#v", decoded.MobileHistory)
	}
}

func TestMobileThreadHistoryResultBoundsSummaryTextAndDropsUnapprovedFields(t *testing.T) {
	hugeText := strings.Repeat("private-summary-", 100_000)
	page, err := json.Marshal(map[string]any{
		"data": []any{
			map[string]any{
				"id": "turn-new",
				"items": []any{
					map[string]any{
						"id": "agent-new", "type": "agentMessage", "text": hugeText,
						"privatePayload": hugeText, "status": "completed",
					},
				},
			},
		},
		"nextCursor": "cursor-older",
	})
	if err != nil {
		t.Fatal(err)
	}
	result, err := mobileThreadHistoryResult(
		json.RawMessage(`{"thread":{"id":"thread-1","cwd":"/workspace","turns":[]}}`),
		page,
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(result) > maxMobileThreadResultBytes || bytes.Contains(result, []byte("privatePayload")) {
		t.Fatalf("paginated mobile history was not projected and bounded: %d bytes", len(result))
	}
	if !bytes.Contains(result, []byte("内容过长，已截断")) || !bytes.Contains(result, []byte("cursor-older")) {
		t.Fatalf("paginated history lost truncation marker or cursor: %s", result)
	}
}

func TestMobileThreadHistoryResultKeepsEveryTurnCoveredByReturnedCursor(t *testing.T) {
	hugeText := strings.Repeat("summary-near-limit-", 8_000)
	turns := make([]any, 0, mobileThreadHistoryPageSize)
	for index := 0; index < mobileThreadHistoryPageSize; index++ {
		turns = append(turns, map[string]any{
			"id": fmt.Sprintf("turn-%d", index),
			"items": []any{
				map[string]any{"id": fmt.Sprintf("user-%d", index), "type": "userMessage", "text": hugeText},
				map[string]any{"id": fmt.Sprintf("agent-%d", index), "type": "agentMessage", "text": hugeText},
			},
		})
	}
	page, err := json.Marshal(map[string]any{"data": turns, "nextCursor": "next-page"})
	if err != nil {
		t.Fatal(err)
	}
	result, err := mobileThreadHistoryResult(
		json.RawMessage(`{"thread":{"id":"thread-1","cwd":"/workspace","turns":[]}}`),
		page,
	)
	if err != nil {
		t.Fatal(err)
	}
	var decoded struct {
		Thread struct {
			Turns []struct {
				ID string `json:"id"`
			} `json:"turns"`
		} `json:"thread"`
		MobileHistory struct {
			OlderCursor string `json:"olderCursor"`
		} `json:"mobileHistory"`
	}
	if err := json.Unmarshal(result, &decoded); err != nil {
		t.Fatal(err)
	}
	if len(result) > maxMobileThreadResultBytes || len(decoded.Thread.Turns) != mobileThreadHistoryPageSize {
		t.Fatalf("cursor skipped projected-out turns: bytes=%d turns=%d", len(result), len(decoded.Thread.Turns))
	}
	if decoded.MobileHistory.OlderCursor != "next-page" {
		t.Fatalf("older cursor=%q", decoded.MobileHistory.OlderCursor)
	}
}

func TestMobileCodexEventEnvelopeWhitelistsTimelineAndBoundsItemPayload(t *testing.T) {
	hugeOutput := strings.Repeat("private-tool-output-", 100_000)
	params, err := json.Marshal(map[string]any{
		"threadId": "thread-1", "turnId": "turn-1",
		"item": map[string]any{
			"id": "item-1", "type": "commandExecution", "command": "git status",
			"aggregatedOutput": hugeOutput, "status": "completed", "exitCode": 0,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	envelope, ok := mobileCodexEventEnvelope(backend.Message{BackendID: "codex",
		Method: "item/completed", Params: params,
	}, "epoch-1")
	if !ok {
		t.Fatal("timeline event was dropped")
	}
	if len(envelope) > 96<<10 || bytes.Contains(envelope, []byte("private-tool-output")) {
		t.Fatalf("mobile event remained unbounded: %d bytes", len(envelope))
	}
	if !bytes.Contains(envelope, []byte("git status")) || !bytes.Contains(envelope, []byte(`"exitCode":0`)) {
		t.Fatalf("command summary was lost: %s", envelope)
	}
	if _, ok := mobileCodexEventEnvelope(backend.Message{BackendID: "codex",
		Method: "account/updated", Params: json.RawMessage(`{"profile":"large internal payload"}`),
	}, "epoch-1"); ok {
		t.Fatal("unrelated app-server event was forwarded to the phone")
	}
}

func TestRouteForParamsUsesThreadAndTurnAcrossMultipleProjectBindings(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	first := runstate.Route{
		RunID: "run-a", BindingID: "project-a", HostID: "host-a", DeviceID: "phone-a",
		ThreadID: "thread-shared", TurnID: "turn-a",
	}
	second := runstate.Route{
		RunID: "run-b", BindingID: "project-b", HostID: "host-a", DeviceID: "phone-b",
		ThreadID: "thread-shared", TurnID: "turn-b",
	}
	if err := routes.Put(first); err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(second); err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes}

	completionRoute, ok := b.routeForParams("codex", json.RawMessage(`{"threadId":"thread-shared","turn":{"id":"turn-b"}}`))
	if !ok || completionRoute.RunID != "run-b" || completionRoute.BindingID != "project-b" {
		t.Fatalf("completion route = %#v ok=%v", completionRoute, ok)
	}
	approvalRoute, ok := b.routeForParams("codex", json.RawMessage(`{"threadId":"thread-shared","turnId":"turn-a","itemId":"approval-a"}`))
	if !ok || approvalRoute.RunID != "run-a" || approvalRoute.BindingID != "project-a" {
		t.Fatalf("approval route = %#v ok=%v", approvalRoute, ok)
	}
}

func TestRouteForParamsWithoutTurnRequiresOneActiveRoute(t *testing.T) {
	dir := t.TempDir()
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	for _, route := range []runstate.Route{
		{RunID: "run-a", HostID: "host-a", DeviceID: "phone-a", ThreadID: "thread-shared", TurnID: "turn-a"},
		{RunID: "run-b", HostID: "host-a", DeviceID: "phone-b", ThreadID: "thread-shared", TurnID: "turn-b"},
	} {
		if err := routes.Put(route); err != nil {
			t.Fatal(err)
		}
	}
	b := &bridge{routes: routes, terminals: terminals}
	if route, ok := b.routeForParams("codex", json.RawMessage(`{"threadId":"thread-shared"}`)); ok {
		t.Fatalf("ambiguous thread event routed to %#v", route)
	}
	if _, _, err := terminals.Freeze(completion.TerminalRunRecord{
		RunID: "run-a", Status: "COMPLETED", CompletionJSON: json.RawMessage(`{"schemaVersion":2}`), CompletedAt: 1,
	}); err != nil {
		t.Fatal(err)
	}
	route, ok := b.routeForParams("codex", json.RawMessage(`{"threadId":"thread-shared"}`))
	if !ok || route.RunID != "run-b" {
		t.Fatalf("unique active route = %#v ok=%v", route, ok)
	}
}

func TestTurnStartedBindsClaimedRouteBeforeRealtimeEvents(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{Type: "turn.start", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1"}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	params := json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress"}}`)
	if err := b.bindStartedTurnRoute("dsh", params); err != nil {
		t.Fatal(err)
	}
	route, ok := b.routeForParams("dsh", params)
	if !ok || route.RunID != "run-1" || route.TurnID != "turn-1" {
		t.Fatalf("started route=%#v ok=%v", route, ok)
	}
}

func TestRouteForParamsWithUnknownTurnDoesNotFallBackToLegacyEmptyTurnRoute(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "legacy-run", HostID: "host-a", DeviceID: "phone-a", ThreadID: "thread-shared",
	}); err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes}
	if route, ok := b.routeForParams("codex", json.RawMessage(`{"threadId":"thread-shared","turnId":"unknown-turn"}`)); ok {
		t.Fatalf("unknown exact turn fell back to legacy route: %#v", route)
	}
}

func TestLegacyTurnStartBackfillsRealTurnIDBeforeRouting(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{Type: "turn.start", ThreadID: "thread-1", BindingID: "project-1"}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	if err := b.backfillTurnRoute(command, json.RawMessage(`{"turn":{"id":"turn-real"}}`)); err != nil {
		t.Fatal(err)
	}
	route, ok := b.routeForParams("codex", json.RawMessage(`{"threadId":"thread-1","turnId":"turn-real"}`))
	if !ok || route.RunID != "legacy:thread-1" || route.DeviceID != "phone-1" {
		t.Fatalf("backfilled route=%#v ok=%v", route, ok)
	}
}

func TestLegacyTurnStartResumesPersistedThreadBeforeSafeRetry(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	var requests []appServerTestRequest
	app := appProcessScripted(t, func(request appServerTestRequest) (json.RawMessage, json.RawMessage) {
		requests = append(requests, request)
		switch len(requests) {
		case 1:
			return nil, json.RawMessage(`{"code":-32600,"message":"thread not found: thread-1"}`)
		case 2:
			return json.RawMessage(`{"thread":{"id":"thread-1"}}`), nil
		case 3:
			return json.RawMessage(`{"thread":{"id":"thread-1"}}`), nil
		default:
			return json.RawMessage(`{"turn":{"id":"turn-real"}}`), nil
		}
	})
	b := &bridge{
		backends: map[string]backend.Backend{"codex": app}, backendOrder: []string{"codex"}, commandCache: cache, routes: routes, state: bridgeState{HostID: "host-1"},
	}
	command := protocol.Command{
		Type: "turn.start", RequestID: "request-1", ThreadID: "thread-1", Text: "继续任务",
	}

	result, outcome, err := b.executeTurnStartOnce(
		context.Background(), "phone-1", command, b.backendFor("codex"), legacyTurnStartParams(command),
	)

	if err != nil || outcome != turnRPCSucceeded || string(result) != `{"turn":{"id":"turn-real"}}` {
		t.Fatalf("result=%s outcome=%q err=%v", result, outcome, err)
	}
	if got := []string{requests[0].Method, requests[1].Method, requests[2].Method, requests[3].Method}; !reflect.DeepEqual(got, []string{"turn/start", "thread/read", "thread/resume", "turn/start"}) {
		t.Fatalf("methods=%v", got)
	}
	if requests[1].Params["threadId"] != "thread-1" || requests[1].Params["includeTurns"] != false {
		t.Fatalf("metadata params=%#v", requests[1].Params)
	}
	if requests[2].Params["threadId"] != "thread-1" || requests[2].Params["excludeTurns"] != true {
		t.Fatalf("resume params=%#v", requests[2].Params)
	}
	for _, index := range []int{0, 3} {
		if requests[index].Params["clientUserMessageId"] != "request-1" {
			t.Fatalf("turn params[%d]=%#v", index, requests[index].Params)
		}
	}
	if route, ok := routes.ByThreadTurn("thread-1", "turn-real"); !ok || route.DeviceID != "phone-1" {
		t.Fatalf("route=%#v ok=%v", route, ok)
	}
}

func TestLegacyTurnStartDoesNotAttemptAnUnboundedResumeWhenMetadataReadFails(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	var methods []string
	app := appProcessScripted(t, func(request appServerTestRequest) (json.RawMessage, json.RawMessage) {
		methods = append(methods, request.Method)
		switch len(methods) {
		case 1:
			return nil, json.RawMessage(`{"code":-32600,"message":"thread not found: thread-1"}`)
		case 2:
			return nil, json.RawMessage(`{"code":-32603,"message":"metadata unavailable"}`)
		default:
			t.Fatalf("unexpected unbounded resume request: %#v", request)
			return nil, nil
		}
	})
	b := &bridge{backends: map[string]backend.Backend{"codex": app}, backendOrder: []string{"codex"}, commandCache: cache, routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{
		Type: "turn.start", RequestID: "request-metadata-failure", ThreadID: "thread-1", Text: "继续任务",
	}

	_, outcome, err := b.executeTurnStartOnce(
		context.Background(), "phone-1", command, b.backendFor("codex"), legacyTurnStartParams(command),
	)

	if err == nil || outcome != turnRPCFailed || !strings.Contains(err.Error(), "metadata unavailable") {
		t.Fatalf("outcome=%q err=%v", outcome, err)
	}
	if !reflect.DeepEqual(methods, []string{"turn/start", "thread/read"}) {
		t.Fatalf("methods=%v", methods)
	}
}

func TestLegacyTurnStartContinuesAnOversizedPersistedThreadWithoutResume(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	rolloutPath := filepath.Join(dir, "oversized-thread.jsonl")
	file, err := os.Create(rolloutPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Truncate(300 << 20); err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	var requests []appServerTestRequest
	app := appProcessScripted(t, func(request appServerTestRequest) (json.RawMessage, json.RawMessage) {
		requests = append(requests, request)
		switch len(requests) {
		case 1:
			return nil, json.RawMessage(`{"code":-32600,"message":"thread not found: thread-1"}`)
		case 2:
			return json.RawMessage(fmt.Sprintf(`{"thread":{"id":"thread-1","name":"review","cwd":"/workspace/project","path":%q}}`, rolloutPath)), nil
		case 3:
			return json.RawMessage(`{"data":[{"id":"turn-new","items":[{"id":"user-new","type":"userMessage","text":"最新用户要求"},{"id":"agent-new","type":"agentMessage","text":"最新处理结论"},{"id":"tool-new","type":"commandExecution","command":"printenv VERY_SECRET"}]},{"id":"turn-old","items":[{"id":"user-old","type":"userMessage","text":"较早用户背景"},{"id":"agent-old","type":"agentMessage","text":"较早处理结论"}]}]}`), nil
		case 4:
			return json.RawMessage(`{"thread":{"id":"thread-continuation"},"cwd":"/workspace/project"}`), nil
		case 5:
			return json.RawMessage(`{"turn":{"id":"turn-real"}}`), nil
		default:
			t.Fatalf("unexpected app-server request: %#v", request)
			return nil, nil
		}
	})
	b := &bridge{
		backends: map[string]backend.Backend{"codex": app}, backendOrder: []string{"codex"}, commandCache: cache, routes: routes, state: bridgeState{HostID: "host-1"},
	}
	b.updateState = func(_ string, update func(*bridgeState) error) error {
		persisted := cloneBridgeState(b.state)
		return update(&persisted)
	}
	command := protocol.Command{
		Type: "turn.start", RequestID: "request-oversized", ThreadID: "thread-1", Text: "继续任务",
	}

	result, outcome, err := b.executeTurnStartOnce(
		context.Background(), "phone-1", command, b.backendFor("codex"), legacyTurnStartParams(command),
	)

	if err != nil || outcome != turnRPCSucceeded {
		t.Fatalf("result=%s outcome=%q err=%v", result, outcome, err)
	}
	methods := make([]string, len(requests))
	for index := range requests {
		methods[index] = requests[index].Method
	}
	if !reflect.DeepEqual(methods, []string{"turn/start", "thread/read", "thread/turns/list", "thread/start", "turn/start"}) {
		t.Fatalf("methods=%v", methods)
	}
	if requests[2].Params["threadId"] != "thread-1" || requests[2].Params["limit"] != float64(8) && requests[2].Params["limit"] != 8 || requests[2].Params["itemsView"] != "summary" {
		t.Fatalf("lazy history params=%#v", requests[2].Params)
	}
	if requests[3].Params["cwd"] != "/workspace/project" {
		t.Fatalf("continuation thread params=%#v", requests[3].Params)
	}
	if requests[4].Params["threadId"] != "thread-continuation" || requests[4].Params["clientUserMessageId"] != "request-oversized" {
		t.Fatalf("continuation turn params=%#v", requests[4].Params)
	}
	input := requests[4].Params["input"].([]any)
	if len(input) != 1 || input[0].(map[string]any)["text"] != "继续任务" {
		t.Fatalf("continuation input=%#v", input)
	}
	additional := requests[4].Params["additionalContext"].(map[string]any)
	history := additional["harness.lazyContinuation.history"].(map[string]any)
	if history["kind"] != "untrusted" {
		t.Fatalf("history context=%#v", history)
	}
	handoff, _ := history["value"].(string)
	for _, expected := range []string{"较早用户背景", "较早处理结论", "最新用户要求", "最新处理结论"} {
		if !strings.Contains(handoff, expected) {
			t.Fatalf("handoff missing %q: %q", expected, handoff)
		}
	}
	if strings.Contains(handoff, "printenv VERY_SECRET") || len([]byte(handoff)) > maxMobilePaginatedTextBytes {
		t.Fatalf("handoff leaked tool output or exceeded bound: %q", handoff)
	}
	var response struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
		Continuation struct {
			ThreadID              string `json:"threadId"`
			ContinuedFromThreadID string `json:"continuedFromThreadId"`
			CWD                   string `json:"cwd"`
		} `json:"continuation"`
	}
	if err := json.Unmarshal(result, &response); err != nil {
		t.Fatal(err)
	}
	if response.Turn.ID != "turn-real" || response.Continuation.ThreadID != "thread-continuation" || response.Continuation.ContinuedFromThreadID != "thread-1" || response.Continuation.CWD != "/workspace/project" {
		t.Fatalf("continuation result=%s", result)
	}
	if _, ok := routes.ByThreadTurn("thread-1", "turn-real"); ok {
		t.Fatal("continuation turn must not be routed under the oversized source thread")
	}
	if route, ok := routes.ByThreadTurn("thread-continuation", "turn-real"); !ok || route.DeviceID != "phone-1" {
		t.Fatalf("continuation route=%#v ok=%v", route, ok)
	}
	record := b.state.ThreadContinuations["thread-1"]
	if record.RootThreadID != "thread-1" || record.Name != "review" || !reflect.DeepEqual(record.ThreadIDs, []string{"thread-1", "thread-continuation"}) {
		t.Fatalf("persisted continuation=%#v", record)
	}
	replayed, replayedOutcome, replayedErr := b.executeTurnStartOnce(
		context.Background(), "phone-1", command, b.backendFor("codex"), legacyTurnStartParams(command),
	)
	if replayedErr != nil || replayedOutcome != turnRPCSucceeded || string(replayed) != string(result) || len(requests) != 5 {
		t.Fatalf("replayed=%s outcome=%q err=%v appCalls=%d", replayed, replayedOutcome, replayedErr, len(requests))
	}
}

func TestMobileThreadListCollapsesContinuationUnderOriginalTitle(t *testing.T) {
	result, err := mobileThreadListResult(
		json.RawMessage(`{"data":[
			{"id":"thread-current","preview":"Reply exactly OK","cwd":"/workspace/project","updatedAt":20},
			{"id":"thread-root","name":"review","preview":"hello","cwd":"/workspace/project","updatedAt":10},
			{"id":"thread-other","name":"other","preview":"keep me","cwd":"/workspace/other","updatedAt":5}
		]}`),
		map[string]bridgestate.ThreadContinuation{
			"thread-root": {
				RootThreadID: "thread-root",
				ThreadIDs:    []string{"thread-root", "thread-current"},
				Name:         "review",
				CWD:          "/workspace/project",
			},
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	var response struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(result, &response); err != nil {
		t.Fatal(err)
	}
	if len(response.Data) != 2 || response.Data[0]["id"] != "thread-current" || response.Data[0]["name"] != "review" || response.Data[0]["continuedFromThreadId"] != "thread-root" || response.Data[1]["id"] != "thread-other" {
		t.Fatalf("collapsed list=%s", result)
	}
}

func TestContinuationHistoryCursorLazilyWalksIntoOriginalThread(t *testing.T) {
	record := bridgestate.ThreadContinuation{
		RootThreadID: "thread-root",
		ThreadIDs:    []string{"thread-root", "thread-current"},
	}
	older := continuationOlderCursor(record, "thread-current", nil)
	if older == nil || *older == "" {
		t.Fatal("continuation did not expose original history")
	}
	target, cursor, err := continuationHistoryRequest("thread-current", *older, &record)
	if err != nil || target != "thread-root" || cursor != "" {
		t.Fatalf("history request target=%q cursor=%q err=%v", target, cursor, err)
	}
	next := "root-page-2"
	older = continuationOlderCursor(record, "thread-root", &next)
	target, cursor, err = continuationHistoryRequest("thread-current", *older, &record)
	if err != nil || target != "thread-root" || cursor != next {
		t.Fatalf("paged history target=%q cursor=%q err=%v", target, cursor, err)
	}
}

func TestTurnStartedDoesNotBindFrozenTerminalRoute(t *testing.T) {
	dir := t.TempDir()
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	route := runstate.Route{RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1"}
	if err := routes.Put(route); err != nil {
		t.Fatal(err)
	}
	if _, _, err := terminals.Freeze(completion.TerminalRunRecord{RunID: "run-1", Status: "COMPLETED", CompletedAt: 1234}); err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, terminals: terminals}
	params := json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-late"}}`)
	if err := b.bindStartedTurnRoute("dsh", params); err == nil {
		t.Fatal("late turn/started bound a frozen route")
	}
	updated, _ := routes.ByRun("run-1")
	if updated.TurnID != "" {
		t.Fatalf("frozen route turn=%q", updated.TurnID)
	}
}

func TestRouteForParamsIgnoresLegacyTerminalLedger(t *testing.T) {
	dir := t.TempDir()
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	route := runstate.Route{RunID: "legacy:thread-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-next"}
	if err := routes.Put(route); err != nil {
		t.Fatal(err)
	}
	if _, _, err := terminals.Freeze(completion.TerminalRunRecord{RunID: route.RunID, Status: "COMPLETED", CompletedAt: 1234}); err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, terminals: terminals}
	params := json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-next"}}`)
	matched, ok := b.routeForParams("dsh", params)
	if !ok || matched.RunID != route.RunID {
		t.Fatalf("legacy route blocked by historical terminal record: %#v ok=%v", matched, ok)
	}
}

func TestAppServerRoutingCapturesCompletionTargetBeforeTerminalFreeze(t *testing.T) {
	dir := t.TempDir()
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	route := runstate.Route{RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-old"}
	if err := routes.Put(route); err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, terminals: terminals}
	params := json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-old"}}`)
	matched, targets := b.appServerRouting("dsh", params)
	if matched.RunID != "run-1" || !reflect.DeepEqual(targets, []string{"phone-1"}) {
		t.Fatalf("captured route=%#v targets=%#v", matched, targets)
	}
	if _, _, err := terminals.Freeze(completion.TerminalRunRecord{RunID: "run-1", Status: "COMPLETED", CompletedAt: 1234}); err != nil {
		t.Fatal(err)
	}
	if _, ok := b.routeForParams("dsh", params); ok {
		t.Fatal("frozen route still resolves dynamically")
	}
	if !reflect.DeepEqual(targets, []string{"phone-1"}) {
		t.Fatalf("captured target changed after freeze: %#v", targets)
	}
}

func TestRouteForParamsRejectsExactFrozenTerminalRoute(t *testing.T) {
	dir := t.TempDir()
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	route := runstate.Route{RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-old"}
	if err := routes.Put(route); err != nil {
		t.Fatal(err)
	}
	if _, _, err := terminals.Freeze(completion.TerminalRunRecord{RunID: "run-1", Status: "COMPLETED", CompletedAt: 1234}); err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, terminals: terminals}
	params := json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-old"}}`)
	if matched, ok := b.routeForParams("dsh", params); ok {
		t.Fatalf("exact frozen route matched: %#v", matched)
	}
}

func TestBeginTransitionAtomicallyStoresRelease(t *testing.T) {
	b := &bridge{}
	command := protocol.Command{Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-old"}
	release := func() {}
	generation := b.beginTurnTransition(command, release)
	transition, ok := b.turnTransitionGeneration("dsh", "thread-1", generation)
	if !ok || transition.release == nil {
		t.Fatalf("transition release was not stored atomically: %#v ok=%v", transition, ok)
	}
}

func TestDelayedOlderStartedDoesNotRegressNewTransition(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	if err := routes.Put(runstate.Route{RunID: "run-1", BackendID: "dsh", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1"}); err != nil {
		t.Fatal(err)
	}
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-1"}}`)); err != nil {
		t.Fatal(err)
	}
	if err := routes.AdvanceTurnBackend("dsh", "run-1", "thread-1", "turn-1", "turn-2"); err != nil {
		t.Fatal(err)
	}
	command := protocol.Command{Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-2"}
	released := make(chan struct{})
	b.beginTurnTransition(command, func() { close(released) })
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-1"}}`)); err != nil {
		t.Fatal(err)
	}
	route, _ := routes.ByRun("run-1")
	if route.TurnID != "turn-2" {
		t.Fatalf("route regressed to %q", route.TurnID)
	}
	select {
	case <-released:
		t.Fatal("delayed older notification ended new transition")
	default:
	}
}

func TestStalePredecessorStartedDoesNotEndNewTransition(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-old"}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	generation := b.beginTurnTransition(command)
	released := make(chan struct{})
	b.setTurnTransitionRelease("dsh", "thread-1", generation, func() { close(released) })
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-old"}}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-released:
		t.Fatal("stale predecessor notification ended the new transition")
	default:
	}
}

func TestExactStartedNotificationEndsMatchingTransition(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-old"}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	generation := b.beginTurnTransition(command)
	released := make(chan struct{})
	b.setTurnTransitionRelease("dsh", "thread-1", generation, func() { close(released) })
	if err := routes.AdvanceTurn("run-1", "thread-1", "turn-old", "turn-new"); err != nil {
		t.Fatal(err)
	}
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-new"}}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-released:
	case <-time.After(time.Second):
		t.Fatal("exact started notification did not end transition")
	}
}

func TestSteerBackfillRetiresTransitionBeforeReturning(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-old"}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	released := make(chan struct{})
	generation := b.beginTurnTransition(command, func() { close(released) })
	if err := b.backfillTurnSteerRoute(command, generation, json.RawMessage(`{"turn":{"id":"turn-new"}}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-released:
	case <-time.After(time.Second):
		t.Fatal("backfill returned before retiring transition")
	}
}

func TestPredecessorBackfillSucceedsAfterQueuedSuccessorAdvancesRoute(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	first := protocol.Command{Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-1"}
	if err := b.claimThread(first, "phone-1"); err != nil {
		t.Fatal(err)
	}
	firstGeneration := b.beginTurnTransition(first)
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-2"}}`)); err != nil {
		t.Fatal(err)
	}
	second := first
	second.ExpectedTurnID = "turn-2"
	b.beginTurnTransition(second)
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-3"}}`)); err != nil {
		t.Fatal(err)
	}
	if err := b.backfillTurnSteerRoute(first, firstGeneration, json.RawMessage(`{"turn":{"id":"turn-2"}}`)); err != nil {
		t.Fatalf("late predecessor backfill: %v", err)
	}
}

func TestTurnSteerStartedNotificationCanRaceAheadOfRPCBackfill(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{
		Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-existing",
	}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	generation := b.beginTurnTransition(command)
	params := json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-next","status":"inProgress"}}`)
	if err := b.bindStartedTurnRoute("dsh", params); err != nil {
		t.Fatal(err)
	}
	if err := b.backfillTurnSteerRoute(command, generation, json.RawMessage(`{"turn":{"id":"turn-next"}}`)); err != nil {
		t.Fatal(err)
	}
	route, ok := routes.ByThreadTurnBackend("thread-1", "turn-next", "dsh")
	if !ok || route.RunID != "run-1" {
		t.Fatalf("raced steer route=%#v ok=%v", route, ok)
	}
}

func TestRequestTurnStartSerializesBackendCallsForSameThread(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan int, 2)
	releaseFirst := make(chan struct{})
	var calls int
	bd := backend.NewFake("dsh").OnScript("turn/start", func(method string, params any) (json.RawMessage, error) {
		calls++
		call := calls
		started <- call
		if call == 1 {
			<-releaseFirst
		}
		return json.RawMessage(fmt.Sprintf(`{"turn":{"id":"turn-%d"}}`, call)), nil
	})
	b := &bridge{
		commandCache: cache, routes: routes,
		state: bridgeState{HostID: "host-1", DeviceSecrets: map[string]string{}, Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{}},
	}
	first := protocol.Command{Type: "turn.start", BackendID: "dsh", CommandID: "command-1", RequestID: "request-1", ThreadID: "thread-1", Text: "first"}
	second := protocol.Command{Type: "turn.start", BackendID: "dsh", CommandID: "command-2", RequestID: "request-2", ThreadID: "thread-1", Text: "second"}
	if err := b.requestTurnStart(context.Background(), "phone-1", first, bd, legacyTurnStartParams(first)); err != nil {
		t.Fatal(err)
	}
	if err := b.requestTurnStart(context.Background(), "phone-1", second, bd, legacyTurnStartParams(second)); err != nil {
		t.Fatal(err)
	}
	select {
	case call := <-started:
		if call != 1 {
			t.Fatalf("first backend call=%d", call)
		}
	case <-time.After(time.Second):
		t.Fatal("first turn.start did not begin")
	}
	select {
	case call := <-started:
		t.Fatalf("second turn.start began before first finished: %d", call)
	case <-time.After(20 * time.Millisecond):
	}
	close(releaseFirst)
	select {
	case call := <-started:
		if call != 2 {
			t.Fatalf("second backend call=%d", call)
		}
	case <-time.After(time.Second):
		t.Fatal("second turn.start stayed blocked")
	}
	deadline := time.Now().Add(time.Second)
	for {
		record, ok := cache.Lookup("legacy-turn-start:command-2")
		if ok && record.Status == commandcache.StatusSucceeded {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("second turn.start did not persist completion: %#v ok=%v", record, ok)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestQueuedControlDispatchRefreshesOnlyValidArrivalSnapshot(t *testing.T) {
	if got := controlDispatchTurnID("turn-1", "turn-1", "turn-2", true); got != "turn-2" {
		t.Fatalf("queued valid dispatch=%q", got)
	}
	if got := controlDispatchTurnID("turn-1", "turn-2", "turn-2", true); got != "turn-1" {
		t.Fatalf("queued stale dispatch=%q", got)
	}
	if got := controlDispatchTurnID("turn-1", "turn-1", "turn-2", false); got != "turn-1" {
		t.Fatalf("nonqueued dispatch=%q", got)
	}
}

func TestTurnCallsAreSerializedPerBackendThread(t *testing.T) {
	b := &bridge{}
	waitFirst, finishFirst := b.enqueueTurnCall("dsh", "thread-1")
	waitSecond, finishSecond := b.enqueueTurnCall("dsh", "thread-1")
	defer finishSecond()
	if err := waitFirst(context.Background()); err != nil {
		t.Fatal(err)
	}
	blockedCtx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := waitSecond(blockedCtx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("second same-thread call did not wait: %v", err)
	}
	finishFirst()
	if err := waitSecond(context.Background()); err != nil {
		t.Fatalf("second same-thread call stayed blocked: %v", err)
	}
	waitOther, finishOther := b.enqueueTurnCall("dsh", "thread-2")
	defer finishOther()
	if err := waitOther(context.Background()); err != nil {
		t.Fatalf("different thread was blocked: %v", err)
	}
}

func TestCancelledQueuedTurnCallDoesNotReleaseLaterCallEarly(t *testing.T) {
	b := &bridge{}
	waitFirst, finishFirst := b.enqueueTurnCall("dsh", "thread-1")
	waitSecond, finishSecond := b.enqueueTurnCall("dsh", "thread-1")
	waitThird, finishThird := b.enqueueTurnCall("dsh", "thread-1")
	defer finishThird()
	if err := waitFirst(context.Background()); err != nil {
		t.Fatal(err)
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if err := waitSecond(cancelled); !errors.Is(err, context.Canceled) {
		t.Fatalf("queued cancellation=%v", err)
	}
	secondFinished := make(chan struct{})
	go func() {
		finishSecond()
		close(secondFinished)
	}()
	thirdCtx, cancelThird := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancelThird()
	if err := waitThird(thirdCtx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("third call bypassed running first after cancellation: %v", err)
	}
	finishFirst()
	select {
	case <-secondFinished:
	case <-time.After(time.Second):
		t.Fatal("cancelled queue entry did not drain")
	}
	if err := waitThird(context.Background()); err != nil {
		t.Fatalf("third call stayed blocked after predecessor drained: %v", err)
	}
}

func TestOverlappingTurnSteersAdvanceInRequestOrder(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	first := protocol.Command{
		Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-existing",
	}
	second := first
	second.RequestID = "second"
	if err := b.claimThread(first, "phone-1"); err != nil {
		t.Fatal(err)
	}
	firstGeneration := b.beginTurnTransition(first)
	b.beginTurnTransition(second)
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-next"}}`)); err != nil {
		t.Fatal(err)
	}
	b.endTurnTransition(first, firstGeneration)
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-third"}}`)); err != nil {
		t.Fatalf("second steer did not inherit the preceding turn: %v", err)
	}
	route, ok := routes.ByThreadTurnBackend("thread-1", "turn-third", "dsh")
	if !ok || route.RunID != "run-1" {
		t.Fatalf("overlapping steer route=%#v ok=%v", route, ok)
	}
}

func TestOverlappingTurnSteerClaimDoesNotRegressAdvancedRoute(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	first := protocol.Command{
		Type: "turn.steer", BackendID: "dsh", RunID: "run-1", ThreadID: "thread-1", ExpectedTurnID: "turn-existing",
	}
	if err := b.claimThread(first, "phone-1"); err != nil {
		t.Fatal(err)
	}
	generation := b.beginTurnTransition(first)
	if err := b.bindStartedTurnRoute("dsh", json.RawMessage(`{"threadId":"thread-1","turn":{"id":"turn-next"}}`)); err != nil {
		t.Fatal(err)
	}
	b.endTurnTransition(first, generation)
	second := first
	second.RequestID = "second"
	if err := b.claimThread(second, "phone-1"); err != nil {
		t.Fatal(err)
	}
	route, _ := routes.ByRun("run-1")
	if route.TurnID != "turn-next" {
		t.Fatalf("stale overlapping claim regressed route to %q", route.TurnID)
	}
	prepared, params := b.prepareTurnSteer(second, map[string]any{"expectedTurnId": second.ExpectedTurnID})
	if prepared.ExpectedTurnID != "turn-next" || params.(map[string]any)["expectedTurnId"] != "turn-next" {
		t.Fatalf("stale steer was not refreshed: command=%#v params=%#v", prepared, params)
	}
}

func TestLegacyTurnSteerBackfillsNewTurnIDAfterExpectedTurn(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{
		Type: "turn.steer", BackendID: "dsh", ThreadID: "thread-1", ExpectedTurnID: "turn-existing",
	}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	if err := b.backfillTurnRoute(command, json.RawMessage(`{"turn":{"id":"turn-next"}}`)); err != nil {
		t.Fatal(err)
	}
	if _, old := routes.ByThreadTurnBackend("thread-1", "turn-existing", "dsh"); old {
		t.Fatal("steer kept the completed turn route")
	}
	route, ok := routes.ByThreadTurnBackend("thread-1", "turn-next", "dsh")
	if !ok || route.RunID != "legacy:thread-1" {
		t.Fatalf("steer next route=%#v ok=%v", route, ok)
	}
}

func TestLegacyTurnSteerClaimsExpectedTurnID(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{Type: "turn.steer", ThreadID: "thread-1", ExpectedTurnID: "turn-existing"}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	if route, ok := routes.ByThreadTurn("thread-1", "turn-existing"); !ok || route.RunID != "legacy:thread-1" {
		t.Fatalf("steer route=%#v ok=%v", route, ok)
	}
}

func TestTurnStartCacheCanonicalizesExplicitDefaultBackend(t *testing.T) {
	omitted := protocol.Command{Type: "turn.start", CommandID: "command-1", RequestID: "request-1", ThreadID: "thread-1", Text: "开始"}
	explicit := omitted
	explicit.BackendID = "codex"
	_, omittedHash := legacyTurnStartCacheIdentity(omitted)
	_, explicitHash := legacyTurnStartCacheIdentity(explicit)
	if omittedHash != explicitHash {
		t.Fatalf("default backend hashes differ: omitted=%s explicit=%s", omittedHash, explicitHash)
	}
}

func TestTurnStartMigratesPreBackendSucceededCacheOnce(t *testing.T) {
	cache, err := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	preBackend := protocol.Command{Type: "turn.start", CommandID: "command-1", RequestID: "request-1", ThreadID: "thread-1", Text: "开始"}
	cacheID := "legacy-turn-start:command-1"
	raw, _ := json.Marshal(preBackend)
	digest := sha256.Sum256(raw)
	preBackendHash := hex.EncodeToString(digest[:])
	if _, execute, err := cache.Begin(cacheID, "turn.start", preBackendHash); err != nil || !execute {
		t.Fatalf("legacy begin execute=%v err=%v", execute, err)
	}
	if _, err := cache.Complete(cacheID, "", json.RawMessage(`{"turn":{"id":"turn-1"}}`)); err != nil {
		t.Fatal(err)
	}
	b := &bridge{commandCache: cache, routes: routes, state: bridgeState{HostID: "host-1"}}
	explicit := preBackend
	explicit.BackendID = "codex"
	result, outcome, err := b.executeTurnStartOnce(context.Background(), "phone-1", explicit, backend.NewFake("codex"), legacyTurnStartParams(explicit))
	if err != nil || outcome != turnRPCSucceeded || len(result) == 0 {
		t.Fatalf("migrated replay outcome=%q result=%s err=%v", outcome, result, err)
	}
	dsh := explicit
	dsh.BackendID = "dsh"
	if _, _, err := b.executeTurnStartOnce(context.Background(), "phone-1", dsh, backend.NewFake("dsh"), legacyTurnStartParams(dsh)); err == nil {
		t.Fatal("migrated turn.start cache was reusable by dsh")
	}
}

func TestLegacyTurnStartBackfillFailureIsPersistentUnknownAndNotReexecuted(t *testing.T) {
	dir := t.TempDir()
	cachePath := filepath.Join(dir, "commands.json")
	cache, err := commandcache.Open(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	appCalls := 0
	b := &bridge{
		backends: map[string]backend.Backend{"codex": appProcessReturningCounted(t, json.RawMessage(`{"turn":{}}`), &appCalls)}, backendOrder: []string{"codex"},
		commandCache: cache, routes: routes, state: bridgeState{HostID: "host-1"},
	}
	command := protocol.Command{Type: "turn.start", RequestID: "request-1", ThreadID: "thread-1", Text: "开始"}
	params := map[string]any{"threadId": command.ThreadID}
	if _, outcome, err := b.executeTurnStartOnce(context.Background(), "phone-1", command, b.backendFor("codex"), params); err == nil || outcome != turnRPCUnknown {
		t.Fatalf("first outcome=%q err=%v", outcome, err)
	}

	reopened, err := commandcache.Open(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	b.commandCache = reopened
	if _, outcome, err := b.executeTurnStartOnce(context.Background(), "phone-1", command, b.backendFor("codex"), params); err == nil || outcome != turnRPCUnknown {
		t.Fatalf("retry outcome=%q err=%v", outcome, err)
	}
	if appCalls != 1 {
		t.Fatalf("turn/start app-server calls=%d", appCalls)
	}
	cacheID, _ := legacyTurnStartCacheIdentity(command)
	record, ok := reopened.Lookup(cacheID)
	if !ok || record.Status != commandcache.StatusUnknown || len(record.ResultJSON) == 0 {
		t.Fatalf("persistent unknown record=%#v ok=%v", record, ok)
	}
}

func TestLegacyTurnStartRouteSaveFailureDoesNotReexecuteAppServer(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routeStateDir := filepath.Join(dir, "route-state")
	routes, err := runstate.OpenRoutes(filepath.Join(routeStateDir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	appCalls := 0
	injectRouteSaveFailure := func() {
		if err := os.RemoveAll(routeStateDir); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(routeStateDir, []byte("blocks route persistence"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	b := &bridge{
		backends: map[string]backend.Backend{"codex": appProcessReturningHooked(t, json.RawMessage(`{"turn":{"id":"turn-real"}}`), &appCalls, injectRouteSaveFailure)}, backendOrder: []string{"codex"},
		commandCache: cache, routes: routes, state: bridgeState{HostID: "host-1"},
	}
	command := protocol.Command{Type: "turn.start", RequestID: "request-1", ThreadID: "thread-1", Text: "开始"}
	params := map[string]any{"threadId": command.ThreadID}
	if _, outcome, err := b.executeTurnStartOnce(context.Background(), "phone-1", command, b.backendFor("codex"), params); err == nil || outcome != turnRPCUnknown {
		t.Fatalf("first outcome=%q err=%v", outcome, err)
	}
	if route, ok := routes.ByRun("legacy:thread-1"); !ok || route.TurnID != "" {
		t.Fatalf("failed route save leaked TurnID in memory: %#v ok=%v", route, ok)
	}
	if _, outcome, err := b.executeTurnStartOnce(context.Background(), "phone-1", command, b.backendFor("codex"), params); err == nil || outcome != turnRPCUnknown {
		t.Fatalf("retry outcome=%q err=%v", outcome, err)
	}
	if appCalls != 1 {
		t.Fatalf("turn/start app-server calls=%d", appCalls)
	}
}

func TestUnknownTurnStartResponseIsExplicitlyNotRetrySafe(t *testing.T) {
	payload := turnRPCResponsePayload(nil, turnRPCUnknown, errors.New("route persistence failed"))
	var decoded map[string]any
	if err := json.Unmarshal(payload, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["outcome"] != "UNKNOWN" || decoded["status"] != "RECONCILING" || decoded["retrySafe"] != false {
		t.Fatalf("unknown response=%s", payload)
	}
	if _, ordinaryError := decoded["error"]; ordinaryError {
		t.Fatalf("unknown outcome was encoded as ordinary retryable error: %s", payload)
	}
}

func TestPersistentUnknownTurnStartCanReconcileWithoutCallingAppServer(t *testing.T) {
	dir := t.TempDir()
	cachePath := filepath.Join(dir, "commands.json")
	cache, err := commandcache.Open(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{commandCache: cache, routes: routes, state: bridgeState{HostID: "host-1"}}
	command := protocol.Command{Type: "turn.start", RequestID: "request-1", ThreadID: "thread-1", Text: "开始"}
	if err := b.claimThread(command, "phone-1"); err != nil {
		t.Fatal(err)
	}
	cacheID, payloadHash := legacyTurnStartCacheIdentity(command)
	if _, execute, err := cache.Begin(cacheID, "turn.start", payloadHash); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	pending := mustJSON(turnStartReconciliation{
		Command: command, Result: json.RawMessage(`{"turn":{"id":"turn-real"}}`),
	})
	if _, err := cache.MarkUnknownWithResult(cacheID, errors.New("injected route persistence failure"), pending); err != nil {
		t.Fatal(err)
	}

	reopened, err := commandcache.Open(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	b.commandCache = reopened
	if err := b.recoverTurnStartRoutes(); err != nil {
		t.Fatal(err)
	}
	if route, ok := routes.ByThreadTurn("thread-1", "turn-real"); !ok || route.DeviceID != "phone-1" {
		t.Fatalf("reconciled route=%#v ok=%v", route, ok)
	}
	if record, ok := reopened.Lookup(cacheID); !ok || record.Status != commandcache.StatusSucceeded {
		t.Fatalf("reconciled command=%#v ok=%v", record, ok)
	}
}

func TestSnapshotLedgerMissDoesNotExposeLiveTerminalState(t *testing.T) {
	for _, status := range []string{"completed", "failed", "cancelled"} {
		t.Run(status, func(t *testing.T) {
			terminals, err := completion.OpenTerminalRunStore(filepath.Join(t.TempDir(), "terminal-runs.json"))
			if err != nil {
				t.Fatal(err)
			}
			app := appProcessReturning(t, json.RawMessage(fmt.Sprintf(
				`{"thread":{"turns":[{"id":"turn-1","status":{"type":%q}}]}}`, status,
			)))
			b := &bridge{backends: map[string]backend.Backend{"codex": app}, backendOrder: []string{"codex"}, terminals: terminals}
			snapshot, err := b.snapshotForRoute(context.Background(), runstate.Route{
				RunID: "run-1", ThreadID: "thread-1", TurnID: "turn-1",
			})
			if err != nil {
				t.Fatal(err)
			}
			if snapshot.Status != "RECONCILING" || len(snapshot.CompletionJSON) != 0 || snapshot.CompletedAt != 0 {
				t.Fatalf("ledger miss exposed live terminal state: %#v", snapshot)
			}
			if pending := terminals.PendingObservations(); len(pending) != 0 {
				t.Fatalf("snapshot rebuilt terminal evidence indirectly: %#v", pending)
			}
		})
	}
}

func TestCompleteRunTemporaryThreadReadFailureDoesNotFreezeFailedTerminal(t *testing.T) {
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(t.TempDir(), "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	client := appserverrpc.NewClient(strings.NewReader(""), writerFunc(func([]byte) (int, error) {
		return 0, errors.New("temporary app-server disconnect")
	}), "epoch-1")
	b := bridgeForTerminalTest(t, newTestBackend(client), terminals)
	b.completeRun(context.Background(), runstate.Route{
		RunID: "run-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1",
	}, json.RawMessage(`{"status":{"type":"completed"}}`))
	if record, frozen := terminals.Lookup("run-1"); frozen {
		t.Fatalf("temporary read failure froze terminal state: %#v", record)
	}
	if pending := terminals.PendingObservations(); len(pending) != 1 || pending[0].RunID != "run-1" {
		t.Fatalf("terminal observation was not retained for retry: %#v", pending)
	}
}

func TestCompleteRunMissingTargetTurnDoesNotFreezeFailedTerminal(t *testing.T) {
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(t.TempDir(), "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := bridgeForTerminalTest(
		t,
		appProcessReturning(t, json.RawMessage(`{"thread":{"turns":[{"id":"other-turn","status":{"type":"completed"}}]}}`)),
		terminals,
	)
	b.completeRun(context.Background(), runstate.Route{
		RunID: "run-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1",
	}, json.RawMessage(`{"status":{"type":"completed"}}`))
	if record, frozen := terminals.Lookup("run-1"); frozen {
		t.Fatalf("missing target turn froze terminal state: %#v", record)
	}
}

func TestUnknownCompletionStatusDoesNotFreezeCompletedTerminal(t *testing.T) {
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(t.TempDir(), "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := bridgeForTerminalTest(
		t,
		appProcessReturning(t, json.RawMessage(`{"thread":{"turns":[{"id":"turn-1","status":{"type":"futureStatus"}}]}}`)),
		terminals,
	)
	b.completeRun(context.Background(), runstate.Route{
		RunID: "run-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1",
	}, json.RawMessage(`{}`))
	if record, frozen := terminals.Lookup("run-1"); frozen {
		t.Fatalf("unknown status froze completed terminal: %#v", record)
	}
	if got := completionTurnStatus(json.RawMessage(`{}`), json.RawMessage(`{"type":"futureStatus"}`)); got != "unknown" {
		t.Fatalf("unknown status normalized as %q", got)
	}
}

func TestCompletionTurnStatusUsesExplicitTerminalWhitelist(t *testing.T) {
	tests := []struct {
		name     string
		params   json.RawMessage
		fallback json.RawMessage
		want     string
	}{
		{name: "completed", params: json.RawMessage(`{"status":{"type":"completed"}}`), want: "completed"},
		{name: "failed", params: json.RawMessage(`{"turn":{"status":"failed"}}`), want: "failed"},
		{name: "cancelled", fallback: json.RawMessage(`{"type":"interrupted"}`), want: "cancelled"},
		{name: "unknown", params: json.RawMessage(`{"status":"inProgress"}`), want: "unknown"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := completionTurnStatus(test.params, test.fallback); got != test.want {
				t.Fatalf("status = %q, want %q", got, test.want)
			}
		})
	}
}

func TestSnapshotForRouteUsesFrozenCompletionWithoutAppServer(t *testing.T) {
	dir := t.TempDir()
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	frozenJSON := json.RawMessage(`{"schemaVersion":2,"completionId":"completion-1","summary":"frozen"}`)
	record, _, err := terminals.Freeze(completion.TerminalRunRecord{
		RunID: "run-1", Status: "COMPLETED", CompletionJSON: frozenJSON, CompletedAt: 1234,
	})
	if err != nil {
		t.Fatal(err)
	}
	workspaceDir := filepath.Join(dir, "workspace")
	if err := os.MkdirAll(workspaceDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(workspaceDir, "mutated-after-completion.txt"), []byte("later"), 0o600); err != nil {
		t.Fatal(err)
	}
	b := &bridge{terminals: terminals}
	snapshot, err := b.snapshotForRoute(context.Background(), runstate.Route{
		RunID: "run-1", ThreadID: "thread-1", TurnID: "turn-1",
		BaselineJSON: string(mustJSON(runstate.WorkspaceBaseline{CWD: workspaceDir})),
	})
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Status != "COMPLETED" || string(snapshot.CompletionJSON) != string(frozenJSON) || snapshot.CompletedAt != record.CompletedAt {
		t.Fatalf("snapshot = %#v", snapshot)
	}
}

func TestRecoverTerminalRunsBackfillsMissingJournalEvent(t *testing.T) {
	dir := t.TempDir()
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := terminals.Freeze(completion.TerminalRunRecord{
		RunID: "run-1", Status: "COMPLETED", CompletionJSON: json.RawMessage(`{"schemaVersion":2,"summary":"frozen"}`), CompletedAt: 1234,
	}); err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{RunID: "run-1", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1"}); err != nil {
		t.Fatal(err)
	}
	b := bridgeForTerminalTest(t, nil, terminals)
	b.routes = routes
	if err := b.recoverTerminalRuns(context.Background(), "phone-1"); err != nil {
		t.Fatal(err)
	}
	pending := b.journal.Pending("host-1", "phone-1")
	if len(pending) != 1 || pending[0].Type != "run.completed" || pending[0].RunID != "run-1" {
		t.Fatalf("journal backfill=%#v", pending)
	}
	if len(terminals.PendingJournalRecords()) != 0 {
		t.Fatalf("journaled terminal remained pending")
	}
}

func TestRecoverTerminalRunsRetriesPersistentObservation(t *testing.T) {
	dir := t.TempDir()
	terminals, err := completion.OpenTerminalRunStore(filepath.Join(dir, "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	params := json.RawMessage(`{"threadId":"thread-1","turnId":"turn-1","status":{"type":"completed"}}`)
	if err := terminals.Observe(completion.TerminalObservation{RunID: "run-1", Params: params, ObservedAt: 1234}); err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	route := runstate.Route{RunID: "run-1", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1"}
	if err := routes.Put(route); err != nil {
		t.Fatal(err)
	}
	app := appProcessReturning(t, json.RawMessage(`{"thread":{"turns":[{"id":"turn-1","status":{"type":"completed"},"items":[]}]}}`))
	b := bridgeForTerminalTest(t, app, terminals)
	b.routes = routes
	if err := b.recoverTerminalRuns(context.Background(), "phone-1"); err != nil {
		t.Fatal(err)
	}
	if record, ok := terminals.Lookup("run-1"); !ok || record.Status != "COMPLETED" {
		t.Fatalf("recovered terminal=%#v ok=%v", record, ok)
	}
	if len(terminals.PendingObservations()) != 0 || len(terminals.PendingJournalRecords()) != 0 {
		t.Fatalf("recovery did not drain durable reconciliation state")
	}
}

func TestBuildCompletionEvidenceMarksWorkspaceCaptureFailureUnverified(t *testing.T) {
	route := runstate.Route{
		RunID: "run-1", WorkspaceID: "workspace-1",
		BaselineJSON: string(mustJSON(runstate.WorkspaceBaseline{
			CWD: filepath.Join(t.TempDir(), "missing"), IsGit: true, Head: "before", Branch: "main",
		})),
	}
	evidence := buildCompletionEvidence(route, completedTurn{})
	if evidence.Git == nil || evidence.Git.State != completion.GitUnverified || evidence.Git.Reason == "" {
		t.Fatalf("git evidence=%#v", evidence.Git)
	}
}

type writerFunc func([]byte) (int, error)

func (function writerFunc) Write(raw []byte) (int, error) { return function(raw) }

type appServerTestRequest struct {
	ID     json.RawMessage
	Method string
	Params map[string]any
}

func appProcessScripted(
	t *testing.T,
	respond func(appServerTestRequest) (json.RawMessage, json.RawMessage),
) *testBackend {
	t.Helper()
	reader, serverWriter := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	client := appserverrpc.NewClient(reader, writerFunc(func(requestRaw []byte) (int, error) {
		var request appServerTestRequest
		if err := json.Unmarshal(requestRaw, &request); err != nil {
			return 0, err
		}
		result, responseError := respond(request)
		var response string
		if len(responseError) > 0 {
			response = fmt.Sprintf(`{"id":%s,"error":%s}`+"\n", request.ID, responseError)
		} else {
			response = fmt.Sprintf(`{"id":%s,"result":%s}`+"\n", request.ID, result)
		}
		if _, err := serverWriter.Write([]byte(response)); err != nil {
			return 0, err
		}
		return len(requestRaw), nil
	}), "epoch-1")
	client.Start(ctx)
	t.Cleanup(func() {
		cancel()
		_ = serverWriter.Close()
		_ = reader.Close()
	})
	return newTestBackend(client)
}

func appProcessReturning(t *testing.T, result json.RawMessage) *testBackend {
	return appProcessReturningCounted(t, result, nil)
}

func appProcessReturningCounted(t *testing.T, result json.RawMessage, calls *int) *testBackend {
	return appProcessReturningHooked(t, result, calls, nil)
}

func appProcessReturningHooked(t *testing.T, result json.RawMessage, calls *int, beforeResponse func()) *testBackend {
	t.Helper()
	reader, serverWriter := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	client := appserverrpc.NewClient(reader, writerFunc(func(requestRaw []byte) (int, error) {
		if calls != nil {
			*calls++
		}
		if beforeResponse != nil {
			beforeResponse()
		}
		var request struct {
			ID json.RawMessage `json:"id"`
		}
		if err := json.Unmarshal(requestRaw, &request); err != nil {
			return 0, err
		}
		response := fmt.Sprintf(`{"id":%s,"result":%s}`+"\n", request.ID, result)
		if _, err := serverWriter.Write([]byte(response)); err != nil {
			return 0, err
		}
		return len(requestRaw), nil
	}), "epoch-1")
	client.Start(ctx)
	t.Cleanup(func() {
		cancel()
		_ = serverWriter.Close()
		_ = reader.Close()
	})
	return newTestBackend(client)
}

func bridgeForTerminalTest(t *testing.T, app *testBackend, terminals *completion.TerminalRunStore) *bridge {
	t.Helper()
	dir := t.TempDir()
	store, err := journal.Open(filepath.Join(dir, "logical-events.log"), bytes.Repeat([]byte{0x41}, 32), 100)
	if err != nil {
		t.Fatal(err)
	}
	return &bridge{
		backends: map[string]backend.Backend{"codex": app}, backendOrder: []string{"codex"},
		terminals: terminals, journal: store, path: filepath.Join(dir, "state.json"),
		state: bridgeState{
			HostID: "host-1", DeviceSecrets: map[string]string{}, Sequences: map[string]uint64{},
			PendingOutbound: map[string]map[string]string{},
		},
	}
}

// testBackend adapts a scripted appserverrpc.Client to the backend.Backend
// interface used by the bridge, keeping existing scripted-response tests
// intact after the M4 backend abstraction.
type testBackend struct {
	client   *appserverrpc.Client
	messages chan backend.Message
}

func newTestBackend(client *appserverrpc.Client) *testBackend {
	b := &testBackend{client: client, messages: make(chan backend.Message, 64)}
	client.SetNotificationHandler(func(message appserverrpc.Message) {
		b.messages <- backend.Message{BackendID: "codex", ID: message.ID, Method: message.Method, Params: message.Params}
	})
	return b
}

func (b *testBackend) ID() string             { return "codex" }
func (b *testBackend) Name() string           { return "Codex" }
func (b *testBackend) Capabilities() []string { return backend.CodexCapabilities() }
func (b *testBackend) ProcessEpoch() string   { return b.client.ProcessEpoch() }

func (b *testBackend) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	return b.client.Call(ctx, method, params)
}

func (b *testBackend) Notify(ctx context.Context, method string, params any) error {
	return b.client.Notify(method, params)
}

func (b *testBackend) Respond(ctx context.Context, ref backend.ServerRequestRef, result any) error {
	return b.client.Respond(appserverrpc.ServerRequestRef{
		ID: ref.ID, Method: ref.Method, Params: ref.Params, ProcessEpoch: ref.ProcessEpoch,
	}, result)
}

func (b *testBackend) Start(ctx context.Context)        {}
func (b *testBackend) Messages() <-chan backend.Message { return b.messages }
func (b *testBackend) Done() <-chan error               { return b.client.Done() }
func (b *testBackend) Close() error                     { return nil }

func TestExecuteCommandRoutesByBackendID(t *testing.T) {
	codexCalls := make(chan string, 4)
	dshCalls := make(chan string, 4)
	codex := backend.NewFake("codex").OnScript("thread/list", func(method string, params any) (json.RawMessage, error) {
		codexCalls <- method
		return json.RawMessage(`{"data":[]}`), nil
	})
	dsh := backend.NewFake("dsh").OnScript("thread/list", func(method string, params any) (json.RawMessage, error) {
		dshCalls <- method
		return json.RawMessage(`{"data":[]}`), nil
	})
	b := &bridge{
		backends:     map[string]backend.Backend{"codex": codex, "dsh": dsh},
		backendOrder: []string{"codex", "dsh"},
		state: bridgeState{
			Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{},
		},
	}
	// A dsh command must reach only the dsh backend.
	if err := b.executeCommand(context.Background(), "phone-1", protocol.Command{
		Type: "thread.list", RequestID: "r-dsh", BackendID: "dsh",
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case method := <-dshCalls:
		if method != "thread/list" {
			t.Fatalf("dsh method = %s", method)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("dsh backend was not called")
	}
	select {
	case <-codexCalls:
		t.Fatal("codex backend was called for a dsh command")
	default:
	}
	// An empty backend id defaults to codex.
	if err := b.executeCommand(context.Background(), "phone-1", protocol.Command{
		Type: "thread.list", RequestID: "r-codex",
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case method := <-codexCalls:
		if method != "thread/list" {
			t.Fatalf("codex method = %s", method)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("codex backend was not called")
	}
}

func TestExecuteCommandUnknownBackendIsRejectedWithoutCallingOthers(t *testing.T) {
	called := make(chan struct{}, 1)
	codex := backend.NewFake("codex").OnScript("thread.list", func(method string, params any) (json.RawMessage, error) {
		called <- struct{}{}
		return json.RawMessage(`{"data":[]}`), nil
	})
	b := &bridge{
		backends: map[string]backend.Backend{"codex": codex},
		state: bridgeState{
			Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{},
		},
	}
	err := b.executeCommand(context.Background(), "phone-1", protocol.Command{
		Type: "thread.list", RequestID: "r-aux", BackendID: "aux",
	})
	if err == nil {
		t.Fatal("unknown backend command must fail")
	}
	select {
	case <-called:
		t.Fatal("a registered backend was called for an unknown backend id")
	default:
	}
}

func TestExecuteCommandRejectsRawRPCWithoutCallingBackend(t *testing.T) {
	raw := backend.NewFake("codex")
	secret := bytes.Repeat([]byte{7}, 32)
	b := &bridge{
		backends:     map[string]backend.Backend{"codex": raw},
		backendOrder: []string{"codex"},
		path:         filepath.Join(t.TempDir(), "state.json"),
		state: bridgeState{
			Sequences:       map[string]uint64{},
			PendingOutbound: map[string]map[string]string{},
			DeviceSecrets:   map[string]string{"phone-1": protocol.EncodeSecret(secret)},
		},
	}
	if err := b.executeCommand(context.Background(), "phone-1", protocol.Command{
		Type: "rpc", RequestID: "request-rpc-1", BackendID: "codex", Method: "thread/read",
	}); err != nil {
		t.Fatal(err)
	}
	if calls := raw.Calls(); len(calls) != 0 {
		t.Fatalf("raw backend was called: %#v", calls)
	}
	pending := b.state.PendingOutbound["phone-1"]
	if len(pending) != 1 {
		t.Fatalf("pending outbound = %d entries, want 1", len(pending))
	}
	for _, rawWire := range pending {
		var wire protocol.WireMessage
		if err := json.Unmarshal([]byte(rawWire), &wire); err != nil {
			t.Fatal(err)
		}
		var event protocol.Event
		if err := protocol.Decrypt(secret, wire, &event); err != nil {
			t.Fatal(err)
		}
		if event.Type != "error" || event.RequestID != "request-rpc-1" {
			t.Fatalf("event = %#v", event)
		}
		var payload struct {
			Code       string `json:"code"`
			LatestLine string `json:"latestLine"`
		}
		if err := json.Unmarshal(event.Payload, &payload); err != nil {
			t.Fatal(err)
		}
		if payload.Code != "RAW_RPC_DISABLED" {
			t.Fatalf("payload = %#v, want RAW_RPC_DISABLED", payload)
		}
	}
}

func TestStartRunInjectsAppServerAdapterTypedSlice(t *testing.T) {
	dir := t.TempDir()
	cache, err := commandcache.Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	routes, err := runstate.OpenRoutes(filepath.Join(dir, "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	store, err := journal.Open(filepath.Join(dir, "logical-events.log"), bytes.Repeat([]byte{0x41}, 32), 100)
	if err != nil {
		t.Fatal(err)
	}
	secret := bytes.Repeat([]byte{5}, 32)
	cwd := t.TempDir()
	candidate, err := workspace.Inspect(secret, cwd, time.Now().UnixMilli())
	if err != nil {
		t.Fatal(err)
	}
	registry, err := workspace.OpenRegistry(filepath.Join(dir, "workspaces.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := registry.PutCandidates("device-1", []workspace.Candidate{candidate}); err != nil {
		t.Fatal(err)
	}
	raw := backend.NewFake("dsh").
		OnScript("thread/list", func(string, any) (json.RawMessage, error) {
			return json.RawMessage(`{"data":[]}`), nil
		}).
		OnScript("thread/start", func(string, any) (json.RawMessage, error) {
			return json.RawMessage(`{"thread":{"id":"thread-1"}}`), nil
		}).
		OnScript("turn/start", func(method string, params any) (json.RawMessage, error) {
			rawParams, _ := json.Marshal(params)
			var want struct {
				ThreadID            string         `json:"threadId"`
				ClientUserMessageID string         `json:"clientUserMessageId"`
				OutputSchema        map[string]any `json:"outputSchema"`
			}
			_ = json.Unmarshal(rawParams, &want)
			if want.ThreadID != "thread-1" || want.ClientUserMessageID != "command-run-1" || want.OutputSchema == nil {
				return nil, fmt.Errorf("unexpected turn/start params: %s", rawParams)
			}
			return json.RawMessage(`{"turn":{"id":"turn-1"}}`), nil
		})
	b := &bridge{
		backends: map[string]backend.Backend{"dsh": raw}, backendOrder: []string{"dsh"},
		commandCache: cache, routes: routes, journal: store,
		workspaces: registry,
		path:       filepath.Join(dir, "state.json"),
		state: bridgeState{
			HostID: "host-1", Sequences: map[string]uint64{},
			PendingOutbound: map[string]map[string]string{},
			DeviceSecrets:   map[string]string{"device-1": protocol.EncodeSecret(secret)},
		},
	}
	command := protocol.Command{
		Type: "run.start", CommandID: "command-run-1", RequestID: "request-run-1", RunID: "run-1",
		BindingID: "binding-1", WorkspaceID: candidate.WorkspaceID, BackendID: "dsh",
		RepositoryFingerprint: candidate.RepositoryFingerprint, Objective: "完成typed slice验证",
	}
	if err := b.executeCommand(context.Background(), "device-1", command); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		record, ok := cache.Lookup(command.CommandID)
		if ok && record.Status == commandcache.StatusSucceeded {
			break
		}
		if time.Now().After(deadline) {
			record, _ := cache.Lookup(command.CommandID)
			t.Fatalf("run.start did not succeed: record=%#v calls=%#v", record, raw.Calls())
		}
		time.Sleep(10 * time.Millisecond)
	}
	calls := raw.Calls()
	methods := []string{}
	for _, call := range calls {
		methods = append(methods, call.Method)
	}
	if !reflect.DeepEqual(methods, []string{"thread/list", "thread/start", "turn/start"}) {
		t.Fatalf("raw methods = %v", methods)
	}
	var started *protocol.LogicalEvent
	for _, event := range store.Pending("host-1", "device-1") {
		if event.Type == "run.started" {
			copy := event
			started = &copy
		}
	}
	if started == nil {
		t.Fatalf("run.started was not journaled: %#v", store.Pending("host-1", "device-1"))
	}
	var payload struct {
		CommandID  string `json:"commandId"`
		RunID      string `json:"runId"`
		ThreadID   string `json:"threadId"`
		TurnID     string `json:"turnId"`
		LatestLine string `json:"latestLine"`
	}
	if err := json.Unmarshal(started.Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload.CommandID != "command-run-1" || payload.RunID != "run-1" ||
		payload.ThreadID != "thread-1" || payload.TurnID != "turn-1" || payload.LatestLine != "Mac 已接收任务" {
		t.Fatalf("run.started payload = %#v", payload)
	}
	route, ok := routes.ByRun("run-1")
	if !ok || route.ThreadID != "thread-1" || route.TurnID != "turn-1" || route.BackendID != "dsh" {
		t.Fatalf("route = %#v ok=%v", route, ok)
	}
}

func TestRouteForParamsScopesEventsToOwningBackend(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-codex", HostID: "host-a", DeviceID: "phone-a", ThreadID: "thread-codex", BackendID: "codex",
	}); err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{
		RunID: "run-dsh", HostID: "host-a", DeviceID: "phone-b", ThreadID: "thread-dsh", BackendID: "dsh",
	}); err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes}

	route, ok := b.routeForParams("dsh", json.RawMessage(`{"threadId":"thread-dsh"}`))
	if !ok || route.RunID != "run-dsh" {
		t.Fatalf("dsh route = %#v ok=%v", route, ok)
	}
	// A dsh event for a codex-owned thread must not route anywhere.
	if _, ok := b.routeForParams("dsh", json.RawMessage(`{"threadId":"thread-codex"}`)); ok {
		t.Fatal("dsh event reached a codex-owned thread")
	}
	if _, ok := b.routeForParams("codex", json.RawMessage(`{"threadId":"thread-dsh"}`)); ok {
		t.Fatal("codex event reached a dsh-owned thread")
	}
}

func TestPendingReplayDropsStaleHostStatusAndSortsOrdinaryEvents(t *testing.T) {
	secret := bytes.Repeat([]byte{7}, 32)
	deviceID := "phone-1"
	pending := map[string]string{}
	add := func(sequence uint64, eventType string) {
		wire, err := protocol.Encrypt(secret, protocol.WireMessage{
			HostID: "host-1", DeviceID: deviceID, Sequence: sequence,
		}, protocol.Event{Type: eventType})
		if err != nil {
			t.Fatal(err)
		}
		raw, _ := json.Marshal(wire)
		pending[wire.MessageID] = string(raw)
	}
	add(3, "rpc.response")
	add(1, "host.status")
	add(2, "run.started")
	b := &bridge{state: bridgeState{
		DeviceSecrets:   map[string]string{deviceID: protocol.EncodeSecret(secret)},
		PendingOutbound: map[string]map[string]string{deviceID: pending},
	}}

	b.mu.Lock()
	wires := b.pendingReplayWiresLocked(time.Now().UnixMilli())
	b.mu.Unlock()

	if len(wires) != 2 || wires[0].Sequence != 2 || wires[1].Sequence != 3 {
		t.Fatalf("replayed wires=%#v", wires)
	}
	if len(b.state.PendingOutbound[deviceID]) != 2 {
		t.Fatalf("stale status was not coalesced: %#v", b.state.PendingOutbound[deviceID])
	}
}

func TestInitialBackendRosterWaitsForAllBackendsBeforeFirstBroadcast(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ready := make(chan backend.Backend, 2)
	broadcasts := make(chan struct{}, 2)
	go awaitInitialBackendRoster(ctx, ready, 2, time.Second, func() { broadcasts <- struct{}{} })

	ready <- backend.NewFake("codex")
	select {
	case <-broadcasts:
		t.Fatal("partial startup roster was broadcast before dsh initialized")
	case <-time.After(30 * time.Millisecond):
	}
	ready <- backend.NewFake("dsh")
	select {
	case <-broadcasts:
	case <-time.After(time.Second):
		t.Fatal("full startup roster was not broadcast")
	}
}

func TestSuperviseBackendRestartsCrashedBackendAndLeavesOthersAlive(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	b := &bridge{routes: routes, backendBackoff: 30 * time.Millisecond}
	var mu sync.Mutex
	var codexInstances []*backend.Fake
	dsh := backend.NewFake("dsh")
	factory := func(spec backend.Spec) (backend.Backend, error) {
		f := backend.NewFake(spec.ID)
		mu.Lock()
		codexInstances = append(codexInstances, f)
		mu.Unlock()
		return f, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ready := make(chan backend.Backend, 2)
	go b.superviseBackend(ctx, backend.Spec{ID: "codex"}, ready, factory)
	go b.superviseBackend(ctx, backend.Spec{ID: "dsh"}, ready, func(spec backend.Spec) (backend.Backend, error) {
		return dsh, nil
	})
	firstArrival := <-ready
	secondArrival := <-ready
	var codexFirst backend.Backend
	if firstArrival.ID() == "codex" {
		codexFirst = firstArrival
	} else {
		codexFirst = secondArrival
	}

	// Crash the codex backend; the dsh backend must keep serving and codex
	// must be restarted and re-registered.
	codexFirst.(*backend.Fake).Crash(errors.New("boom"))
	deadline := time.After(5 * time.Second)
	for {
		mu.Lock()
		count := len(codexInstances)
		mu.Unlock()
		if count >= 2 {
			break
		}
		select {
		case <-deadline:
			t.Fatalf("codex backend was not restarted (instances=%d)", count)
		default:
			time.Sleep(20 * time.Millisecond)
		}
	}
	if got := b.backendFor("codex"); got == nil || got == codexFirst {
		t.Fatal("restarted codex backend is not the active one")
	}
	if got := b.backendFor("dsh"); got != dsh {
		t.Fatal("dsh backend was disturbed by the codex crash")
	}
	// The restarted backend can serve a command.
	if _, err := b.backendFor("codex").Call(context.Background(), "thread/list", map[string]any{}); err != nil {
		t.Fatalf("restarted backend call failed: %v", err)
	}
}

func TestParseBackendSpecsKnownDSH(t *testing.T) {
	specs, err := parseBackendSpecs([]string{"codex", "dsh"}, "codex-exec")
	if err != nil {
		t.Fatal(err)
	}
	if len(specs) != 2 {
		t.Fatalf("specs = %#v", specs)
	}
	codex := specs[0]
	if codex.ID != "codex" || codex.Exec != "codex-exec" || codex.Name != "Codex" {
		t.Fatalf("codex spec = %#v", codex)
	}
	dsh := specs[1]
	if dsh.ID != "dsh" || dsh.Name != "DeepSeek Harness" || dsh.Exec != "dsh" {
		t.Fatalf("dsh spec = %#v", dsh)
	}
	if len(dsh.Args) != 4 || dsh.Args[0] != "--profile" || dsh.Args[1] != "appserver" ||
		dsh.Args[2] != "--listen" || dsh.Args[3] != "stdio://" {
		t.Fatalf("dsh args = %#v", dsh.Args)
	}
	hasApprovals := false
	for _, capability := range dsh.Capabilities {
		if capability == "approvals.v1" || capability == "user-input.v1" {
			hasApprovals = true
		}
	}
	if hasApprovals {
		t.Fatalf("dsh must not advertise approval capabilities: %#v", dsh.Capabilities)
	}
	if len(codex.Capabilities) != len(dsh.Capabilities)+2 {
		t.Fatalf("codex caps = %d, dsh caps = %d", len(codex.Capabilities), len(dsh.Capabilities))
	}
}

func TestParseBackendSpecsCustomExecutableAndDefaults(t *testing.T) {
	specs, err := parseBackendSpecs(nil, "codex-exec")
	if err != nil {
		t.Fatal(err)
	}
	if len(specs) != 1 || specs[0].ID != "codex" || specs[0].Exec != "codex-exec" {
		t.Fatalf("default specs = %#v", specs)
	}
	specs, err = parseBackendSpecs([]string{"aux=/opt/tools/appserver"}, "codex-exec")
	if err != nil {
		t.Fatal(err)
	}
	if len(specs) != 1 || specs[0].ID != "aux" || specs[0].Exec != "/opt/tools/appserver" {
		t.Fatalf("custom specs = %#v", specs)
	}
	if _, err := parseBackendSpecs([]string{"nope"}, "codex-exec"); err == nil {
		t.Fatal("unknown bare backend id must be rejected")
	}
	if _, err := parseBackendSpecs([]string{"codex", "codex"}, "codex-exec"); err == nil {
		t.Fatal("duplicate backend ids must be rejected")
	}
}

func TestRunSnapshotPayloadCarriesBackendIDPerRunAndApproval(t *testing.T) {
	payload := runSnapshotPayload(
		"host-1", "device-1", 7, "epoch-1",
		[]runSnapshot{
			{RunID: "run-codex", BackendID: "codex", Status: "RUNNING", LatestLine: "正在运行"},
			{RunID: "run-dsh", BackendID: "dsh", Status: "RUNNING", LatestLine: "正在运行"},
		},
		[]map[string]any{
			{"approvalId": "approval-1", "runId": "run-codex", "backendId": "codex", "status": "PENDING"},
		},
	)
	var decoded struct {
		Runs []struct {
			RunID     string `json:"runId"`
			BackendID string `json:"backendId"`
		} `json:"runs"`
		Approvals []struct {
			BackendID string `json:"backendId"`
		} `json:"approvals"`
	}
	if err := json.Unmarshal(payload, &decoded); err != nil {
		t.Fatal(err)
	}
	if len(decoded.Runs) != 2 || decoded.Runs[0].BackendID != "codex" || decoded.Runs[1].BackendID != "dsh" {
		t.Fatalf("runs = %#v", decoded.Runs)
	}
	if len(decoded.Approvals) != 1 || decoded.Approvals[0].BackendID != "codex" {
		t.Fatalf("approvals = %#v", decoded.Approvals)
	}
}

func TestSnapshotForRouteReconcilesWhenBackendUnavailable(t *testing.T) {
	codex := backend.NewFake("codex").OnScript("thread/read", func(method string, params any) (json.RawMessage, error) {
		return json.RawMessage(`{"thread":{"turns":[{"id":"turn-1","status":{"type":"inProgress"}}]}}`), nil
	})
	b := &bridge{backends: map[string]backend.Backend{"codex": codex}}
	// The dsh backend crashed: its run must reconcile instead of erroring out.
	snapshot, err := b.snapshotForRoute(context.Background(), runstate.Route{
		RunID: "run-dsh", BackendID: "dsh", ThreadID: "thread-dsh", TurnID: "turn-1",
	})
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Status != "RECONCILING" || snapshot.LatestLine != "正在与 Mac 对账" {
		t.Fatalf("snapshot = %#v", snapshot)
	}
	if snapshot.BackendID != "dsh" {
		t.Fatalf("snapshot backendId = %q", snapshot.BackendID)
	}
	// The codex backend still answers: its run stays live.
	snapshot, err = b.snapshotForRoute(context.Background(), runstate.Route{
		RunID: "run-codex", BackendID: "codex", ThreadID: "thread-codex", TurnID: "turn-1",
	})
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Status != "RUNNING" || snapshot.LatestLine != "任务正在 Mac 上运行" {
		t.Fatalf("codex snapshot = %#v", snapshot)
	}
}

func waitForRecoveryWorker(t *testing.T, b *bridge) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for {
		b.recoveryWorkMu.Lock()
		running := b.recoveryWorkerRunning
		b.recoveryWorkMu.Unlock()
		if !running {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("recovery worker did not become idle")
		}
		time.Sleep(time.Millisecond)
	}
}

func TestRunSnapshotPayloadLegacyBackendDefaults(t *testing.T) {
	payload := runSnapshotPayload("host-1", "device-1", 0, "", nil, nil)
	if !bytes.Contains(payload, []byte(`"runs":null`)) && !bytes.Contains(payload, []byte(`"runs":[]`)) {
		t.Fatalf("empty runs payload = %s", payload)
	}
}
