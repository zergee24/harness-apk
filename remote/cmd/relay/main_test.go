package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/harnessapk/remote/internal/protocol"
	"github.com/harnessapk/remote/internal/push"
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

func TestRelayForwardsMobileHistoryLargerThanOneMiBWithoutDisconnectingHost(t *testing.T) {
	store, err := state.Open(filepath.Join(t.TempDir(), "relay.json"))
	if err != nil {
		t.Fatal(err)
	}
	hostToken, _, err := store.RegisterHost("host-1", "Mac")
	if err != nil {
		t.Fatal(err)
	}
	pairing, err := store.CreatePairing("host-1", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	device, deviceToken, err := store.Enroll(pairing.Ticket, "Phone", "")
	if err != nil {
		t.Fatal(err)
	}

	relay := &server{
		store: store, notifier: push.Noop{},
		hosts: map[string]*client{}, devices: map[string]*client{},
	}
	httpServer := httptest.NewServer(http.HandlerFunc(relay.websocket))
	defer httpServer.Close()
	wsURL := "ws" + strings.TrimPrefix(httpServer.URL, "http") + "/v1/ws"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	dial := func(role, id, token string) *websocket.Conn {
		t.Helper()
		headers := http.Header{}
		headers.Set("Authorization", "Bearer "+token)
		conn, _, err := websocket.Dial(ctx, wsURL+"?role="+role+"&id="+id, &websocket.DialOptions{HTTPHeader: headers})
		if err != nil {
			t.Fatal(err)
		}
		conn.SetReadLimit(8 << 20)
		t.Cleanup(func() { _ = conn.Close(websocket.StatusNormalClosure, "test complete") })
		return conn
	}
	deviceConn := dial("device", device.ID, deviceToken)
	hostConn := dial("host", "host-1", hostToken)

	wire := protocol.WireMessage{
		Version: protocol.Version, MessageID: "large-history", HostID: "host-1", DeviceID: device.ID,
		Sequence: 1, ExpiresAt: time.Now().Add(time.Minute).UnixMilli(), Nonce: "nonce",
		Ciphertext: strings.Repeat("x", 2<<20),
	}
	if err := hostConn.Write(ctx, websocket.MessageText, mustMarshal(t, wire)); err != nil {
		t.Fatal(err)
	}
	_, raw, err := deviceConn.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var forwarded protocol.WireMessage
	if err := json.Unmarshal(raw, &forwarded); err != nil {
		t.Fatal(err)
	}
	if len(forwarded.Ciphertext) != 2<<20 {
		t.Fatalf("ciphertext length = %d", len(forwarded.Ciphertext))
	}
}

func mustMarshal(t *testing.T, value any) []byte {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}
