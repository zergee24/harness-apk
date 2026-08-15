package protocol

import (
	"encoding/json"
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

func TestAckWireRoundTripAndAckOfBinding(t *testing.T) {
	secret, _ := NewSecret()
	wire, err := Encrypt(secret, WireMessage{HostID: "mac", DeviceID: "phone", Sequence: 2, AckOf: "original-msg"}, Command{Type: "ack"})
	if err != nil {
		t.Fatal(err)
	}
	if wire.AckOf != "original-msg" {
		t.Fatalf("ackOf = %q", wire.AckOf)
	}
	var command Command
	if err := Decrypt(secret, wire, &command); err != nil {
		t.Fatal(err)
	}
	if command.Type != "ack" {
		t.Fatalf("type = %q", command.Type)
	}
	wire.AckOf = "tampered"
	if err := Decrypt(secret, wire, &command); err == nil || !strings.Contains(err.Error(), "authentication") {
		t.Fatalf("expected authentication error for tampered ackOf, got %v", err)
	}
}

func TestCommandBackendIDRoundTrip(t *testing.T) {
	secret, err := NewSecret()
	if err != nil {
		t.Fatal(err)
	}
	wire, err := Encrypt(secret, WireMessage{HostID: "mac", DeviceID: "phone", Sequence: 3},
		Command{Type: "turn.start", RequestID: "req", ThreadID: "t1", Text: "hi", BackendID: "dsh"})
	if err != nil {
		t.Fatal(err)
	}
	var command Command
	if err := Decrypt(secret, wire, &command); err != nil {
		t.Fatal(err)
	}
	if command.BackendID != "dsh" {
		t.Fatalf("backendId = %q, want dsh", command.BackendID)
	}
}

func TestCommandLegacyPayloadHasEmptyBackendID(t *testing.T) {
	secret, _ := NewSecret()
	wire, err := Encrypt(secret, WireMessage{HostID: "mac", DeviceID: "phone", Sequence: 4},
		Command{Type: "thread.list", RequestID: "req"})
	if err != nil {
		t.Fatal(err)
	}
	var command Command
	if err := Decrypt(secret, wire, &command); err != nil {
		t.Fatal(err)
	}
	if command.BackendID != "" {
		t.Fatalf("legacy payload backendId = %q, want empty", command.BackendID)
	}
	raw, err := json.Marshal(Command{Type: "thread.list", RequestID: "req"})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "backendId") {
		t.Fatalf("legacy command JSON must omit backendId: %s", raw)
	}
}

func TestEventBackendIDRoundTrip(t *testing.T) {
	secret, _ := NewSecret()
	wire, err := Encrypt(secret, WireMessage{HostID: "mac", DeviceID: "phone", Sequence: 5},
		Event{Type: "codex.event", BackendID: "dsh", Method: "turn/completed", CreatedAt: time.Now().UnixMilli()})
	if err != nil {
		t.Fatal(err)
	}
	var event Event
	if err := Decrypt(secret, wire, &event); err != nil {
		t.Fatal(err)
	}
	if event.BackendID != "dsh" {
		t.Fatalf("event backendId = %q, want dsh", event.BackendID)
	}
}

func TestHostStatusPayloadWithBackends(t *testing.T) {
	payload := HostStatusPayload{
		SchemaVersion: 1,
		Capabilities:  []string{"run.lifecycle.v1", "logical-replay.v1"},
		Backends: []BackendInfo{
			{ID: DefaultBackendID, Name: "Codex", Capabilities: []string{"run.lifecycle.v1", CapabilityApprovals}},
			{ID: "dsh", Name: "DeepSeek Harness", Capabilities: []string{"run.lifecycle.v1"}},
		},
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	var decoded HostStatusPayload
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.SchemaVersion != 1 || len(decoded.Backends) != 2 {
		t.Fatalf("decoded payload = %+v", decoded)
	}
	if decoded.Backends[1].ID != "dsh" || len(decoded.Backends[1].Capabilities) != 1 {
		t.Fatalf("dsh backend = %+v", decoded.Backends[1])
	}
	if decoded.Backends[0].ID != DefaultBackendID || len(decoded.Backends[0].Capabilities) != 2 {
		t.Fatalf("codex backend = %+v", decoded.Backends[0])
	}
}

func TestHostStatusPayloadLegacyWithoutBackends(t *testing.T) {
	raw := `{"schemaVersion":1,"capabilities":["run.lifecycle.v1"]}`
	var decoded HostStatusPayload
	if err := json.Unmarshal([]byte(raw), &decoded); err != nil {
		t.Fatal(err)
	}
	if len(decoded.Backends) != 0 || len(decoded.Capabilities) != 1 {
		t.Fatalf("legacy payload = %+v", decoded)
	}
}
