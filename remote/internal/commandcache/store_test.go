package commandcache

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestAttachContextBackfillsLegacyUnknownRecord(t *testing.T) {
	path := filepath.Join(t.TempDir(), "commands.json")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, execute, err := store.Begin("command-legacy", "run.steer", "hash"); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	if _, err := store.MarkUnknown("command-legacy", errors.New("lost response")); err != nil {
		t.Fatal(err)
	}
	contextJSON := json.RawMessage(`{"backendId":"dsh","threadId":"thread-1","expectedTurnId":"turn-1"}`)
	if _, err := store.AttachContext("command-legacy", contextJSON); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	record, ok := reopened.Lookup("command-legacy")
	var context struct {
		BackendID      string `json:"backendId"`
		ThreadID       string `json:"threadId"`
		ExpectedTurnID string `json:"expectedTurnId"`
	}
	if !ok || json.Unmarshal(record.ContextJSON, &context) != nil || context.BackendID != "dsh" ||
		context.ThreadID != "thread-1" || context.ExpectedTurnID != "turn-1" {
		t.Fatalf("record=%#v context=%#v", record, context)
	}
}

func TestAttachResultRollsBackMemoryWhenPersistenceFails(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "commands.json")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, execute, err := store.Begin("command-1", "run.steer", "hash"); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	if _, err := store.MarkUnknown("command-1", errors.New("lost response")); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(dir, 0o700) })
	if _, err := store.AttachResult("command-1", "event-1", json.RawMessage(`{"turnId":"turn-2"}`)); err == nil {
		t.Fatal("AttachResult succeeded despite unwritable store directory")
	}
	record, _ := store.Lookup("command-1")
	if record.ResultEventID != "" || len(record.ResultJSON) != 0 || record.Status != StatusUnknown {
		t.Fatalf("failed save leaked into memory: %#v", record)
	}
}

func TestUnknownRecordsUseDurableDispatchOrderInsteadOfCommandID(t *testing.T) {
	path := filepath.Join(t.TempDir(), "commands.json")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"z-first", "a-second"} {
		if _, execute, err := store.BeginWithContext(id, "run.steer", "hash-"+id, json.RawMessage(`{"threadId":"thread-1"}`)); err != nil || !execute {
			t.Fatalf("begin %s execute=%v err=%v", id, execute, err)
		}
		if _, err := store.MarkUnknown(id, errors.New("lost")); err != nil {
			t.Fatal(err)
		}
	}
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	records := reopened.RecordsByTypeStatus("run.steer", StatusUnknown)
	if len(records) != 2 || records[0].CommandID != "z-first" || records[1].CommandID != "a-second" ||
		records[0].DispatchOrder == 0 || records[0].DispatchOrder >= records[1].DispatchOrder {
		t.Fatalf("records=%#v", records)
	}
}

func TestFailedUnknownPersistenceRemainsRecoveryVisibleInMemory(t *testing.T) {
	dir := t.TempDir()
	store, err := Open(filepath.Join(dir, "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	if _, execute, err := store.BeginWithContext("command-uncertain", "run.steer", "hash", json.RawMessage(`{"threadId":"thread-1"}`)); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(dir, 0o700) })
	if _, err := store.MarkUnknown("command-uncertain", errors.New("lost response")); err == nil {
		t.Fatal("MarkUnknown succeeded despite unwritable store directory")
	}
	record, ok := store.Lookup("command-uncertain")
	if !ok || record.Status != StatusUnknown || record.LastError != "lost response" {
		t.Fatalf("lookup=%#v ok=%v", record, ok)
	}
	records := store.RecordsByTypeStatus("run.steer", StatusUnknown)
	if len(records) != 1 || records[0].CommandID != "command-uncertain" {
		t.Fatalf("records=%#v", records)
	}
}

func TestBeginWithContextPersistsReconciliationIdentityAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "commands.json")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	contextJSON := json.RawMessage(`{"backendId":"dsh","threadId":"thread-1","expectedTurnId":"turn-1"}`)
	if _, execute, err := store.BeginWithContext("command-1", "turn.steer", "payload-hash", contextJSON); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	if _, err := store.MarkUnknown("command-1", errInjected); err != nil {
		t.Fatal(err)
	}

	restarted, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	record, ok := restarted.Lookup("command-1")
	var context struct {
		BackendID      string `json:"backendId"`
		ThreadID       string `json:"threadId"`
		ExpectedTurnID string `json:"expectedTurnId"`
	}
	if !ok || json.Unmarshal(record.ContextJSON, &context) != nil || context.BackendID != "dsh" || context.ThreadID != "thread-1" || context.ExpectedTurnID != "turn-1" {
		t.Fatalf("restarted record=%#v context=%#v ok=%v", record, context, ok)
	}
}

var errInjected = &injectedError{}

type injectedError struct{}

func (*injectedError) Error() string { return "injected" }

func TestSucceedResolvesUnknownFromAuthoritativeObservation(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	if _, execute, err := store.Begin("command-1", "turn.steer", "payload-hash"); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}
	if _, err := store.MarkUnknown("command-1", errInjected); err != nil {
		t.Fatal(err)
	}
	result := json.RawMessage(`{"turnId":"turn-2"}`)
	record, err := store.Succeed("command-1", "event-1", result)
	if err != nil {
		t.Fatal(err)
	}
	if record.Status != StatusSucceeded || record.ResultEventID != "event-1" || string(record.ResultJSON) != string(result) || record.LastError != "" {
		t.Fatalf("resolved record=%#v", record)
	}
}

func TestRestartedInFlightCommandBecomesUnknownInsteadOfReexecuting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "commands.json")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, execute, err := store.Begin("command-1", "run.start", "payload-hash"); err != nil || !execute {
		t.Fatalf("begin execute=%v err=%v", execute, err)
	}

	restarted, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	record, execute, err := restarted.Begin("command-1", "run.start", "payload-hash")
	if err != nil {
		t.Fatal(err)
	}
	if execute || record.Status != StatusUnknown {
		t.Fatalf("restarted record=%#v execute=%v", record, execute)
	}
}
