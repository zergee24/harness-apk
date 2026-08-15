package run

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestServerRequestResolvedMarksPendingApprovalStale(t *testing.T) {
	store, err := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	requestID := json.RawMessage(`{"request":"approval-1"}`)
	if err := store.PutApproval(Approval{
		ApprovalID: "approval-1", RunID: "run-1", ProcessEpoch: "epoch-1",
		ServerRequestID: requestID, Status: ApprovalPending,
	}); err != nil {
		t.Fatal(err)
	}

	approval, changed, err := store.MarkServerRequestResolved("", "epoch-1", requestID)
	if err != nil {
		t.Fatal(err)
	}
	if !changed || approval.Status != ApprovalStale {
		t.Fatalf("approval=%#v changed=%v", approval, changed)
	}
}

func TestRouteSurvivesWebSocketReconnectAndBridgeStateReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "routes.json")
	store, err := OpenRoutes(path)
	if err != nil {
		t.Fatal(err)
	}
	route := Route{
		RunID: "run-1", BindingID: "binding-1", WorkspaceID: "workspace-1",
		HostID: "host-1", DeviceID: "device-1", ThreadID: "thread-1", TurnID: "turn-1",
	}
	if err := store.Put(route); err != nil {
		t.Fatal(err)
	}

	reconnected, err := OpenRoutes(path)
	if err != nil {
		t.Fatal(err)
	}
	loaded, ok := reconnected.ByThread("thread-1")
	route.BackendID = "codex" // legacy empty backend id normalizes to codex
	if !ok || loaded != route {
		t.Fatalf("loaded route=%#v ok=%v", loaded, ok)
	}
}

