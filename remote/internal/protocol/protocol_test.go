package protocol

import (
	"strings"
	"testing"
	"time"
)

func TestEncryptRoundTripAndTamperDetection(t *testing.T) {
	secret, err := NewSecret()
	if err != nil {
		t.Fatal(err)
	}
	wire, err := Encrypt(secret, WireMessage{HostID: "mac", DeviceID: "phone", Sequence: 1}, Command{Type: "turn.start", RequestID: "req", Text: "hello"})
	if err != nil {
		t.Fatal(err)
	}
	var command Command
	if err := Decrypt(secret, wire, &command); err != nil {
		t.Fatal(err)
	}
	if command.Text != "hello" {
		t.Fatalf("text = %q", command.Text)
	}
	wire.HostID = "other"
	if err := Decrypt(secret, wire, &command); err == nil || !strings.Contains(err.Error(), "authentication") {
		t.Fatalf("expected authentication error, got %v", err)
	}
}

func TestExpiredMessageRejected(t *testing.T) {
	secret, _ := NewSecret()
	wire, err := Encrypt(secret, WireMessage{HostID: "mac", DeviceID: "phone", Sequence: 1, ExpiresAt: time.Now().Add(-time.Second).UnixMilli()}, Command{Type: "host.status"})
	if err != nil {
		t.Fatal(err)
	}
	var command Command
	if err := Decrypt(secret, wire, &command); err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("expected expired error, got %v", err)
	}
}
