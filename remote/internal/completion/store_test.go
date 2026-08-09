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