func TestNewProcessEpochInvalidatesOldServerRequestIDs(t *testing.T) {
	store, err := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	requestID := json.RawMessage(`7`)
	if err := store.BeginProcessEpoch("codex", "epoch-1"); err != nil {
		t.Fatal(err)
	}
	if err := store.PutApproval(Approval{
		ApprovalID: "approval-1", RunID: "run-1", ProcessEpoch: "epoch-1",
		ServerRequestID: requestID, Status: ApprovalPending,
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.BeginProcessEpoch("codex", "epoch-2"); err != nil {
		t.Fatal(err)
	}

	approval, _ := store.Approval("approval-1")
	if approval.Status != ApprovalStale {
		t.Fatalf("old approval status = %s", approval.Status)
	}
	if err := store.ValidateResponse("approval-1", "epoch-1", requestID); err == nil {
		t.Fatal("old process epoch server request was accepted")
	}
}

func TestUpdateTurnAtomicallyBackfillsLegacyRoute(t *testing.T) {
	path := filepath.Join(t.TempDir(), "routes.json")
	store, err := OpenRoutes(path)
	if err != nil {
		t.Fatal(err)
	}
	route := Route{
		RunID: "legacy:thread-1", BindingID: "binding-1", WorkspaceID: "workspace-1",
		HostID: "host-1", DeviceID: "device-1", ThreadID: "thread-1", BaselineJSON: `{"cwd":"/workspace"}`,
	}
	if err := store.Put(route); err != nil {
		t.Fatal(err)
	}
	if err := store.UpdateTurn(route.RunID, route.ThreadID, "turn-real"); err != nil {
		t.Fatal(err)
	}
	reopened, err := OpenRoutes(path)
	if err != nil {
		t.Fatal(err)
	}
	updated, ok := reopened.ByThreadTurn("thread-1", "turn-real")
	if !ok || updated.BindingID != route.BindingID || updated.BaselineJSON != route.BaselineJSON {
		t.Fatalf("updated route=%#v ok=%v", updated, ok)
	}
}

func TestUpdateTurnSaveFailureDoesNotMutateInMemoryRoute(t *testing.T) {
	dir := t.TempDir()
	stateDir := filepath.Join(dir, "state")
	path := filepath.Join(stateDir, "routes.json")
	store, err := OpenRoutes(path)
	if err != nil {
		t.Fatal(err)
	}
	route := Route{RunID: "run-1", HostID: "host-1", DeviceID: "phone-1", ThreadID: "thread-1"}
	if err := store.Put(route); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(stateDir); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(stateDir, []byte("blocks route persistence"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := store.UpdateTurn("run-1", "thread-1", "turn-real"); err == nil {
		t.Fatal("UpdateTurn unexpectedly persisted through injected filesystem failure")
	}
	loaded, ok := store.ByRun("run-1")
	if !ok || loaded.TurnID != "" {
		t.Fatalf("failed save mutated in-memory route: %#v ok=%v", loaded, ok)
	}
}

func TestLegacySchemaV1RoutesMigrateToBackendScopedKeys(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "routes.json")
	legacy := `{
  "schemaVersion": 1,
  "processEpoch": "epoch-legacy",
  "routes": {
    "run-legacy": {"runId": "run-legacy", "bindingId": "b", "workspaceId": "w", "hostId": "h", "deviceId": "d", "threadId": "thread-legacy", "turnId": "turn-1", "baselineJson": "{\"cwd\":\"/x\"}"}
  },
  "approvals": {
    "approval-legacy": {"approvalId": "approval-legacy", "runId": "run-legacy", "processEpoch": "epoch-legacy", "serverRequestId": 7, "status": "PENDING"}
  }
}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := OpenRoutes(path)
	if err != nil {
		t.Fatal(err)
	}
	route, ok := store.ByThread("thread-legacy")
	if !ok || route.BackendID != "codex" || route.BaselineJSON != `{"cwd":"/x"}` {
		t.Fatalf("migrated route=%#v ok=%v", route, ok)
	}
	approval, ok := store.Approval("approval-legacy")
	if !ok || approval.BackendID != "codex" {
		t.Fatalf("migrated approval=%#v ok=%v", approval, ok)
	}
	if err := store.ValidateResponse("approval-legacy", "epoch-legacy", json.RawMessage(`7`)); err != nil {
		t.Fatalf("legacy approval epoch not adopted into per-backend epochs: %v", err)
	}
	// Reopen persists schema v2 with composite keys.
	reopened, err := OpenRoutes(path)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.data.SchemaVersion != 2 {
		t.Fatalf("schema version = %d", reopened.data.SchemaVersion)
	}
	if _, ok := reopened.ByRun("run-legacy"); !ok {
		t.Fatal("ByRun lost the migrated route")
	}
}

func TestBeginProcessEpochScopedPerBackend(t *testing.T) {
	store, err := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	request := json.RawMessage(`1`)
	if err := store.BeginProcessEpoch("codex", "codex-epoch-1"); err != nil {
		t.Fatal(err)
	}
	if err := store.BeginProcessEpoch("dsh", "dsh-epoch-1"); err != nil {
		t.Fatal(err)
	}
	if err := store.PutApproval(Approval{
		ApprovalID: "a-codex", RunID: "run-c", BackendID: "codex", ProcessEpoch: "codex-epoch-1",
		ServerRequestID: request, Status: ApprovalPending,
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.PutApproval(Approval{
		ApprovalID: "a-dsh", RunID: "run-d", BackendID: "dsh", ProcessEpoch: "dsh-epoch-1",
		ServerRequestID: request, Status: ApprovalPending,
	}); err != nil {
		t.Fatal(err)
	}
	// codex restarts; only codex approvals must go stale.
	if err := store.BeginProcessEpoch("codex", "codex-epoch-2"); err != nil {
		t.Fatal(err)
	}
	codexApproval, _ := store.Approval("a-codex")
	if codexApproval.Status != ApprovalStale {
		t.Fatalf("codex approval status = %s", codexApproval.Status)
	}
	dshApproval, _ := store.Approval("a-dsh")
	if dshApproval.Status != ApprovalPending {
		t.Fatalf("dsh approval must stay pending, got %s", dshApproval.Status)
	}
	if err := store.ValidateResponse("a-dsh", "dsh-epoch-1", request); err != nil {
		t.Fatalf("dsh approval should still validate: %v", err)
	}
	if err := store.ValidateResponse("a-codex", "codex-epoch-1", request); err == nil {
		t.Fatal("codex approval with old epoch was accepted")
	}
}

func TestByThreadBackendScopesRoutes(t *testing.T) {
	store, err := OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	for _, route := range []Route{
		{RunID: "run-c", HostID: "h", DeviceID: "d", ThreadID: "same-thread", BackendID: "codex"},
		{RunID: "run-d", HostID: "h", DeviceID: "d", ThreadID: "same-thread", BackendID: "dsh"},
	} {
		if err := store.Put(route); err != nil {
			t.Fatal(err)
		}
	}
	codexRoute, ok := store.ByThreadBackend("same-thread", "codex")
	if !ok || codexRoute.RunID != "run-c" {
		t.Fatalf("codex route=%#v ok=%v", codexRoute, ok)
	}
	dshRoute, ok := store.ByThreadBackend("same-thread", "dsh")
	if !ok || dshRoute.RunID != "run-d" {
		t.Fatalf("dsh route=%#v ok=%v", dshRoute, ok)
	}
	if _, ok := store.ByThreadBackend("same-thread", "aux"); ok {
		t.Fatal("aux backend must not see the thread")
	}
}
