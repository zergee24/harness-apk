package commandcache

import (
	"path/filepath"
	"testing"
)

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
