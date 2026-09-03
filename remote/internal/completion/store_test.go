package completion

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestTerminalRunStoreFreezesFirstCompletionAcrossReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "terminal-runs.json")
	store, err := OpenTerminalRunStore(path)
	if err != nil {
		t.Fatal(err)
	}
	firstJSON := json.RawMessage(`{"schemaVersion":2,"completionId":"completion-1","summary":"first"}`)
	first, created, err := store.Freeze(TerminalRunRecord{
		RunID:          "run-1",
		Status:         "COMPLETED",
		CompletionJSON: firstJSON,
		CompletedAt:    1234,
		Workspace: WorkspaceLocator{
			WorkspaceID:           "workspace-1",
			RepositoryFingerprint: "fingerprint-1",
			CWD:                   "/workspace/harness-apk",
		},
	})
	if err != nil || !created || first.CompletionSHA256 == "" {
		t.Fatalf("first freeze = %#v created=%v err=%v", first, created, err)
	}

	reopened, err := OpenTerminalRunStore(path)
	if err != nil {
		t.Fatal(err)
	}
	loaded, ok := reopened.Lookup("run-1")
	if !ok || string(loaded.CompletionJSON) != string(firstJSON) || loaded.CompletionSHA256 != first.CompletionSHA256 {
		t.Fatalf("loaded frozen completion = %#v ok=%v", loaded, ok)
	}

	_, created, err = reopened.Freeze(TerminalRunRecord{
		RunID:          "run-1",
		Status:         "COMPLETED",
		CompletionJSON: json.RawMessage(`{"schemaVersion":2,"completionId":"completion-1","summary":"changed later"}`),
		CompletedAt:    9999,
		Workspace:      loaded.Workspace,
	})
	if created || !errors.Is(err, ErrTerminalRunConflict) {
		t.Fatalf("second freeze created=%v err=%v", created, err)
	}
	stillFrozen, _ := reopened.Lookup("run-1")
	if string(stillFrozen.CompletionJSON) != string(firstJSON) || stillFrozen.CompletedAt != 1234 {
		t.Fatalf("first terminal completion was overwritten: %#v", stillFrozen)
	}
}

func TestTerminalRunStoreRejectsCompletionHashMismatchOnOpen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "terminal-runs.json")
	raw := []byte(`{"schemaVersion":1,"runs":{"run-1":{"runId":"run-1","status":"COMPLETED","completion":{"schemaVersion":2,"summary":"tampered"},"completionSha256":"0000","completedAt":1234}}}`)
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenTerminalRunStore(path); err == nil {
		t.Fatal("opened terminal ledger with a mismatched completion hash")
	}
}

func TestTerminalRunStoreRejectsUnknownTerminalStatus(t *testing.T) {
	store, err := OpenTerminalRunStore(filepath.Join(t.TempDir(), "terminal-runs.json"))
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.Freeze(TerminalRunRecord{RunID: "run-1", Status: "FUTURE", CompletedAt: 1234}); err == nil {
		t.Fatal("unknown terminal status was frozen")
	}
}

func TestTerminalRunStoreMigratesLegacyV1Ledger(t *testing.T) {
	path := filepath.Join(t.TempDir(), "terminal-runs.json")
	raw := []byte(`{"schemaVersion":1,"runs":{"run-1":{"runId":"run-1","status":"FAILED","completedAt":1234}}}`)
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := OpenTerminalRunStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if record, ok := store.Lookup("run-1"); !ok || record.Status != "FAILED" {
		t.Fatalf("legacy record=%#v ok=%v", record, ok)
	}
	if pending := store.PendingJournalRecords(); len(pending) != 1 || pending[0].RunID != "run-1" {
		t.Fatalf("legacy journal recovery=%#v", pending)
	}
}

func TestTerminalRunStoreKeepsFirstFreezeAfterDirectorySyncFailure(t *testing.T) {
	path := filepath.Join(t.TempDir(), "terminal-runs.json")
	store, err := openTerminalRunStore(path, func(string) error {
		return errors.New("injected directory sync failure")
	})
	if err != nil {
		t.Fatal(err)
	}
	first := TerminalRunRecord{RunID: "run-1", Status: "FAILED", CompletedAt: 1234}
	if _, created, err := store.Freeze(first); err == nil || created {
		t.Fatalf("freeze created=%v err=%v", created, err)
	}
	if frozen, ok := store.Lookup("run-1"); !ok || frozen.Status != "FAILED" || frozen.CompletedAt != 1234 {
		t.Fatalf("post-rename freeze was forgotten: %#v ok=%v", frozen, ok)
	}
	if _, created, err := store.Freeze(TerminalRunRecord{
		RunID: "run-1", Status: "COMPLETED", CompletedAt: 9999,
	}); created || !errors.Is(err, ErrTerminalRunConflict) {
		t.Fatalf("first freeze overwritten: created=%v err=%v", created, err)
	}
	reopened, err := OpenTerminalRunStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if frozen, ok := reopened.Lookup("run-1"); !ok || frozen.Status != "FAILED" || frozen.CompletedAt != 1234 {
		t.Fatalf("durable first freeze=%#v ok=%v", frozen, ok)
	}
}

func TestTerminalObservationAndJournalStatePersistAcrossReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "terminal-runs.json")
	store, err := OpenTerminalRunStore(path)
	if err != nil {
		t.Fatal(err)
	}
	params := json.RawMessage(`{"threadId":"thread-1","turnId":"turn-1"}`)
	if err := store.Observe(TerminalObservation{
		RunID: "run-1", Params: params, ObservedAt: 1234,
	}); err != nil {
		t.Fatal(err)
	}
	reopened, err := OpenTerminalRunStore(path)
	if err != nil {
		t.Fatal(err)
	}
	pending := reopened.PendingObservations()
	if len(pending) != 1 || pending[0].RunID != "run-1" || string(pending[0].Params) != string(params) {
		t.Fatalf("pending observations=%#v", pending)
	}
	if _, _, err := reopened.Freeze(TerminalRunRecord{RunID: "run-1", Status: "FAILED", CompletedAt: 5678}); err != nil {
		t.Fatal(err)
	}
	if len(reopened.PendingObservations()) != 0 || len(reopened.PendingJournalRecords()) != 1 {
		t.Fatalf("freeze did not move observation to journal outbox")
	}
	if err := reopened.MarkJournaled("run-1", "terminal-event-1"); err != nil {
		t.Fatal(err)
	}
	reopened, err = OpenTerminalRunStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(reopened.PendingJournalRecords()) != 0 {
		t.Fatalf("journal marker was not durable: %#v", reopened.PendingJournalRecords())
	}
}
