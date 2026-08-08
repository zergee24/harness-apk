package journal

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/harnessapk/remote/internal/protocol"
)

func TestReplayKeepsLogicalIdentityAndRefreshesWireEnvelope(t *testing.T) {
	key := bytes.Repeat([]byte{0x41}, 32)
	secret := bytes.Repeat([]byte{0x22}, 32)
	store, err := Open(filepath.Join(t.TempDir(), "journal.log"), key, 100)
	if err != nil {
		t.Fatal(err)
	}
	event := protocol.LogicalEvent{
		SchemaVersion: 1, EventID: "event-1", HostID: "host-1", DeviceID: "device-1",
		RunID: "run-1", Sequence: 1, Type: "run.started", CreatedAt: time.Now().UnixMilli(),
	}
	if err := store.Append(event); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(filepath.Dir(store.path), filepath.Base(store.path)))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, []byte(event.RunID)) || bytes.Contains(raw, []byte(event.Type)) {
		t.Fatalf("journal leaked logical event plaintext: %s", raw)
	}

	first, err := store.Replay(event.EventID, secret, 10)
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.Replay(event.EventID, secret, 11)
	if err != nil {
		t.Fatal(err)
	}
	var firstEvent, secondEvent protocol.LogicalEvent
	if err := protocol.Decrypt(secret, first, &firstEvent); err != nil {
		t.Fatal(err)
	}
	if err := protocol.Decrypt(secret, second, &secondEvent); err != nil {
		t.Fatal(err)
	}
	if firstEvent.EventID != event.EventID || secondEvent.EventID != event.EventID || firstEvent.Sequence != event.Sequence || secondEvent.Sequence != event.Sequence {
		t.Fatalf("logical identity changed: first=%#v second=%#v", firstEvent, secondEvent)
	}
	if first.MessageID == second.MessageID || first.Nonce == second.Nonce || first.Sequence == second.Sequence {
		t.Fatalf("wire envelope was reused: first=%#v second=%#v", first, second)
	}
}

func TestCompactionRecordsGapBeforeDroppingUnackedEvent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "journal.log")
	key := bytes.Repeat([]byte{0x42}, 32)
	store, err := Open(path, key, 2)
	if err != nil {
		t.Fatal(err)
	}
	for sequence := uint64(1); sequence <= 3; sequence++ {
		if err := store.Append(protocol.LogicalEvent{
			SchemaVersion: 1, EventID: protocolID(sequence), HostID: "host-1", DeviceID: "device-1",
			RunID: "run-1", Sequence: sequence, Type: "run.item.upserted", CreatedAt: int64(sequence),
		}); err != nil {
			t.Fatal(err)
		}
	}
	if err := store.Compact(); err != nil {
		t.Fatal(err)
	}
	if gap, ok := store.GapFrom("host-1", "device-1"); !ok || gap != 1 {
		t.Fatalf("gap = %d, %v", gap, ok)
	}
	reopened, err := Open(path, key, 2)
	if err != nil {
		t.Fatal(err)
	}
	if gap, ok := reopened.GapFrom("host-1", "device-1"); !ok || gap != 1 {
		t.Fatalf("persisted gap = %d, %v", gap, ok)
	}
	if events := reopened.Pending("host-1", "device-1"); len(events) != 2 || events[0].Sequence != 2 {
		t.Fatalf("pending after compaction = %#v", events)
	}
}

func protocolID(sequence uint64) string {
	return "event-" + time.Unix(int64(sequence), 0).UTC().Format("150405")
}
