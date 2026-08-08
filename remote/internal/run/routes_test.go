package run

import (
	"encoding/json"
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

	approval, changed, err := store.MarkServerRequestResolved("epoch-1", requestID)
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
	if err := store.BeginProcessEpoch("epoch-1"); err != nil {
		t.Fatal(err)
	}
	if err := store.PutApproval(Approval{
		ApprovalID: "approval-1", RunID: "run-1", ProcessEpoch: "epoch-1",
		ServerRequestID: requestID, Status: ApprovalPending,
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.BeginProcessEpoch("epoch-2"); err != nil {
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
