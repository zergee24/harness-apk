package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/harnessapk/remote/internal/protocol"
	qrcode "github.com/skip2/go-qrcode"
)

type bridgeState struct {
	RelayURL        string                       `json:"relayUrl"`
	HostID          string                       `json:"hostId"`
	HostName        string                       `json:"hostName"`
	HostToken       string                       `json:"hostToken"`
	Pending         map[string]string            `json:"pendingPairingSecrets"`
	DeviceSecrets   map[string]string            `json:"deviceSecrets"`
	Sequences       map[string]uint64            `json:"sequences"`
	PendingOutbound map[string]map[string]string `json:"pendingOutbound,omitempty"`
}

type bridge struct {
	mu           sync.Mutex
	writeMu      sync.Mutex
	state        bridgeState
	path         string
	conn         *websocket.Conn
	app          *appServer
	seen         map[string]time.Time
	threadOwners map[string]string
}

type appServer struct {
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	scanner   *bufio.Scanner
	writeMu   sync.Mutex
	requestMu sync.Mutex
	nextID    int64
	pending   map[string]pendingRequest
}

type pendingRequest struct{ DeviceID, RequestID string }

func main() {
	statePath := defaultStatePath()
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "init":
		runInit(statePath, os.Args[2:])
	case "recover":
		runRecover(statePath, os.Args[2:])
	case "pair":
		runPair(statePath, os.Args[2:])
	case "serve":
		runServe(statePath, os.Args[2:])
	default:
		usage()
		os.Exit(2)
	}
}

func runInit(defaultPath string, args []string) {
	flags := flag.NewFlagSet("init", flag.ExitOnError)
	statePath := flags.String("state", defaultPath, "bridge state file")
	relayURL := flags.String("relay", "", "relay HTTPS URL")
	hostID := flags.String("host-id", "", "stable host id")
	name := flags.String("name", hostname(), "host display name")
	bootstrap := flags.String("bootstrap-token", os.Getenv("HARNESS_RELAY_BOOTSTRAP_TOKEN"), "one-time relay bootstrap token")
	_ = flags.Parse(args)
	if *relayURL == "" || *hostID == "" || *bootstrap == "" {
		flags.Usage()
		os.Exit(2)
	}
	request := map[string]string{"HostID": *hostID, "Name": *name}
	var response struct{ HostID, HostToken, RecoveryCode string }
	if err := postJSON(context.Background(), strings.TrimRight(*relayURL, "/")+"/v1/hosts/register", *bootstrap, "", request, &response); err != nil {
		log.Fatal(err)
	}
	state := bridgeState{RelayURL: *relayURL, HostID: response.HostID, HostName: *name, HostToken: response.HostToken, Pending: map[string]string{}, DeviceSecrets: map[string]string{}, Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{}}
	if err := saveBridgeState(*statePath, state); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("Bridge initialized. Store this recovery code offline:\n%s\n", response.RecoveryCode)
}

func runRecover(defaultPath string, args []string) {
	flags := flag.NewFlagSet("recover", flag.ExitOnError)
	statePath := flags.String("state", defaultPath, "bridge state file")
	relayURL := flags.String("relay", "", "relay HTTPS URL")
	hostID := flags.String("host-id", "", "host id")
	name := flags.String("name", hostname(), "host display name")
	recovery := flags.String("recovery-code", "", "offline recovery code")
	_ = flags.Parse(args)
	if *relayURL == "" || *hostID == "" || *recovery == "" {
		flags.Usage()
		os.Exit(2)
	}
	request := map[string]string{"HostID": *hostID, "RecoveryCode": *recovery}
	var response struct{ HostID, HostToken, RecoveryCode string }
	if err := postJSON(context.Background(), strings.TrimRight(*relayURL, "/")+"/v1/hosts/recover", "", "", request, &response); err != nil {
		log.Fatal(err)
	}
	state := bridgeState{RelayURL: *relayURL, HostID: response.HostID, HostName: *name, HostToken: response.HostToken, Pending: map[string]string{}, DeviceSecrets: map[string]string{}, Sequences: map[string]uint64{}, PendingOutbound: map[string]map[string]string{}}
	if err := saveBridgeState(*statePath, state); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("Bridge recovered. Existing phones were revoked. Store this new recovery code offline:\n%s\n", response.RecoveryCode)
}

