package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/harnessapk/remote/internal/protocol"
	"github.com/harnessapk/remote/internal/state"
)

func TestRelayRemainsOpaqueAndKeepsWireV1TTLBehavior(t *testing.T) {
	path := filepath.Join(t.TempDir(), "relay.json")
	store, err := state.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	_, _, _ = store.RegisterHost("host-1", "Mac")
	pairing, _ := store.CreatePairing("host-1", time.Minute)
	device, _, _ := store.Enroll(pairing.Ticket, "Phone", "")
	secret := bytes.Repeat([]byte{0x33}, 32)
	wire, err := protocol.Encrypt(secret, protocol.WireMessage{
		HostID: "host-1", DeviceID: device.ID, Sequence: 1,
	}, protocol.LogicalEvent{
		SchemaVersion: 1, EventID: "event-1", HostID: "host-1", DeviceID: device.ID,
		RunID: "run-secret", Sequence: 1, Type: "run.started", CreatedAt: time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatal(err)
	}
	if wire.Version != protocol.Version || wire.ExpiresAt <= time.Now().UnixMilli() {
		t.Fatalf("wire v1 ttl missing: %#v", wire)
	}
	if err := store.Enqueue(device.ID, wire); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "run-secret") || strings.Contains(string(raw), "run.started") {
		t.Fatalf("relay state contains logical plaintext: %s", raw)
	}
}
