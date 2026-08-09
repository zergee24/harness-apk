package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	appserverrpc "github.com/harnessapk/remote/internal/appserver"
	"github.com/harnessapk/remote/internal/commandcache"
	"github.com/harnessapk/remote/internal/completion"
	"github.com/harnessapk/remote/internal/journal"
	"github.com/harnessapk/remote/internal/protocol"
	runstate "github.com/harnessapk/remote/internal/run"
)

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
	payload := approvalLogicalPayload(appserverrpc.Message{
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

	if got := bridge.eventTargets(params); !reflect.DeepEqual(got, []string{"phone-a"}) {
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

	if got := bridge.eventTargets(json.RawMessage(`{"threadId":"thread-b"}`)); len(got) != 0 {
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

	completionRoute, ok := b.routeForParams(json.RawMessage(`{"threadId":"thread-shared","turn":{"id":"turn-b"}}`))
	if !ok || completionRoute.RunID != "run-b" || completionRoute.BindingID != "project-b" {
		t.Fatalf("completion route = %#v ok=%v", completionRoute, ok)
	}
	approvalRoute, ok := b.routeForParams(json.RawMessage(`{"threadId":"thread-shared","turnId":"turn-a","itemId":"approval-a"}`))
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
	if route, ok := b.routeForParams(json.RawMessage(`{"threadId":"thread-shared"}`)); ok {
		t.Fatalf("ambiguous thread event routed to %#v", route)
	}
	if _, _, err := terminals.Freeze(completion.TerminalRunRecord{
		RunID: "run-a", Status: "COMPLETED", CompletionJSON: json.RawMessage(`{"schemaVersion":2}`), CompletedAt: 1,
	}); err != nil {
		t.Fatal(err)
	}
	route, ok := b.routeForParams(json.RawMessage(`{"threadId":"thread-shared"}`))
	if !ok || route.RunID != "run-b" {
		t.Fatalf("unique active route = %#v ok=%v", route, ok)
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
	if route, ok := b.routeForParams(json.RawMessage(`{"threadId":"thread-shared","turnId":"unknown-turn"}`)); ok {
		t.Fatalf("unknown exact turn fell back to legacy route: %#v", route)
	}
}

func TestSnapshotLedgerMissDoesNotExposeLiveTerminalState(t *testing.T) {
	for _, status := range []string{"completed", "failed", "cancelled"} {
		t.Run(status, func(t *testing.T) {
			app := appProcessReturning(t, json.RawMessage(fmt.Sprintf(
				`{"thread":{"turns":[{"id":"turn-1","status":{"type":%q}}]}}`, status,
			)))
			b := &bridge{app: app}
			snapshot, err := b.snapshotForRoute(context.Background(), runstate.Route{
				RunID: "run-1", ThreadID: "thread-1", TurnID: "turn-1",
			})
			if err != nil {
				t.Fatal(err)
			}
			if snapshot.Status != "RECONCILING" || len(snapshot.CompletionJSON) != 0 || snapshot.CompletedAt != 0 {
				t.Fatalf("ledger miss exposed live terminal state: %#v", snapshot)
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
	b := bridgeForTerminalTest(t, &appProcess{client: client}, terminals)
	b.completeRun(context.Background(), runstate.Route{
		RunID: "run-1", DeviceID: "phone-1", ThreadID: "thread-1", TurnID: "turn-1",
	}, json.RawMessage(`{"status":{"type":"completed"}}`))
	if record, frozen := terminals.Lookup("run-1"); frozen {
		t.Fatalf("temporary read failure froze terminal state: %#v", record)
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

type writerFunc func([]byte) (int, error)

func (function writerFunc) Write(raw []byte) (int, error) { return function(raw) }

func appProcessReturning(t *testing.T, result json.RawMessage) *appProcess {
	t.Helper()
	reader, serverWriter := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	client := appserverrpc.NewClient(reader, writerFunc(func(requestRaw []byte) (int, error) {
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
	return &appProcess{client: client}
}

func bridgeForTerminalTest(t *testing.T, app *appProcess, terminals *completion.TerminalRunStore) *bridge {
	t.Helper()
	dir := t.TempDir()
	store, err := journal.Open(filepath.Join(dir, "logical-events.log"), bytes.Repeat([]byte{0x41}, 32), 100)
	if err != nil {
		t.Fatal(err)
	}
	return &bridge{
		app: app, terminals: terminals, journal: store, path: filepath.Join(dir, "state.json"),
		state: bridgeState{
			HostID: "host-1", DeviceSecrets: map[string]string{}, Sequences: map[string]uint64{},
			PendingOutbound: map[string]map[string]string{},
		},
	}
}