func runPair(defaultPath string, args []string) {
	flags := flag.NewFlagSet("pair", flag.ExitOnError)
	statePath := flags.String("state", defaultPath, "bridge state file")
	qrPath := flags.String("qr", "", "pairing QR PNG output path")
	_ = flags.Parse(args)
	state, err := loadBridgeState(*statePath)
	if err != nil {
		log.Fatal(err)
	}
	var pairing struct {
		Ticket, HostID string
		ExpiresAt      int64
	}
	if err := postJSON(context.Background(), strings.TrimRight(state.RelayURL, "/")+"/v1/pairings", state.HostToken, state.HostID, map[string]string{}, &pairing); err != nil {
		log.Fatal(err)
	}
	secret, err := protocol.NewSecret()
	if err != nil {
		log.Fatal(err)
	}
	state.Pending[pairing.Ticket] = protocol.EncodeSecret(secret)
	if err := saveBridgeState(*statePath, state); err != nil {
		log.Fatal(err)
	}
	payload := protocol.PairingPayload{Version: protocol.Version, RelayURL: state.RelayURL, HostID: state.HostID, HostName: state.HostName, PairingTicket: pairing.Ticket, PairingSecret: protocol.EncodeSecret(secret), ExpiresAt: pairing.ExpiresAt}
	raw, _ := json.Marshal(payload)
	fmt.Println(string(raw))
	output := *qrPath
	if output == "" {
		output = filepath.Join(filepath.Dir(*statePath), "pairing.png")
	}
	if err := qrcode.WriteFile(string(raw), qrcode.Medium, 512, output); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("Pairing QR written to %s\n", output)
}

