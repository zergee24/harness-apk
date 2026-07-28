package state

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/harnessapk/remote/internal/protocol"
)

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
