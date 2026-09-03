package state

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/harnessapk/remote/internal/protocol"
)

func TestStateV1ToV2PreservesCredentialsAndForcesInitialGapSnapshot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.json")
	v1 := map[string]any{
		"relayUrl": "https://relay.example", "hostId": "host-1", "hostName": "Mac",
		"hostToken": "host-token", "pendingPairingSecrets": map[string]string{"ticket": "pair-secret"},
		"deviceSecrets":   map[string]string{"device-1": "device-secret"},
		"sequences":       map[string]uint64{"device-1": 41},
		"pendingOutbound": map[string]map[string]string{"device-1": {"wire-1": "opaque-wire"}},
	}
	raw, _ := json.Marshal(v1)
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}

	state, err := LoadBridge(path)
	if err != nil {
		t.Fatal(err)
	}
	if state.SchemaVersion != 2 || state.HostToken != "host-token" || state.DeviceSecrets["device-1"] != "device-secret" || state.Sequences["device-1"] != 41 {
		t.Fatalf("migrated state = %#v", state)
	}
	if !state.NeedsInitialGapSnapshot || len(state.PendingOutbound) != 0 {
		t.Fatalf("legacy pending was guessed instead of forcing snapshot: %#v", state)
	}
	reloaded, err := LoadBridge(path)
	if err != nil || reloaded.SchemaVersion != 2 || !reloaded.NeedsInitialGapSnapshot {
		t.Fatalf("reloaded state=%#v err=%v", reloaded, err)
	}
}

func TestBridgeStatePersistsLogicalThreadContinuations(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.json")
	initial := BridgeData{
		SchemaVersion:   BridgeSchemaVersion,
		RelayURL:        "https://relay.example",
		HostID:          "host-1",
		HostName:        "Mac",
		HostToken:       "host-token",
		Pending:         map[string]string{},
		DeviceSecrets:   map[string]string{},
		Sequences:       map[string]uint64{},
		PendingOutbound: map[string]map[string]string{},
		JournalKey:      base64.RawURLEncoding.EncodeToString(make([]byte, 32)),
		ThreadContinuations: map[string]ThreadContinuation{
			"thread-root": {
				RootThreadID: "thread-root",
				ThreadIDs:    []string{"thread-root", "thread-current"},
				Name:         "review",
				CWD:          "/workspace/project",
			},
		},
	}
	if err := SaveBridge(path, initial); err != nil {
		t.Fatal(err)
	}

	reloaded, err := LoadBridge(path)
	if err != nil {
		t.Fatal(err)
	}
	record := reloaded.ThreadContinuations["thread-root"]
	if record.RootThreadID != "thread-root" || record.Name != "review" || record.CWD != "/workspace/project" || len(record.ThreadIDs) != 2 || record.ThreadIDs[1] != "thread-current" {
		t.Fatalf("continuation=%#v", record)
	}
}

func TestPairingIsSingleUseAndDeviceCanBeRevoked(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "relay.json"))
	if err != nil {
		t.Fatal(err)
	}
	token, _, err := store.RegisterHost("mac", "Mac")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := store.AuthenticateHost("mac", token); !ok {
		t.Fatal("host authentication failed")
	}
	pairing, err := store.CreatePairing("mac", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	device, deviceToken, err := store.Enroll(pairing.Ticket, "phone", "push-id")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.Enroll(pairing.Ticket, "other", ""); err == nil {
		t.Fatal("pairing ticket was reused")
	}
	if _, ok := store.AuthenticateDevice(device.ID, deviceToken); !ok {
		t.Fatal("device authentication failed")
	}
	if err := store.RevokeDevice("mac", device.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := store.AuthenticateDevice(device.ID, deviceToken); ok {
		t.Fatal("revoked device authenticated")
	}
}

func TestOfflineMessagesAreBoundedAndExpire(t *testing.T) {
	store, _ := Open(filepath.Join(t.TempDir(), "relay.json"))
	_, _, _ = store.RegisterHost("mac", "Mac")
	pairing, _ := store.CreatePairing("mac", time.Minute)
	device, _, _ := store.Enroll(pairing.Ticket, "phone", "")
	_ = store.Enqueue(device.ID, protocol.WireMessage{MessageID: "expired", ExpiresAt: time.Now().Add(-time.Second).UnixMilli()})
	for i := 0; i < 105; i++ {
		_ = store.Enqueue(device.ID, protocol.WireMessage{MessageID: randomID(i), ExpiresAt: time.Now().Add(time.Minute).UnixMilli()})
	}
	pending := store.Drain(device.ID)
	if len(pending) != 100 {
		t.Fatalf("pending count = %d", len(pending))
	}
	if len(store.Drain(device.ID)) != 0 {
		t.Fatal("messages were delivered twice")
	}
}

func TestOfflineHostMessagesAreQueuedAndExpire(t *testing.T) {
	store, _ := Open(filepath.Join(t.TempDir(), "relay.json"))
	_, _, _ = store.RegisterHost("mac", "Mac")
	_ = store.EnqueueHost("mac", protocol.WireMessage{
		MessageID: "expired",
		ExpiresAt: time.Now().Add(-time.Second).UnixMilli(),
	})
	_ = store.EnqueueHost("mac", protocol.WireMessage{
		MessageID: "pending",
		ExpiresAt: time.Now().Add(time.Minute).UnixMilli(),
	})

	pending := store.DrainHost("mac")

	if len(pending) != 1 || pending[0].MessageID != "pending" {
		t.Fatalf("pending host messages = %#v", pending)
	}
	if len(store.DrainHost("mac")) != 0 {
		t.Fatal("host messages were delivered twice")
	}
}

func TestDeviceCanRefreshPushTarget(t *testing.T) {
	store, _ := Open(filepath.Join(t.TempDir(), "relay.json"))
	_, _, _ = store.RegisterHost("mac", "Mac")
	pairing, _ := store.CreatePairing("mac", time.Minute)
	device, deviceToken, _ := store.Enroll(pairing.Ticket, "phone", "")

	if err := store.UpdatePushTarget(device.ID, deviceToken, "push-ready"); err != nil {
		t.Fatal(err)
	}

	updated, ok := store.Device(device.ID)
	if !ok || updated.PushTarget != "push-ready" {
		t.Fatalf("updated device = %#v", updated)
	}
	if err := store.UpdatePushTarget(device.ID, "wrong-token", "attacker"); err == nil {
		t.Fatal("invalid device token updated push target")
	}
}

func randomID(value int) string { return time.UnixMilli(int64(value)).Format(time.RFC3339Nano) }

func TestBootstrapCanOnlyBeUsedOnceAndRecoveryRotatesCredentials(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "relay.json"))
	if err != nil {
		t.Fatal(err)
	}
	oldToken, recovery, err := store.RegisterHost("mac", "Mac")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.RegisterHost("other", "Other"); err == nil {
		t.Fatal("bootstrap was reused")
	}
	newToken, nextRecovery, err := store.RecoverHost("mac", recovery)
	if err != nil {
		t.Fatal(err)
	}
	if newToken == oldToken || nextRecovery == recovery {
		t.Fatal("credentials were not rotated")
	}
	if _, ok := store.AuthenticateHost("mac", oldToken); ok {
		t.Fatal("old host token authenticated")
	}
	if _, ok := store.AuthenticateHost("mac", newToken); !ok {
		t.Fatal("new host token failed")
	}
	if _, _, err := store.RecoverHost("mac", recovery); err == nil {
		t.Fatal("old recovery code was reused")
	}
}