func runServe(defaultPath string, args []string) {
	flags := flag.NewFlagSet("serve", flag.ExitOnError)
	statePath := flags.String("state", defaultPath, "bridge state file")
	codex := flags.String("codex", "codex", "Codex executable")
	_ = flags.Parse(args)
	state, err := loadBridgeState(*statePath)
	if err != nil {
		log.Fatal(err)
	}
	for {
		app, err := startAppServer(*codex)
		if err != nil {
			log.Printf("start app-server: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}
		b := &bridge{
			state: state, path: *statePath, app: app,
			seen: map[string]time.Time{}, threadOwners: map[string]string{},
		}
		if err := b.run(context.Background()); err != nil {
			log.Printf("bridge disconnected: %v", err)
		}
		app.close()
		state, _ = loadBridgeState(*statePath)
		time.Sleep(3 * time.Second)
	}
}

func (b *bridge) run(ctx context.Context) error {
	wsURL, err := relayWebSocketURL(b.state.RelayURL, "host", b.state.HostID)
	if err != nil {
		return err
	}
	headers := http.Header{"Authorization": []string{"Bearer " + b.state.HostToken}}
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{HTTPHeader: headers})
	if err != nil {
		return err
	}
	b.conn = conn
	defer conn.Close(websocket.StatusNormalClosure, "bridge stopped")
	if err := b.resendPending(ctx); err != nil {
		return err
	}
	lines := make(chan []byte, 64)
	errorsCh := make(chan error, 2)
	go func() {
		for b.app.scanner.Scan() {
			raw := append([]byte(nil), b.app.scanner.Bytes()...)
			lines <- raw
		}
		errorsCh <- b.app.scanner.Err()
	}()
	go func() {
		for {
			_, raw, err := conn.Read(ctx)
			if err != nil {
				errorsCh <- err
				return
			}
			if err := b.handleWire(ctx, raw); err != nil {
				log.Printf("discard remote message: %v", err)
			}
		}
	}()
	if err := b.app.initialize(); err != nil {
		return err
	}
	for {
		select {
		case raw := <-lines:
			b.handleAppServer(ctx, raw)
		case err := <-errorsCh:
			return err
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

func (b *bridge) handleWire(ctx context.Context, raw []byte) error {
	var wire protocol.WireMessage
	if err := json.Unmarshal(raw, &wire); err != nil {
		return err
	}
	b.mu.Lock()
	secretEncoded := b.state.DeviceSecrets[wire.DeviceID]
	if secretEncoded == "" && wire.PairingTicket != "" {
		secretEncoded = b.state.Pending[wire.PairingTicket]
		if secretEncoded != "" {
			b.state.DeviceSecrets[wire.DeviceID] = secretEncoded
			delete(b.state.Pending, wire.PairingTicket)
			_ = saveBridgeState(b.path, b.state)
		}
	}
	if _, duplicate := b.seen[wire.MessageID]; duplicate {
		b.mu.Unlock()
		return errors.New("replayed message")
	}
	b.seen[wire.MessageID] = time.Now()
	for id, seenAt := range b.seen {
		if time.Since(seenAt) > 10*time.Minute {
			delete(b.seen, id)
		}
	}
	b.mu.Unlock()
	secret, err := protocol.DecodeSecret(secretEncoded)
	if err != nil {
		return err
	}
	var command protocol.Command
	if err := protocol.Decrypt(secret, wire, &command); err != nil {
		return err
	}
	if command.Type == "ack" && wire.AckOf != "" {
		b.acknowledge(wire.DeviceID, wire.AckOf)
		return nil
	}
	return b.executeCommand(ctx, wire.DeviceID, command)
}

func (b *bridge) acknowledge(deviceID, messageID string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if pending := b.state.PendingOutbound[deviceID]; pending != nil {
		if _, exists := pending[messageID]; exists {
			delete(pending, messageID)
			_ = saveBridgeState(b.path, b.state)
		}
	}
}

func (b *bridge) executeCommand(ctx context.Context, deviceID string, command protocol.Command) error {
	switch command.Type {
	case "host.status":
		return b.sendEvent(ctx, deviceID, protocol.Event{Type: "host.status", RequestID: command.RequestID, Message: "online", CreatedAt: time.Now().UnixMilli()}, "")
	case "thread.list":
		return b.app.request(deviceID, command.RequestID, "thread/list", json.RawMessage(`{"limit":50,"sortKey":"updated_at","sortDirection":"desc","sourceKinds":["cli","vscode","exec","appServer"]}`))
	case "thread.read":
		return b.app.request(deviceID, command.RequestID, "thread/read", mustJSON(map[string]any{"threadId": command.ThreadID, "includeTurns": true}))
	case "thread.start":
		return b.app.request(deviceID, command.RequestID, "thread/start", mustJSON(map[string]any{"cwd": command.CWD}))
	case "turn.start":
		b.claimThread(command.ThreadID, deviceID)
		return b.app.request(deviceID, command.RequestID, "turn/start", mustJSON(map[string]any{"threadId": command.ThreadID, "input": []map[string]string{{"type": "text", "text": command.Text}}}))
	case "turn.steer":
		b.claimThread(command.ThreadID, deviceID)
		return b.app.request(deviceID, command.RequestID, "turn/steer", mustJSON(map[string]any{"threadId": command.ThreadID, "expectedTurnId": command.ExpectedTurnID, "input": []map[string]string{{"type": "text", "text": command.Text}}}))
	case "turn.interrupt":
		return b.app.request(deviceID, command.RequestID, "turn/interrupt", mustJSON(map[string]string{"threadId": command.ThreadID, "turnId": command.TurnID}))
	case "approval.respond":
		return b.app.response(command.ServerRequestID, map[string]string{"decision": command.Decision})
	case "rpc":
		return b.app.request(deviceID, command.RequestID, command.Method, command.Params)
	default:
		return b.sendEvent(ctx, deviceID, protocol.Event{Type: "error", RequestID: command.RequestID, Message: "unsupported command: " + command.Type, CreatedAt: time.Now().UnixMilli()}, "")
	}
}

func (b *bridge) handleAppServer(ctx context.Context, raw []byte) {
	var message struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Params json.RawMessage `json:"params"`
		Result json.RawMessage `json:"result"`
		Error  json.RawMessage `json:"error"`
	}
	if json.Unmarshal(raw, &message) != nil {
		return
	}
	if len(message.ID) > 0 && string(message.ID) != "null" {
		key := string(message.ID)
		b.app.requestMu.Lock()
		pending, exists := b.app.pending[key]
		if exists {
			delete(b.app.pending, key)
		}
		b.app.requestMu.Unlock()
		if exists {
			_ = b.sendEvent(ctx, pending.DeviceID, protocol.Event{Type: "rpc.response", RequestID: pending.RequestID, Payload: raw, CreatedAt: time.Now().UnixMilli()}, "")
			return
		}
	}
	if message.Method == "" {
		return
	}
	eventType, pushKind := "codex.event", ""
	if strings.Contains(message.Method, "requestApproval") || strings.Contains(message.Method, "requestUserInput") {
		eventType, pushKind = "approval.request", "approval"
	}
	if message.Method == "turn/completed" {
		pushKind = "completion"
	}
	for _, deviceID := range b.eventTargets(message.Params) {
		_ = b.sendEvent(ctx, deviceID, protocol.Event{Type: eventType, Method: message.Method, Payload: raw, CreatedAt: time.Now().UnixMilli()}, pushKind)
	}
}

func (b *bridge) claimThread(threadID, deviceID string) {
	if threadID == "" || deviceID == "" {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.threadOwners == nil {
		b.threadOwners = map[string]string{}
	}
	b.threadOwners[threadID] = deviceID
}

func (b *bridge) eventTargets(params json.RawMessage) []string {
	var envelope struct {
		ThreadID string `json:"threadId"`
		Thread   struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if json.Unmarshal(params, &envelope) != nil {
		return nil
	}
	threadID := envelope.ThreadID
	if threadID == "" {
		threadID = envelope.Thread.ID
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	deviceID := b.threadOwners[threadID]
	if deviceID == "" {
		return nil
	}
	return []string{deviceID}
}

func (b *bridge) sendEvent(ctx context.Context, deviceID string, event protocol.Event, pushKind string) error {
	b.mu.Lock()
	encoded := b.state.DeviceSecrets[deviceID]
	b.state.Sequences[deviceID]++
	sequence := b.state.Sequences[deviceID]
	_ = saveBridgeState(b.path, b.state)
	b.mu.Unlock()
	secret, err := protocol.DecodeSecret(encoded)
	if err != nil {
		return err
	}
	if pushKind != "" {
		pushKind = "wake"
	}
	wire, err := protocol.Encrypt(secret, protocol.WireMessage{HostID: b.state.HostID, DeviceID: deviceID, Sequence: sequence, PushKind: pushKind}, event)
	if err != nil {
		return err
	}
	raw, _ := json.Marshal(wire)
	b.mu.Lock()
	pending := b.state.PendingOutbound[deviceID]
	if pending == nil {
		pending = map[string]string{}
		b.state.PendingOutbound[deviceID] = pending
	}
	pending[wire.MessageID] = string(raw)
	_ = saveBridgeState(b.path, b.state)
	b.mu.Unlock()
	b.writeMu.Lock()
	defer b.writeMu.Unlock()
	return b.conn.Write(ctx, websocket.MessageText, raw)
}

func (b *bridge) resendPending(ctx context.Context) error {
	now := time.Now().UnixMilli()
	b.mu.Lock()
	var wires []protocol.WireMessage
	for deviceID, pending := range b.state.PendingOutbound {
		for id, raw := range pending {
			var wire protocol.WireMessage
			if json.Unmarshal([]byte(raw), &wire) != nil || wire.ExpiresAt <= now {
				delete(pending, id)
			} else {
				wires = append(wires, wire)
			}
		}
		if len(pending) == 0 {
			delete(b.state.PendingOutbound, deviceID)
		}
	}
	if len(wires) == 0 && len(b.state.PendingOutbound) == 0 {
		b.mu.Unlock()
		return nil
	}
	_ = saveBridgeState(b.path, b.state)
	b.mu.Unlock()
	if len(wires) == 0 {
		return nil
	}
	b.writeMu.Lock()
	defer b.writeMu.Unlock()
	for _, wire := range wires {
		raw, _ := json.Marshal(wire)
		if err := b.conn.Write(ctx, websocket.MessageText, raw); err != nil {
			return err
		}
	}
	return nil
}

func (b *bridge) deviceSecrets() map[string]string {
	b.mu.Lock()
	defer b.mu.Unlock()
	result := map[string]string{}
	for k, v := range b.state.DeviceSecrets {
		result[k] = v
	}
	return result
}

func startAppServer(codex string) (*appServer, error) {
	cmd := exec.Command(codex, "app-server", "--listen", "stdio://")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 64<<10), 4<<20)
	return &appServer{cmd: cmd, stdin: stdin, scanner: scanner, nextID: 100, pending: map[string]pendingRequest{}}, nil
}

func (a *appServer) initialize() error {
	if err := a.write(map[string]any{"method": "initialize", "id": 1, "params": map[string]any{"clientInfo": map[string]string{"name": "harness_remote_bridge", "title": "Harness Remote Bridge", "version": "0.1.0"}}}); err != nil {
		return err
	}
	return a.write(map[string]any{"method": "initialized", "params": map[string]any{}})
}

func (a *appServer) request(deviceID, requestID, method string, params json.RawMessage) error {
	a.requestMu.Lock()
	a.nextID++
	id := a.nextID
	a.pending[fmt.Sprint(id)] = pendingRequest{DeviceID: deviceID, RequestID: requestID}
	a.requestMu.Unlock()
	var decoded any = map[string]any{}
	if len(params) > 0 {
		_ = json.Unmarshal(params, &decoded)
	}
	return a.write(map[string]any{"method": method, "id": id, "params": decoded})
}

func (a *appServer) response(id json.RawMessage, result any) error {
	var decoded any
	if json.Unmarshal(id, &decoded) != nil {
		return errors.New("invalid server request id")
	}
	return a.write(map[string]any{"id": decoded, "result": result})
}
func (a *appServer) write(value any) error {
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	a.writeMu.Lock()
	defer a.writeMu.Unlock()
	_, err = a.stdin.Write(append(raw, '\n'))
	return err
}
func (a *appServer) close() {
	_ = a.stdin.Close()
	if a.cmd.Process != nil {
		_ = a.cmd.Process.Kill()
	}
	_ = a.cmd.Wait()
}

func loadBridgeState(path string) (bridgeState, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return bridgeState{}, err
	}
	var s bridgeState
	err = json.Unmarshal(raw, &s)
	if s.Pending == nil {
		s.Pending = map[string]string{}
	}
	if s.DeviceSecrets == nil {
		s.DeviceSecrets = map[string]string{}
	}
	if s.Sequences == nil {
		s.Sequences = map[string]uint64{}
	}
	if s.PendingOutbound == nil {
		s.PendingOutbound = map[string]map[string]string{}
	}
	return s, err
}
func saveBridgeState(path string, s bridgeState) error {
	raw, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err = os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
func postJSON(ctx context.Context, endpoint, token, hostID string, requestBody, responseBody any) error {
	raw, _ := json.Marshal(requestBody)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	if hostID != "" {
		req.Header.Set("X-Harness-Host-ID", hostID)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("relay returned %s: %s", resp.Status, strings.TrimSpace(string(body)))
	}
	return json.NewDecoder(resp.Body).Decode(responseBody)
}
func relayWebSocketURL(relayURL, role, id string) (string, error) {
	parsed, err := url.Parse(relayURL)
	if err != nil {
		return "", err
	}
	if parsed.Scheme == "https" {
		parsed.Scheme = "wss"
	} else {
		parsed.Scheme = "ws"
	}
	parsed.Path = "/v1/ws"
	query := parsed.Query()
	query.Set("role", role)
	query.Set("id", id)
	parsed.RawQuery = query.Encode()
	return parsed.String(), nil
}
func mustJSON(value any) json.RawMessage { raw, _ := json.Marshal(value); return raw }
func defaultStatePath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./bridge.json"
	}
	return filepath.Join(home, ".harness-remote", "bridge.json")
}
func hostname() string { name, _ := os.Hostname(); return name }
func usage()           { fmt.Fprintln(os.Stderr, "usage: harness-bridge <init|recover|pair|serve> [options]") }
