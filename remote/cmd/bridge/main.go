package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"
	appserverrpc "github.com/harnessapk/remote/internal/appserver"
	"github.com/harnessapk/remote/internal/backend"
	"github.com/harnessapk/remote/internal/commandcache"
	"github.com/harnessapk/remote/internal/completion"
	"github.com/harnessapk/remote/internal/journal"
	"github.com/harnessapk/remote/internal/protocol"
	runstate "github.com/harnessapk/remote/internal/run"
	bridgestate "github.com/harnessapk/remote/internal/state"
	"github.com/harnessapk/remote/internal/workspace"
	qrcode "github.com/skip2/go-qrcode"
)

type bridgeState = bridgestate.BridgeData

type bridge struct {
	mu             sync.Mutex
	writeMu        sync.Mutex
	state          bridgeState
	path           string
	conn           *websocket.Conn
	backends       map[string]backend.Backend
	backendOrder   []string
	journal        *journal.Store
	commandCache   *commandcache.Store
	routes         *runstate.RouteStore
	terminals      *completion.TerminalRunStore
	workspaces     *workspace.Registry
	seen           map[string]time.Time
	updateState    func(string, func(*bridgeState) error) error
	backendBackoff time.Duration
}

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
	case "workspace":
		runWorkspace(statePath, os.Args[2:])
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
	if err := bridgestate.UpdateBridge(*statePath, func(current *bridgeState) error {
		current.Pending[pairing.Ticket] = protocol.EncodeSecret(secret)
		state = *current
		return nil
	}); err != nil {
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

func runWorkspace(defaultPath string, args []string) {
	flags := flag.NewFlagSet("workspace", flag.ExitOnError)
	statePath := flags.String("state", defaultPath, "bridge state file")
	_ = flags.Parse(args)
	remaining := flags.Args()
	if len(remaining) == 0 {
		fmt.Fprintln(os.Stderr, "usage: harness-bridge workspace [--state path] <add|remove|list> [directory]")
		return
	}
	state, err := loadBridgeState(*statePath)
	if err != nil {
		log.Fatal(err)
	}
	switch remaining[0] {
	case "list":
		for _, cwd := range state.RegisteredWorkspaces {
			fmt.Println(cwd)
		}
	case "add", "remove":
		if len(remaining) != 2 {
			log.Fatal("workspace add/remove requires one directory")
		}
		cwd, err := canonicalWorkspaceDirectory(remaining[1])
		if err != nil {
			log.Fatal(err)
		}
		if err := bridgestate.UpdateBridge(*statePath, func(current *bridgeState) error {
			paths := make(map[string]struct{}, len(current.RegisteredWorkspaces)+1)
			for _, registered := range current.RegisteredWorkspaces {
				paths[registered] = struct{}{}
			}
			if remaining[0] == "add" {
				paths[cwd] = struct{}{}
			} else {
				delete(paths, cwd)
			}
			current.RegisteredWorkspaces = current.RegisteredWorkspaces[:0]
			for registered := range paths {
				current.RegisteredWorkspaces = append(current.RegisteredWorkspaces, registered)
			}
			sort.Strings(current.RegisteredWorkspaces)
			state = *current
			return nil
		}); err != nil {
			log.Fatal(err)
		}
	default:
		log.Fatalf("unsupported workspace action %q", remaining[0])
	}
}

func canonicalWorkspaceDirectory(path string) (string, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	canonical, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(canonical)
	if err != nil {
		return "", err
	}
	if !info.IsDir() {
		return "", errors.New("workspace path is not a directory")
	}
	return filepath.Clean(canonical), nil
}

func runServe(defaultPath string, args []string) {
	flags := flag.NewFlagSet("serve", flag.ExitOnError)
	statePath := flags.String("state", defaultPath, "bridge state file")
	codex := flags.String("codex", "codex", "Codex executable")
	var backendSpecs stringList
	flags.Var(&backendSpecs, "backend", "backend <id> or <id>=<executable>; repeatable (default: codex)")
	_ = flags.Parse(args)
	specs, err := parseBackendSpecs(backendSpecs, *codex)
	if err != nil {
		log.Fatal(err)
	}
	state, err := loadBridgeState(*statePath)
	if err != nil {
		log.Fatal(err)
	}
	persistedBackends := make([]bridgestate.BackendConfig, 0, len(specs))
	for _, spec := range specs {
		persistedBackends = append(persistedBackends, bridgestate.BackendConfig{
			ID: spec.ID, Name: spec.Name, Capabilities: spec.Capabilities,
		})
	}
	if err := bridgestate.UpdateBridge(*statePath, func(current *bridgeState) error {
		current.Backends = persistedBackends
		state = *current
		return nil
	}); err != nil {
		log.Fatal(err)
	}
	for {
		journalKey, err := base64.RawURLEncoding.DecodeString(state.JournalKey)
		if err != nil {
			log.Printf("decode journal key: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}
		journalStore, err := journal.Open(filepath.Join(filepath.Dir(*statePath), "logical-events.log"), journalKey, 10_000)
		if err != nil {
			log.Printf("open logical journal: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}
		commandStore, err := commandcache.Open(filepath.Join(filepath.Dir(*statePath), "commands.json"))
		if err != nil {
			log.Printf("open command cache: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}
		routeStore, err := runstate.OpenRoutes(filepath.Join(filepath.Dir(*statePath), "routes.json"))
		if err != nil {
			log.Printf("open run routes: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}
		terminalStore, err := completion.OpenTerminalRunStore(filepath.Join(filepath.Dir(*statePath), "terminal-runs.json"))
		if err != nil {
			log.Printf("open terminal run ledger: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}
		workspaceRegistry, err := workspace.OpenRegistry(filepath.Join(filepath.Dir(*statePath), "workspaces.json"))
		if err != nil {
			log.Printf("open workspace registry: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}
		b := &bridge{
			state: state, path: *statePath, journal: journalStore,
			commandCache: commandStore, routes: routeStore, terminals: terminalStore, workspaces: workspaceRegistry,
			seen: map[string]time.Time{},
		}
		if err := b.recoverThreadContinuationsFromCommands(); err != nil {
			log.Printf("recover lazy continuations: %v", err)
		}
		serveCtx, cancelServe := context.WithCancel(context.Background())
		if err := b.run(serveCtx, specs); err != nil {
			log.Printf("bridge disconnected: %v", err)
		}
		cancelServe()
		b.closeBackends()
		state, _ = loadBridgeState(*statePath)
		time.Sleep(3 * time.Second)
	}
}

// stringList collects one repeatable string flag.
type stringList []string

func (s *stringList) String() string { return strings.Join(*s, ",") }
func (s *stringList) Set(value string) error {
	*s = append(*s, value)
	return nil
}

// parseBackendSpecs turns --backend values into ordered backend specs. A bare
// id resolves a known backend (codex); <id>=<executable> registers any
// executable speaking the canonical app-server protocol under that id.
func parseBackendSpecs(raw []string, codexExec string) ([]backend.Spec, error) {
	if len(raw) == 0 {
		raw = []string{"codex"}
	}
	specs := make([]backend.Spec, 0, len(raw))
	seen := map[string]struct{}{}
	for _, item := range raw {
		id, exec := item, ""
		if eq := strings.IndexByte(item, '='); eq >= 0 {
			id, exec = item[:eq], item[eq+1:]
		}
		id = backend.NormalizeID(strings.TrimSpace(id))
		if id == "" {
			return nil, fmt.Errorf("backend id is required in %q", item)
		}
		if _, duplicate := seen[id]; duplicate {
			return nil, fmt.Errorf("duplicate backend %q", id)
		}
		seen[id] = struct{}{}
		if strings.TrimSpace(exec) == "" {
			switch id {
			case protocol.DefaultBackendID:
				exec = codexExec
			case "dsh":
				exec = "dsh"
			default:
				return nil, fmt.Errorf("unknown backend %q: use <id>=<executable>", id)
			}
		}
		name := id
		capabilities := backend.CodexCapabilities()
		args := []string(nil)
		switch id {
		case protocol.DefaultBackendID:
			name = "Codex"
		case "dsh":
			name = "DeepSeek Harness"
			capabilities = backend.DSHCapabilities()
			args = []string{"--profile", "appserver", "--listen", "stdio://"}
		}
		specs = append(specs, backend.Spec{
			ID: id, Name: name, Capabilities: capabilities, Exec: exec, Args: args,
		})
	}
	return specs, nil
}

func executeCachedCommand(
	cache *commandcache.Store,
	commandID, commandType, payloadSHA256 string,
	call func() (string, json.RawMessage, error),
) (commandcache.Record, error) {
	record, execute, err := cache.Begin(commandID, commandType, payloadSHA256)
	if err != nil || !execute {
		return record, err
	}
	eventID, result, err := call()
	if err != nil {
		_, persistErr := cache.Fail(commandID, err)
		if persistErr != nil {
			return commandcache.Record{}, errors.Join(err, persistErr)
		}
		return commandcache.Record{}, err
	}
	return cache.Complete(commandID, eventID, result)
}

func (b *bridge) run(ctx context.Context, specs []backend.Spec) error {
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
	errorsCh := make(chan error, 2)
	ready := make(chan backend.Backend, len(specs))
	for _, spec := range specs {
		go b.superviseBackend(ctx, spec, ready, func(spec backend.Spec) (backend.Backend, error) {
			return backend.StartCodex(spec)
		})
	}
	// Wait until every backend registered once so the first host.status is
	// truthful, but never block the relay loop forever on a broken backend
	// spec: supervisors keep retrying and host.status reports availability.
	remaining := len(specs)
	readyDeadline := time.After(60 * time.Second)
	for remaining > 0 {
		select {
		case <-ready:
			remaining--
		case <-readyDeadline:
			log.Printf("timed out waiting for backend readiness (%d pending); continuing", remaining)
			remaining = 0
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	close(ready)
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
	if err := b.recoverTurnStartRoutes(); err != nil {
		log.Printf("recover turn/start routes after bridge connection: %v", err)
	}
	if err := b.recoverTerminalRuns(ctx, ""); err != nil {
		log.Printf("recover terminal runs after bridge connection: %v", err)
	}
	for {
		select {
		case err := <-errorsCh:
			return err
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

// superviseBackend starts, initializes, and restarts one backend process
// independently: a crash only affects its own routes/approvals (via its own
// process epoch) and never tears down the relay connection or other backends.
func (b *bridge) superviseBackend(
	ctx context.Context,
	spec backend.Spec,
	ready chan<- backend.Backend,
	start func(backend.Spec) (backend.Backend, error),
) {
	backoff := b.backendBackoff
	if backoff <= 0 {
		backoff = 3 * time.Second
	}
	announced := false
	for {
		bd, err := start(spec)
		if err != nil {
			log.Printf("start backend %s: %v", spec.ID, err)
			if !sleepCtx(ctx, backoff) {
				return
			}
			continue
		}
		initCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		_, initErr := bd.Call(initCtx, "initialize", map[string]any{
			"clientInfo": map[string]string{
				"name": "harness_remote_bridge", "title": "Harness Remote Bridge", "version": "0.2.0",
			},
			"capabilities": map[string]bool{"experimentalApi": true},
		})
		if initErr == nil {
			initErr = bd.Notify(initCtx, "initialized", map[string]any{})
		}
		cancel()
		if initErr != nil {
			log.Printf("initialize backend %s: %v", spec.ID, initErr)
			_ = bd.Close()
			if !sleepCtx(ctx, backoff) {
				return
			}
			continue
		}
		bd.Start(ctx)
		if err := b.routes.BeginProcessEpoch(bd.ID(), bd.ProcessEpoch()); err != nil {
			log.Printf("begin process epoch for backend %s: %v", spec.ID, err)
		}
		b.registerBackend(bd)
		if !announced {
			announced = true
			select {
			case ready <- bd:
			case <-ctx.Done():
				return
			}
		}
		go func(bd backend.Backend) {
			for message := range bd.Messages() {
				b.handleAppServer(ctx, message)
			}
		}(bd)
		exitErr := <-bd.Done()
		b.unregisterBackend(bd.ID())
		_ = bd.Close()
		log.Printf("backend %s exited (%v); restarting in %s", spec.ID, exitErr, backoff)
		if !sleepCtx(ctx, backoff) {
			return
		}
	}
}

func sleepCtx(ctx context.Context, duration time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(duration):
		return true
	}
}

func (b *bridge) registerBackend(bd backend.Backend) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.backends == nil {
		b.backends = map[string]backend.Backend{}
	}
	if _, exists := b.backends[bd.ID()]; !exists {
		b.backendOrder = append(b.backendOrder, bd.ID())
	}
	b.backends[bd.ID()] = bd
}

func (b *bridge) unregisterBackend(id string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.backends, id)
}

// backendFor resolves the backend for a command/event; empty id means the
// default backend.
func (b *bridge) backendFor(id string) backend.Backend {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.backends[backend.NormalizeID(id)]
}

// closeBackends stops every supervised backend (used on bridge teardown).
func (b *bridge) closeBackends() {
	b.mu.Lock()
	backends := make([]backend.Backend, 0, len(b.backends))
	for _, bd := range b.backends {
		backends = append(backends, bd)
	}
	b.backends = map[string]backend.Backend{}
	b.backendOrder = nil
	b.mu.Unlock()
	for _, bd := range backends {
		_ = bd.Close()
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
		var err error
		secretEncoded, err = b.claimPairingSecretLocked(wire.PairingTicket, wire.DeviceID)
		if err != nil {
			b.mu.Unlock()
			return err
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
			_ = b.persistStateLocked()
		}
	}
}

func (b *bridge) executeCommand(ctx context.Context, deviceID string, command protocol.Command) error {
	backendID := backend.NormalizeID(command.BackendID)
	switch command.Type {
	case "host.status":
		return b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "host.status", RequestID: command.RequestID, Message: "online",
			Payload: b.hostStatusPayload(), CreatedAt: time.Now().UnixMilli(),
		}, "")
	case "event.ack":
		if b.journal == nil {
			return errors.New("logical journal is unavailable")
		}
		return b.journal.Ack(b.state.HostID, deviceID, command.HighestContiguousSequence)
	}
	bd := b.backendFor(backendID)
	if bd == nil {
		return b.sendBackendEvent(ctx, backendID, deviceID, protocol.Event{
			Type: "error", RequestID: command.RequestID,
			Message: "后端不可用：" + backendID, CreatedAt: time.Now().UnixMilli(),
		}, "")
	}
	switch command.Type {
	case "thread.list":
		return b.requestMobileThreadList(ctx, deviceID, command, bd)
	case "thread.summary":
		return b.requestMobileThreadSummary(ctx, deviceID, command, bd)
	case "workspace.list":
		return b.requestWorkspaceCandidates(ctx, deviceID, command, bd)
	case "run.start":
		return b.startRun(ctx, deviceID, command, bd)
	case "run.steer", "run.interrupt":
		return b.controlRun(ctx, deviceID, command, bd)
	case "sync.resume":
		return b.resumeLogicalEvents(ctx, deviceID, command)
	case "run.snapshot":
		return b.sendRunSnapshot(ctx, deviceID, command.OpenRunIDs, command.RequestID)
	case "thread.read":
		return b.requestMobileThreadHistory(ctx, deviceID, command, bd)
	case "thread.start":
		return b.requestAppServer(ctx, deviceID, command, bd, "thread/start", map[string]any{"cwd": command.CWD})
	case "turn.start":
		return b.requestTurnStart(ctx, deviceID, command, bd, legacyTurnStartParams(command))
	case "turn.steer":
		if err := b.claimThread(command, deviceID); err != nil {
			return err
		}
		return b.requestTurnAppServer(ctx, deviceID, command, bd, "turn/steer", map[string]any{"threadId": command.ThreadID, "expectedTurnId": command.ExpectedTurnID, "input": []map[string]string{{"type": "text", "text": command.Text}}})
	case "turn.interrupt":
		return b.requestAppServer(ctx, deviceID, command, bd, "turn/interrupt", map[string]string{"threadId": command.ThreadID, "turnId": command.TurnID})
	case "approval.respond":
		return b.respondApproval(ctx, deviceID, command, bd)
	case "rpc":
		return b.requestAppServer(ctx, deviceID, command, bd, command.Method, decodeRaw(command.Params))
	default:
		return b.sendBackendEvent(ctx, backendID, deviceID, protocol.Event{
			Type: "error", RequestID: command.RequestID,
			Message: "unsupported command: " + command.Type, CreatedAt: time.Now().UnixMilli(),
		}, "")
	}
}

// hostStatusPayload reports the legacy host-level capabilities (the default
// backend's, keeping old clients' behavior) plus the per-backend list.
func (b *bridge) hostStatusPayload() json.RawMessage {
	b.mu.Lock()
	order := append([]string(nil), b.backendOrder...)
	b.mu.Unlock()
	backends := make([]protocol.BackendInfo, 0, len(order))
	var hostCapabilities []string
	for _, id := range order {
		bd := b.backendFor(id)
		if bd == nil {
			continue
		}
		caps := bd.Capabilities()
		backends = append(backends, protocol.BackendInfo{ID: bd.ID(), Name: bd.Name(), Capabilities: caps})
		if id == protocol.DefaultBackendID {
			hostCapabilities = caps
		}
	}
	return mustJSON(protocol.HostStatusPayload{
		SchemaVersion: 1, Capabilities: hostCapabilities, Backends: backends,
	})
}

const (
	mobileThreadHistoryPageSize    = 8
	maxMobilePaginatedTextBytes    = 24 << 10
	maxMobilePaginatedItemsPerTurn = 2
	maxMobileThreadSummaryBytes    = 4 << 10
	maxDirectResumeThreadBytes     = 256 << 20
)

func (b *bridge) requestMobileThreadList(ctx context.Context, deviceID string, command protocol.Command, bd backend.Backend) error {
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		result, err := bd.Call(callCtx, "thread/list", map[string]any{
			"limit": 20, "sortKey": "updated_at", "sortDirection": "desc",
			"sourceKinds": []string{"cli", "vscode", "exec", "appServer"},
		})
		if err == nil {
			if rememberErr := b.rememberThreadContinuationNames(result); rememberErr != nil {
				log.Printf("remember lazy continuation names: %v", rememberErr)
			}
			result, err = mobileThreadListResult(result, b.threadContinuationsSnapshot())
		}
		payload := map[string]any{"result": json.RawMessage(result)}
		if err != nil {
			payload = map[string]any{"error": err.Error()}
		}
		_ = b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "rpc.response", RequestID: command.RequestID,
			Payload: mustJSON(payload), CreatedAt: time.Now().UnixMilli(),
		}, "")
	}()
	return nil
}

func (b *bridge) rememberThreadContinuationNames(raw json.RawMessage) error {
	var response struct {
		Data []map[string]any `json:"data"`
	}
	if json.Unmarshal(raw, &response) != nil {
		return nil
	}
	names := map[string]string{}
	for _, thread := range response.Data {
		id, _ := thread["id"].(string)
		name, _ := thread["name"].(string)
		if strings.TrimSpace(name) == "" {
			name, _ = thread["preview"].(string)
		}
		if id != "" && strings.TrimSpace(name) != "" {
			names[id] = name
		}
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	previous := cloneBridgeState(b.state)
	changed := false
	for rootThreadID, record := range b.state.ThreadContinuations {
		if strings.TrimSpace(record.Name) == "" && strings.TrimSpace(names[rootThreadID]) != "" {
			record.Name = names[rootThreadID]
			b.state.ThreadContinuations[rootThreadID] = record
			changed = true
		}
	}
	if !changed {
		return nil
	}
	if err := b.persistStateLocked(); err != nil {
		b.state = previous
		return err
	}
	return nil
}

func mobileThreadListResult(raw json.RawMessage, continuations map[string]bridgestate.ThreadContinuation) (json.RawMessage, error) {
	var response map[string]any
	if err := json.Unmarshal(raw, &response); err != nil {
		return nil, fmt.Errorf("decode thread list for mobile: %w", err)
	}
	rawData, _ := response["data"].([]any)
	data := make([]map[string]any, 0, len(rawData))
	for _, rawThread := range rawData {
		if thread, ok := rawThread.(map[string]any); ok {
			data = append(data, thread)
		}
	}
	byID := make(map[string]map[string]any, len(data))
	for _, thread := range data {
		if id, _ := thread["id"].(string); id != "" {
			byID[id] = thread
		}
	}
	hidden := map[string]struct{}{}
	for _, record := range continuations {
		if len(record.ThreadIDs) < 2 {
			continue
		}
		latestID := record.ThreadIDs[len(record.ThreadIDs)-1]
		latest := byID[latestID]
		if latest == nil {
			continue
		}
		name := strings.TrimSpace(record.Name)
		if name == "" {
			if root := byID[record.RootThreadID]; root != nil {
				name, _ = root["name"].(string)
				if strings.TrimSpace(name) == "" {
					name, _ = root["preview"].(string)
				}
			}
		}
		if strings.TrimSpace(name) != "" {
			latest["name"] = name
		}
		latest["continuedFromThreadId"] = record.RootThreadID
		for _, id := range record.ThreadIDs[:len(record.ThreadIDs)-1] {
			hidden[id] = struct{}{}
		}
	}
	filtered := data[:0]
	for _, thread := range data {
		id, _ := thread["id"].(string)
		if _, shouldHide := hidden[id]; !shouldHide {
			filtered = append(filtered, thread)
		}
	}
	response["data"] = filtered
	return json.Marshal(response)
}

func mobileThreadSummaryResult(threadID string, pageRaw json.RawMessage) (json.RawMessage, error) {
	var page struct {
		Data []any `json:"data"`
	}
	if err := json.Unmarshal(pageRaw, &page); err != nil {
		return nil, fmt.Errorf("decode latest thread summary for mobile: %w", err)
	}
	latestUserMessage := ""
	execution := map[string]any{"state": "UNKNOWN"}
	executionSet := false
	for _, rawTurn := range page.Data {
		turn, ok := rawTurn.(map[string]any)
		if !ok {
			continue
		}
		if !executionSet {
			turnID, _ := turn["id"].(string)
			status, _ := turn["status"].(string)
			startedAt, _ := jsonNumberInt64(turn["startedAt"])
			completedAt, completed := jsonNumberInt64(turn["completedAt"])
			state := map[string]string{
				"completed":  "COMPLETED",
				"failed":     "FAILED",
				"inProgress": "RUNNING",
			}[status]
			if status == "interrupted" {
				state = "INTERRUPTED"
				if !completed {
					state = "RUNNING"
				}
			}
			if state == "" {
				state = "UNKNOWN"
			}
			execution = map[string]any{
				"state": state, "turnId": turnID, "startedAt": startedAt, "completedAt": nil,
			}
			if completed {
				execution["completedAt"] = completedAt
			}
			executionSet = true
		}
		items, _ := turn["items"].([]any)
		for _, rawItem := range items {
			item, ok := rawItem.(map[string]any)
			if !ok || item["type"] != "userMessage" {
				continue
			}
			latestUserMessage = truncateUTF8(strings.TrimSpace(mobileMessageText(item)), maxMobileThreadSummaryBytes)
			if latestUserMessage != "" {
				break
			}
		}
		if latestUserMessage != "" {
			break
		}
	}
	return json.Marshal(map[string]any{
		"threadId":          threadID,
		"latestUserMessage": latestUserMessage,
		"execution":         execution,
	})
}

func jsonNumberInt64(value any) (int64, bool) {
	switch number := value.(type) {
	case float64:
		return int64(number), true
	case json.Number:
		parsed, err := number.Int64()
		return parsed, err == nil
	default:
		return 0, false
	}
}

func (b *bridge) requestMobileThreadSummary(ctx context.Context, deviceID string, command protocol.Command, bd backend.Backend) error {
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		if command.ThreadID == "" {
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "rpc.response", RequestID: command.RequestID,
				Payload: mustJSON(map[string]any{"error": "thread.summary requires threadId"}), CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		page, err := bd.Call(callCtx, "thread/turns/list", map[string]any{
			"threadId": command.ThreadID, "limit": 3, "sortDirection": "desc", "itemsView": "summary",
		})
		if err == nil {
			page, err = mobileThreadSummaryResult(command.ThreadID, page)
		}
		if err != nil {
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "rpc.response", RequestID: command.RequestID,
				Payload: mustJSON(map[string]any{"error": err.Error()}), CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		_ = b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "rpc.response", RequestID: command.RequestID,
			Payload: mustJSON(map[string]any{"result": json.RawMessage(page)}), CreatedAt: time.Now().UnixMilli(),
		}, "")
	}()
	return nil
}

func (b *bridge) requestMobileThreadHistory(ctx context.Context, deviceID string, command protocol.Command, bd backend.Backend) error {
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		record := b.continuationForLatestThread(command.ThreadID)
		historyThreadID, historyCursor, cursorErr := continuationHistoryRequest(
			command.ThreadID, mobileHistoryCursor(command.Params), record,
		)
		metadata, err := bd.Call(callCtx, "thread/read", map[string]any{
			"threadId": command.ThreadID, "includeTurns": false,
		})
		if err == nil && cursorErr != nil {
			err = cursorErr
		}
		if err == nil {
			params := map[string]any{
				"threadId": historyThreadID, "limit": mobileThreadHistoryPageSize,
				"sortDirection": "desc", "itemsView": "summary",
			}
			if historyCursor != "" {
				params["cursor"] = historyCursor
			}
			var page json.RawMessage
			page, err = bd.Call(callCtx, "thread/turns/list", params)
			if err == nil {
				metadata, err = mobileThreadHistoryResult(metadata, page)
				if err == nil && record != nil {
					metadata, err = overrideMobileOlderCursor(
						metadata, continuationOlderCursor(*record, historyThreadID, threadPageNextCursor(page)),
					)
				}
			}
		}
		if err != nil {
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "rpc.response", RequestID: command.RequestID,
				Payload: mustJSON(map[string]any{"error": err.Error()}), CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		_ = b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "rpc.response", RequestID: command.RequestID,
			Payload: mustJSON(map[string]any{"result": json.RawMessage(metadata)}), CreatedAt: time.Now().UnixMilli(),
		}, "")
	}()
	return nil
}

const mobileContinuationCursorPrefix = "harness-continuation-v1:"

type mobileContinuationCursor struct {
	ThreadID string `json:"threadId"`
	Cursor   string `json:"cursor,omitempty"`
}

func continuationHistoryRequest(
	requestedThreadID string,
	rawCursor string,
	record *bridgestate.ThreadContinuation,
) (string, string, error) {
	if !strings.HasPrefix(rawCursor, mobileContinuationCursorPrefix) {
		return requestedThreadID, rawCursor, nil
	}
	if record == nil {
		return "", "", errors.New("continuation history is unavailable")
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(rawCursor, mobileContinuationCursorPrefix))
	if err != nil {
		return "", "", errors.New("continuation history cursor is invalid")
	}
	var cursor mobileContinuationCursor
	if json.Unmarshal(raw, &cursor) != nil || cursor.ThreadID == "" {
		return "", "", errors.New("continuation history cursor is invalid")
	}
	for _, threadID := range record.ThreadIDs {
		if cursor.ThreadID == threadID {
			return cursor.ThreadID, cursor.Cursor, nil
		}
	}
	return "", "", errors.New("continuation history cursor points outside its conversation")
}

func continuationOlderCursor(
	record bridgestate.ThreadContinuation,
	historyThreadID string,
	nextCursor *string,
) *string {
	cursor := mobileContinuationCursor{ThreadID: historyThreadID}
	if nextCursor != nil && *nextCursor != "" {
		cursor.Cursor = *nextCursor
	} else {
		index := -1
		for candidateIndex, threadID := range record.ThreadIDs {
			if threadID == historyThreadID {
				index = candidateIndex
				break
			}
		}
		if index <= 0 {
			return nil
		}
		cursor.ThreadID = record.ThreadIDs[index-1]
	}
	raw, err := json.Marshal(cursor)
	if err != nil {
		return nil
	}
	encoded := mobileContinuationCursorPrefix + base64.RawURLEncoding.EncodeToString(raw)
	return &encoded
}

func threadPageNextCursor(raw json.RawMessage) *string {
	var page struct {
		NextCursor *string `json:"nextCursor"`
	}
	if json.Unmarshal(raw, &page) != nil {
		return nil
	}
	return page.NextCursor
}

func overrideMobileOlderCursor(raw json.RawMessage, olderCursor *string) (json.RawMessage, error) {
	var response map[string]any
	if err := json.Unmarshal(raw, &response); err != nil {
		return nil, err
	}
	history, _ := response["mobileHistory"].(map[string]any)
	if history == nil {
		history = map[string]any{}
		response["mobileHistory"] = history
	}
	history["olderCursor"] = olderCursor
	history["hasOlder"] = olderCursor != nil && *olderCursor != ""
	return json.Marshal(response)
}

func mobileHistoryCursor(raw json.RawMessage) string {
	var params struct {
		Cursor string `json:"cursor"`
	}
	_ = json.Unmarshal(raw, &params)
	return params.Cursor
}

func mobileThreadHistoryResult(metadataRaw, pageRaw json.RawMessage) (json.RawMessage, error) {
	var metadata map[string]any
	if err := json.Unmarshal(metadataRaw, &metadata); err != nil {
		return nil, fmt.Errorf("decode thread metadata for mobile: %w", err)
	}
	thread, ok := metadata["thread"].(map[string]any)
	if !ok {
		return nil, errors.New("thread metadata for mobile is missing thread")
	}
	var page struct {
		Data       []any   `json:"data"`
		NextCursor *string `json:"nextCursor"`
	}
	if err := json.Unmarshal(pageRaw, &page); err != nil {
		return nil, fmt.Errorf("decode paginated thread history for mobile: %w", err)
	}
	for left, right := 0, len(page.Data)-1; left < right; left, right = left+1, right-1 {
		page.Data[left], page.Data[right] = page.Data[right], page.Data[left]
	}
	thread["turns"] = projectMobilePaginatedTurns(page.Data)
	metadata["mobileHistory"] = map[string]any{
		"olderCursor": page.NextCursor,
		"hasOlder":    page.NextCursor != nil && *page.NextCursor != "",
	}
	merged, err := json.Marshal(metadata)
	if err != nil {
		return nil, fmt.Errorf("encode paginated thread history for mobile: %w", err)
	}
	return mobileThreadReadResult(merged)
}

func projectMobilePaginatedTurns(turns []any) []map[string]any {
	projectedTurns := make([]map[string]any, 0, len(turns))
	for _, rawTurn := range turns {
		turn, ok := rawTurn.(map[string]any)
		if !ok {
			continue
		}
		items, _ := turn["items"].([]any)
		if len(items) > maxMobilePaginatedItemsPerTurn {
			items = []any{items[0], items[len(items)-1]}
		}
		projectedItems := make([]map[string]any, 0, len(items))
		for _, rawItem := range items {
			if item, ok := rawItem.(map[string]any); ok {
				projectedItems = append(projectedItems, mobileTimelineItemWithTextLimit(item, maxMobilePaginatedTextBytes))
			}
		}
		projectedTurn := map[string]any{"items": projectedItems}
		copyJSONFields(projectedTurn, turn, "id", "status", "itemsView")
		projectedTurns = append(projectedTurns, projectedTurn)
	}
	return projectedTurns
}

func (b *bridge) requestWorkspaceCandidates(ctx context.Context, deviceID string, command protocol.Command, bd backend.Backend) error {
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		result, err := bd.Call(callCtx, "thread/list", map[string]any{
			"limit": 50, "sortKey": "updated_at", "sortDirection": "desc",
			"sourceKinds": []string{"cli", "vscode", "exec", "appServer"},
		})
		if err != nil {
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "error", RequestID: command.RequestID,
				Message: "读取 Mac 工作区失败", CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		var response struct {
			Data []struct {
				CWD       string `json:"cwd"`
				UpdatedAt int64  `json:"updatedAt"`
			} `json:"data"`
		}
		if err := json.Unmarshal(result, &response); err != nil {
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "error", RequestID: command.RequestID,
				Message: "Mac 工作区响应格式无效", CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		b.mu.Lock()
		secretEncoded := b.state.DeviceSecrets[deviceID]
		registered := append([]string(nil), b.state.RegisteredWorkspaces...)
		b.mu.Unlock()
		secret, err := protocol.DecodeSecret(secretEncoded)
		if err != nil {
			return
		}
		sources := make([]workspace.Source, 0, len(response.Data))
		for _, thread := range response.Data {
			if thread.CWD != "" {
				sources = append(sources, workspace.Source{CWD: thread.CWD, LastUsedAt: thread.UpdatedAt})
			}
		}
		candidates, err := workspace.InspectCandidates(secret, sources, registered)
		if err != nil {
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "error", RequestID: command.RequestID,
				Message: "检查 Mac 工作区失败", CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		if err := b.workspaces.PutCandidates(deviceID, candidates); err != nil {
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "error", RequestID: command.RequestID,
				Message: "保存 Mac 工作区候选失败", CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		_ = b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "workspace.candidates", RequestID: command.RequestID,
			Payload: mustJSON(candidates), CreatedAt: time.Now().UnixMilli(),
		}, "")
	}()
	return nil
}

func (b *bridge) startRun(ctx context.Context, deviceID string, command protocol.Command, bd backend.Backend) error {
	if command.CommandID == "" || command.RequestID == "" || command.RunID == "" {
		return errors.New("run.start command identity is required")
	}
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
		defer cancel()
		b.mu.Lock()
		secretEncoded := b.state.DeviceSecrets[deviceID]
		b.mu.Unlock()
		secret, err := protocol.DecodeSecret(secretEncoded)
		if err != nil {
			return
		}
		coordinator := runstate.Coordinator{
			Cache: b.commandCache, Routes: b.routes, App: bd, HostID: b.state.HostID,
			ResolveWorkspace: b.workspaces.Resolve,
			InspectWorkspace: func(cwd string) (workspace.Candidate, error) {
				return workspace.Inspect(secret, cwd, time.Now().UnixMilli())
			},
			CaptureBaseline: workspace.CaptureBaseline,
			Emit:            b.emitLogicalEvent,
		}
		_, startErr := coordinator.Start(callCtx, runstate.StartCommand{
			CommandID: command.CommandID, RunID: command.RunID, BindingID: command.BindingID,
			WorkspaceID: command.WorkspaceID, DeviceID: deviceID,
			RepositoryFingerprint: command.RepositoryFingerprint, Objective: command.Objective,
		})
		if startErr == nil || errors.Is(startErr, runstate.ErrCommandInFlight) {
			return
		}
		if existing, ok := b.commandCache.Lookup(command.CommandID); ok && existing.ResultEventID != "" {
			return
		}
		eventType := "run.failed"
		latestLine := "Mac 未能启动任务"
		code := "RUN_START_FAILED"
		if errors.Is(startErr, runstate.ErrBindingMismatch) {
			latestLine = "Mac 工作区已变化，请重新绑定"
			code = "BINDING_MISMATCH"
		} else if errors.Is(startErr, runstate.ErrCommandUnknown) {
			eventType = "run.reconciling"
			latestLine = "正在核对 Mac 是否已启动任务"
			code = "START_OUTCOME_UNKNOWN"
		}
		payload := mustJSON(map[string]string{"code": code, "latestLine": latestLine})
		eventID, emitErr := b.emitLogicalEvent(ctx, deviceID, command.RunID, eventType, payload)
		if emitErr == nil {
			_, _ = b.commandCache.AttachResult(command.CommandID, eventID, payload)
		}
	}()
	return nil
}

func (b *bridge) controlRun(ctx context.Context, deviceID string, command protocol.Command, bd backend.Backend) error {
	if command.CommandID == "" || command.RequestID == "" || command.RunID == "" {
		return errors.New("run control command identity is required")
	}
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
		defer cancel()
		coordinator := runstate.ControlCoordinator{
			Cache: b.commandCache, Routes: b.routes, App: bd, Emit: b.emitLogicalEvent,
		}
		err := coordinator.Execute(callCtx, runstate.ControlCommand{
			Type: command.Type, CommandID: command.CommandID, RunID: command.RunID,
			DeviceID: deviceID, ExpectedTurnID: command.ExpectedTurnID, Text: command.Text,
		})
		if errors.Is(err, runstate.ErrControlOutcomeUnknown) {
			payload := mustJSON(map[string]string{
				"commandId": command.CommandID, "latestLine": "正在核对手机指令结果",
			})
			_, _ = b.emitLogicalEvent(ctx, deviceID, command.RunID, "run.control.unknown", payload)
			return
		}
		if err != nil {
			payload := mustJSON(map[string]string{
				"commandId": command.CommandID, "latestLine": "Mac 未能处理手机指令", "errorMessage": err.Error(),
			})
			_, _ = b.emitLogicalEvent(ctx, deviceID, command.RunID, "run.control.failed", payload)
		}
	}()
	return nil
}

func (b *bridge) emitLogicalEvent(
	ctx context.Context,
	deviceID, runID, eventType string,
	payload json.RawMessage,
) (string, error) {
	eventID, err := protocol.NewID()
	if err != nil {
		return "", err
	}
	backendID := ""
	if b.routes != nil {
		if route, ok := b.routes.ByRun(runID); ok {
			backendID = route.BackendID
		}
	}
	event := protocol.LogicalEvent{
		SchemaVersion: 1, EventID: eventID, HostID: b.state.HostID, DeviceID: deviceID,
		RunID: runID, BackendID: backendID, Type: eventType, Payload: payload, CreatedAt: time.Now().UnixMilli(),
	}
	err = b.sendLogicalEvent(ctx, event)
	if err != nil && b.journal.Has(eventID) {
		log.Printf("logical event %s journaled for replay after send failure: %v", eventID, err)
		return eventID, nil
	}
	return eventID, err
}

func (b *bridge) resumeLogicalEvents(ctx context.Context, deviceID string, command protocol.Command) error {
	if b.journal == nil {
		return errors.New("logical journal is unavailable")
	}
	knownHead := b.journal.Head(b.state.HostID, deviceID)
	if err := b.recoverTurnStartRoutes(); err != nil {
		log.Printf("recover turn/start routes on sync.resume: %v", err)
	}
	if err := b.recoverTerminalRuns(ctx, deviceID); err != nil {
		log.Printf("recover terminal runs on sync.resume: %v", err)
	}
	ackThrough := command.HighestContiguousSequence
	if ackThrough > knownHead {
		ackThrough = knownHead
	}
	if err := b.journal.Ack(b.state.HostID, deviceID, ackThrough); err != nil {
		return err
	}
	b.mu.Lock()
	forceSnapshot := b.state.NeedsInitialGapSnapshot
	b.mu.Unlock()
	if forceSnapshot || command.HighestContiguousSequence > knownHead || b.journal.RequiresSnapshot(b.state.HostID, deviceID, ackThrough) {
		return b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "sync.gap", RequestID: command.RequestID,
			Payload: mustJSON(map[string]any{
				"journalHead": b.journal.Head(b.state.HostID, deviceID),
				"openRunIds":  command.OpenRunIDs,
			}),
			CreatedAt: time.Now().UnixMilli(),
		}, "")
	}
	for _, event := range b.journal.Pending(b.state.HostID, deviceID) {
		if event.Sequence <= command.HighestContiguousSequence {
			continue
		}
		if err := b.transmitJournaledEvent(ctx, event); err != nil {
			return err
		}
	}
	return nil
}

type runSnapshot struct {
	RunID          string          `json:"runId"`
	BackendID      string          `json:"backendId,omitempty"`
	Status         string          `json:"status"`
	ThreadID       string          `json:"threadId,omitempty"`
	TurnID         string          `json:"turnId,omitempty"`
	LatestLine     string          `json:"latestLine"`
	CompletionJSON json.RawMessage `json:"completion,omitempty"`
	CompletedAt    int64           `json:"completedAt,omitempty"`
	ErrorMessage   string          `json:"errorMessage,omitempty"`
}

func (b *bridge) sendRunSnapshot(ctx context.Context, deviceID string, runIDs []string, requestID string) error {
	routes := b.routes.ByRuns(runIDs)
	runs := make([]runSnapshot, 0, len(routes))
	for _, route := range routes {
		snapshot, err := b.snapshotForRoute(ctx, route)
		if err != nil {
			return err
		}
		runs = append(runs, snapshot)
	}
	approvals := make([]map[string]any, 0)
	for _, approval := range b.routes.ApprovalsForRuns(runIDs) {
		approvals = append(approvals, map[string]any{
			"approvalId": approval.ApprovalID, "runId": approval.RunID,
			"backendId": approval.BackendID, "processEpoch": approval.ProcessEpoch,
			"serverRequestId": approval.ServerRequestID,
			"method":          approval.Method, "itemId": approval.ItemID,
			"actionType": approval.ActionType, "target": approval.Target,
			"commandPreview":     approval.CommandPreview,
			"details":            decodeRaw(json.RawMessage(approval.DetailsJSON)),
			"availableDecisions": appserverrpc.MobileApprovalDecisions(),
			"risk":               approval.Risk, "requestedAt": approval.RequestedAt,
			"status": approval.Status,
		})
	}
	defaultBackend := b.backendFor(protocol.DefaultBackendID)
	processEpoch := ""
	if defaultBackend != nil {
		processEpoch = defaultBackend.ProcessEpoch()
	}
	payload := runSnapshotPayload(
		b.state.HostID, deviceID, b.journal.Head(b.state.HostID, deviceID), processEpoch, runs, approvals,
	)
	if err := b.sendEvent(ctx, deviceID, protocol.Event{
		Type: "sync.snapshot", RequestID: requestID, Payload: payload, CreatedAt: time.Now().UnixMilli(),
	}, ""); err != nil {
		return err
	}
	b.mu.Lock()
	b.state.NeedsInitialGapSnapshot = false
	err := b.persistStateLocked()
	b.mu.Unlock()
	return err
}

// runSnapshotPayload builds the sync.snapshot event payload; extracted for
// contract tests (per-run and per-approval backendId).
func runSnapshotPayload(
	hostID, deviceID string,
	journalHead uint64,
	processEpoch string,
	runs []runSnapshot,
	approvals []map[string]any,
) json.RawMessage {
	return mustJSON(map[string]any{
		"hostId": hostID, "deviceId": deviceID,
		"journalHead":  journalHead,
		"processEpoch": processEpoch,
		"runs":         runs, "approvals": approvals,
	})
}

func snapshotStatus(raw json.RawMessage, turnID string) (string, string) {
	if turnID == "" {
		return "RECONCILING", "正在与 Mac 对账"
	}
	var result struct {
		Thread struct {
			Turns []struct {
				ID     string          `json:"id"`
				Status json.RawMessage `json:"status"`
			} `json:"turns"`
		} `json:"thread"`
	}
	if json.Unmarshal(raw, &result) != nil {
		return "RECONCILING", "正在与 Mac 对账"
	}
	for index := len(result.Thread.Turns) - 1; index >= 0; index-- {
		turn := result.Thread.Turns[index]
		if turnID != "" && turn.ID != turnID {
			continue
		}
		var object struct {
			Type string `json:"type"`
		}
		_ = json.Unmarshal(turn.Status, &object)
		status := object.Type
		if status == "" {
			_ = json.Unmarshal(turn.Status, &status)
		}
		switch strings.ToLower(status) {
		case "completed":
			return "COMPLETED", "任务已完成"
		case "failed":
			return "FAILED", "任务失败"
		case "interrupted", "cancelled", "canceled":
			return "CANCELLED", "任务已停止"
		case "inprogress", "running":
			return "RUNNING", "任务正在 Mac 上运行"
		default:
			return "RECONCILING", "正在与 Mac 对账"
		}
	}
	return "RECONCILING", "正在与 Mac 对账"
}

func (b *bridge) sendLogicalEvent(ctx context.Context, event protocol.LogicalEvent) error {
	if b.journal == nil {
		return errors.New("logical journal is unavailable")
	}
	if event.Sequence == 0 {
		stored, err := b.journal.AppendNext(event)
		if err != nil {
			return err
		}
		event = stored
	} else if err := b.journal.Append(event); err != nil {
		return err
	}
	return b.transmitJournaledEvent(ctx, event)
}

func (b *bridge) transmitJournaledEvent(ctx context.Context, event protocol.LogicalEvent) error {
	b.mu.Lock()
	encoded := b.state.DeviceSecrets[event.DeviceID]
	b.state.Sequences[event.DeviceID]++
	transportSequence := b.state.Sequences[event.DeviceID]
	stateErr := b.persistStateLocked()
	b.mu.Unlock()
	if stateErr != nil {
		return stateErr
	}
	secret, err := protocol.DecodeSecret(encoded)
	if err != nil {
		return err
	}
	wire, err := b.journal.Replay(event.EventID, secret, transportSequence)
	if err != nil {
		return err
	}
	raw, err := json.Marshal(wire)
	if err != nil {
		return err
	}
	b.writeMu.Lock()
	defer b.writeMu.Unlock()
	return b.conn.Write(ctx, websocket.MessageText, raw)
}

func (b *bridge) requestAppServer(
	ctx context.Context,
	deviceID string,
	command protocol.Command,
	bd backend.Backend,
	method string,
	params any,
) error {
	return b.requestAppServerAfter(ctx, deviceID, command, bd, method, params, nil)
}

func (b *bridge) requestTurnAppServer(
	ctx context.Context,
	deviceID string,
	command protocol.Command,
	bd backend.Backend,
	method string,
	params any,
) error {
	return b.requestAppServerAfter(ctx, deviceID, command, bd, method, params, func(result json.RawMessage) error {
		return b.backfillTurnRoute(command, result)
	})
}

type turnRPCOutcome string

const (
	turnRPCSucceeded turnRPCOutcome = "SUCCEEDED"
	turnRPCUnknown   turnRPCOutcome = "UNKNOWN"
	turnRPCFailed    turnRPCOutcome = "FAILED"
)

type turnStartReconciliation struct {
	Command protocol.Command `json:"command"`
	Result  json.RawMessage  `json:"result"`
}

func legacyTurnStartCacheIdentity(command protocol.Command) (string, string) {
	identity := firstNonEmpty(command.CommandID, command.RequestID)
	if identity == "" {
		return "", ""
	}
	raw, _ := json.Marshal(command)
	digest := sha256.Sum256(raw)
	return "legacy-turn-start:" + identity, hex.EncodeToString(digest[:])
}

func legacyTurnStartParams(command protocol.Command) map[string]any {
	return map[string]any{
		"threadId":            command.ThreadID,
		"input":               []map[string]string{{"type": "text", "text": command.Text}},
		"clientUserMessageId": firstNonEmpty(command.CommandID, command.RequestID),
	}
}

func isThreadNotFoundError(err error, threadID string) bool {
	return err != nil && threadID != "" && strings.Contains(err.Error(), "thread not found: "+threadID)
}

type persistedThreadMetadata struct {
	Thread struct {
		ID   string `json:"id"`
		Name string `json:"name"`
		CWD  string `json:"cwd"`
		Path string `json:"path"`
	} `json:"thread"`
}

func (b *bridge) recordThreadContinuation(sourceThreadID, continuationThreadID string, metadata persistedThreadMetadata) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	previous := cloneBridgeState(b.state)
	if b.state.ThreadContinuations == nil {
		b.state.ThreadContinuations = map[string]bridgestate.ThreadContinuation{}
	}
	record := bridgestate.ThreadContinuation{
		RootThreadID: sourceThreadID,
		ThreadIDs:    []string{sourceThreadID},
		Name:         metadata.Thread.Name,
		CWD:          metadata.Thread.CWD,
	}
	foundExisting := false
	for rootThreadID, candidate := range b.state.ThreadContinuations {
		for index, threadID := range candidate.ThreadIDs {
			if threadID != sourceThreadID {
				continue
			}
			record = candidate
			record.RootThreadID = rootThreadID
			record.ThreadIDs = append([]string(nil), candidate.ThreadIDs[:index+1]...)
			foundExisting = true
			break
		}
		if foundExisting {
			break
		}
	}
	if strings.TrimSpace(record.Name) == "" {
		record.Name = metadata.Thread.Name
	}
	if strings.TrimSpace(record.CWD) == "" {
		record.CWD = metadata.Thread.CWD
	}
	if len(record.ThreadIDs) == 0 || record.ThreadIDs[len(record.ThreadIDs)-1] != continuationThreadID {
		record.ThreadIDs = append(record.ThreadIDs, continuationThreadID)
	}
	record.UpdatedAt = time.Now().UnixMilli()
	b.state.ThreadContinuations[record.RootThreadID] = record
	if err := b.persistStateLocked(); err != nil {
		b.state = previous
		return err
	}
	return nil
}

func (b *bridge) recoverThreadContinuationsFromCommands() error {
	if b.commandCache == nil {
		return nil
	}
	records := b.commandCache.RecordsByTypeStatus("turn.start", commandcache.StatusSucceeded)
	sort.Slice(records, func(left, right int) bool { return records[left].UpdatedAt < records[right].UpdatedAt })
	for _, commandRecord := range records {
		var result struct {
			Continuation struct {
				ThreadID              string `json:"threadId"`
				ContinuedFromThreadID string `json:"continuedFromThreadId"`
				CWD                   string `json:"cwd"`
			} `json:"continuation"`
		}
		if json.Unmarshal(commandRecord.ResultJSON, &result) != nil || result.Continuation.ThreadID == "" || result.Continuation.ContinuedFromThreadID == "" {
			continue
		}
		metadata := persistedThreadMetadata{}
		metadata.Thread.CWD = result.Continuation.CWD
		if err := b.recordThreadContinuation(
			result.Continuation.ContinuedFromThreadID,
			result.Continuation.ThreadID,
			metadata,
		); err != nil {
			return err
		}
	}
	return nil
}

func (b *bridge) readPersistedThreadMetadata(ctx context.Context, bd backend.Backend, threadID string) (persistedThreadMetadata, bool, error) {
	metadata, err := bd.Call(ctx, "thread/read", map[string]any{
		"threadId": threadID, "includeTurns": false,
	})
	if err != nil {
		return persistedThreadMetadata{}, false, err
	}
	var response persistedThreadMetadata
	if err := json.Unmarshal(metadata, &response); err != nil {
		return persistedThreadMetadata{}, false, fmt.Errorf("decode persisted thread metadata: %w", err)
	}
	if response.Thread.Path == "" {
		return response, false, nil
	}
	info, err := os.Stat(response.Thread.Path)
	if err != nil {
		return response, false, nil
	}
	return response, info.Size() > maxDirectResumeThreadBytes, nil
}

func lazyContinuationHandoff(pageRaw json.RawMessage) (string, error) {
	var page struct {
		Data []any `json:"data"`
	}
	if err := json.Unmarshal(pageRaw, &page); err != nil {
		return "", fmt.Errorf("decode lazy continuation history: %w", err)
	}
	blocks := make([]string, 0, len(page.Data))
	usedBytes := 0
	for _, rawTurn := range page.Data {
		turn, ok := rawTurn.(map[string]any)
		if !ok {
			continue
		}
		items, _ := turn["items"].([]any)
		messages := make([]string, 0, 2)
		for _, rawItem := range items {
			item, ok := rawItem.(map[string]any)
			if !ok {
				continue
			}
			kind, _ := item["type"].(string)
			role := ""
			switch kind {
			case "userMessage":
				role = "用户"
			case "agentMessage":
				role = "Codex"
			default:
				continue
			}
			message := truncateUTF8(strings.TrimSpace(mobileMessageText(item)), maxMobileThreadSummaryBytes)
			if message != "" {
				messages = append(messages, role+"："+message)
			}
		}
		if len(messages) == 0 {
			continue
		}
		block := strings.Join(messages, "\n")
		separatorBytes := 0
		if len(blocks) > 0 {
			separatorBytes = 2
		}
		remaining := maxMobilePaginatedTextBytes - usedBytes - separatorBytes
		if remaining <= 0 {
			break
		}
		block = truncateUTF8(block, remaining)
		if block == "" {
			break
		}
		blocks = append(blocks, block)
		usedBytes += separatorBytes + len([]byte(block))
	}
	for left, right := 0, len(blocks)-1; left < right; left, right = left+1, right-1 {
		blocks[left], blocks[right] = blocks[right], blocks[left]
	}
	return strings.Join(blocks, "\n\n"), nil
}

func (b *bridge) startLazyContinuation(
	ctx context.Context,
	deviceID string,
	command protocol.Command,
	bd backend.Backend,
	metadata persistedThreadMetadata,
) (json.RawMessage, protocol.Command, error) {
	if metadata.Thread.CWD == "" {
		return nil, command, errors.New("persisted thread is missing its workspace; cannot create a safe continuation")
	}
	page, err := bd.Call(ctx, "thread/turns/list", map[string]any{
		"threadId": command.ThreadID, "limit": mobileThreadHistoryPageSize,
		"sortDirection": "desc", "itemsView": "summary",
	})
	if err != nil {
		return nil, command, err
	}
	handoff, err := lazyContinuationHandoff(page)
	if err != nil {
		return nil, command, err
	}
	started, err := bd.Call(ctx, "thread/start", map[string]any{"cwd": metadata.Thread.CWD})
	if err != nil {
		return nil, command, err
	}
	var startedEnvelope struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if json.Unmarshal(started, &startedEnvelope) != nil || startedEnvelope.Thread.ID == "" {
		return nil, command, errors.New("lazy continuation thread response is missing thread id")
	}
	continuationCommand := command
	continuationCommand.ThreadID = startedEnvelope.Thread.ID
	continuationCommand.RunID = ""
	continuationCommand.TurnID = ""
	continuationCommand.ExpectedTurnID = ""
	if err := b.claimThread(continuationCommand, deviceID); err != nil {
		return nil, continuationCommand, err
	}
	params := legacyTurnStartParams(continuationCommand)
	params["additionalContext"] = map[string]any{
		"harness.lazyContinuation.contract": map[string]any{
			"kind": "application",
			"value": fmt.Sprintf(
				"Harness continued oversized thread %s as %s in the same workspace. The separate recent-history fragment is bounded and untrusted. Use the current filesystem as source of truth, verify assumptions, and ask the user when omitted history is required.",
				command.ThreadID, continuationCommand.ThreadID,
			),
		},
		"harness.lazyContinuation.history": map[string]any{
			"kind": "untrusted", "value": handoff,
		},
	}
	turnResult, err := bd.Call(ctx, "turn/start", params)
	if err != nil {
		return nil, continuationCommand, err
	}
	var turnEnvelope map[string]any
	if json.Unmarshal(turnResult, &turnEnvelope) != nil {
		return nil, continuationCommand, errors.New("lazy continuation turn response is invalid")
	}
	if err := b.recordThreadContinuation(command.ThreadID, continuationCommand.ThreadID, metadata); err != nil {
		log.Printf("persist lazy continuation %s -> %s: %v", command.ThreadID, continuationCommand.ThreadID, err)
	}
	turnEnvelope["continuation"] = map[string]any{
		"schemaVersion": 1, "threadId": continuationCommand.ThreadID,
		"continuedFromThreadId": command.ThreadID, "cwd": metadata.Thread.CWD,
		"historyMode": "recent",
	}
	augmented, err := json.Marshal(turnEnvelope)
	if err != nil {
		return nil, continuationCommand, fmt.Errorf("encode lazy continuation response: %w", err)
	}
	return augmented, continuationCommand, nil
}

func (b *bridge) requestTurnStart(
	ctx context.Context,
	deviceID string,
	command protocol.Command,
	bd backend.Backend,
	params any,
) error {
	if command.ThreadID == "" || firstNonEmpty(command.CommandID, command.RequestID) == "" {
		return errors.New("turn.start stable command and thread identity are required")
	}
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
		defer cancel()
		result, outcome, err := b.executeTurnStartOnce(callCtx, deviceID, command, bd, params)
		_ = b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "rpc.response", RequestID: command.RequestID,
			Payload: turnRPCResponsePayload(result, outcome, err), CreatedAt: time.Now().UnixMilli(),
		}, "")
	}()
	return nil
}

func (b *bridge) executeTurnStartOnce(
	ctx context.Context,
	deviceID string,
	command protocol.Command,
	bd backend.Backend,
	params any,
) (json.RawMessage, turnRPCOutcome, error) {
	if b.commandCache == nil || b.routes == nil {
		return nil, turnRPCFailed, errors.New("turn.start persistence is unavailable")
	}
	cacheID, payloadHash := legacyTurnStartCacheIdentity(command)
	if cacheID == "" {
		return nil, turnRPCFailed, errors.New("turn.start stable command identity is required")
	}
	record, execute, err := b.commandCache.Begin(cacheID, "turn.start", payloadHash)
	if err != nil {
		return nil, turnRPCFailed, err
	}
	if !execute {
		return b.reuseTurnStartRecord(record)
	}
	if err := b.claimThread(command, deviceID); err != nil {
		_, persistErr := b.commandCache.Fail(cacheID, err)
		return nil, turnRPCFailed, errors.Join(err, persistErr)
	}
	result, err := bd.Call(ctx, "turn/start", params)
	if err != nil && isThreadNotFoundError(err, command.ThreadID) {
		metadata, requiresLazyContinuation, metadataErr := b.readPersistedThreadMetadata(ctx, bd, command.ThreadID)
		if metadataErr != nil {
			err = metadataErr
		} else if requiresLazyContinuation {
			var continuationCommand protocol.Command
			result, continuationCommand, err = b.startLazyContinuation(ctx, deviceID, command, bd, metadata)
			if err == nil {
				command = continuationCommand
			}
		} else {
			if _, resumeErr := bd.Call(ctx, "thread/resume", map[string]any{
				"threadId": command.ThreadID, "excludeTurns": true,
			}); resumeErr != nil {
				_, persistErr := b.commandCache.Fail(cacheID, resumeErr)
				return nil, turnRPCFailed, errors.Join(resumeErr, persistErr)
			}
			result, err = bd.Call(ctx, "turn/start", params)
		}
	}
	if err != nil {
		if strings.HasPrefix(err.Error(), "app-server error:") {
			_, persistErr := b.commandCache.Fail(cacheID, err)
			return nil, turnRPCFailed, errors.Join(err, persistErr)
		}
		_, persistErr := b.commandCache.MarkUnknown(cacheID, err)
		return nil, turnRPCUnknown, errors.Join(err, persistErr)
	}
	if err := b.backfillTurnRoute(command, result); err != nil {
		pending := mustJSON(turnStartReconciliation{Command: command, Result: result})
		_, persistErr := b.commandCache.MarkUnknownWithResult(cacheID, err, pending)
		return nil, turnRPCUnknown, errors.Join(err, persistErr)
	}
	if _, err := b.commandCache.Complete(cacheID, "", result); err != nil {
		pending := mustJSON(turnStartReconciliation{Command: command, Result: result})
		_, persistErr := b.commandCache.MarkUnknownWithResult(cacheID, err, pending)
		return nil, turnRPCUnknown, errors.Join(err, persistErr)
	}
	return append(json.RawMessage(nil), result...), turnRPCSucceeded, nil
}

func (b *bridge) reuseTurnStartRecord(record commandcache.Record) (json.RawMessage, turnRPCOutcome, error) {
	switch record.Status {
	case commandcache.StatusSucceeded:
		return append(json.RawMessage(nil), record.ResultJSON...), turnRPCSucceeded, nil
	case commandcache.StatusUnknown:
		if len(record.ResultJSON) > 0 {
			var pending turnStartReconciliation
			if json.Unmarshal(record.ResultJSON, &pending) == nil && len(pending.Result) > 0 {
				if err := b.backfillTurnRoute(pending.Command, pending.Result); err == nil {
					if _, resolveErr := b.commandCache.ResolveUnknown(record.CommandID, pending.Result); resolveErr == nil {
						return append(json.RawMessage(nil), pending.Result...), turnRPCSucceeded, nil
					}
				}
			}
		}
		message := record.LastError
		if message == "" {
			message = "turn.start outcome requires reconciliation"
		}
		return nil, turnRPCUnknown, errors.New(message)
	case commandcache.StatusInFlight:
		return nil, turnRPCUnknown, errors.New("turn.start is already in flight")
	case commandcache.StatusFailed:
		return nil, turnRPCFailed, errors.New(record.LastError)
	default:
		return nil, turnRPCUnknown, errors.New("turn.start outcome is unknown")
	}
}

func (b *bridge) recoverTurnStartRoutes() error {
	if b.commandCache == nil || b.routes == nil {
		return nil
	}
	var recoveryErrors []error
	for _, record := range b.commandCache.RecordsByTypeStatus("turn.start", commandcache.StatusUnknown) {
		if len(record.ResultJSON) == 0 {
			continue
		}
		var pending turnStartReconciliation
		if err := json.Unmarshal(record.ResultJSON, &pending); err != nil {
			recoveryErrors = append(recoveryErrors, fmt.Errorf("decode %s reconciliation: %w", record.CommandID, err))
			continue
		}
		if len(pending.Result) == 0 {
			recoveryErrors = append(recoveryErrors, fmt.Errorf("decode %s reconciliation: app-server result is missing", record.CommandID))
			continue
		}
		if err := b.backfillTurnRoute(pending.Command, pending.Result); err != nil {
			recoveryErrors = append(recoveryErrors, fmt.Errorf("reconcile %s route: %w", record.CommandID, err))
			continue
		}
		if _, err := b.commandCache.ResolveUnknown(record.CommandID, pending.Result); err != nil {
			recoveryErrors = append(recoveryErrors, fmt.Errorf("resolve %s reconciliation: %w", record.CommandID, err))
		}
	}
	return errors.Join(recoveryErrors...)
}

func turnRPCResponsePayload(result json.RawMessage, outcome turnRPCOutcome, cause error) json.RawMessage {
	if outcome == turnRPCSucceeded {
		return mustJSON(map[string]any{"result": result})
	}
	if outcome == turnRPCUnknown {
		message := "turn.start outcome requires reconciliation"
		if cause != nil {
			message = cause.Error()
		}
		return mustJSON(map[string]any{
			"outcome": "UNKNOWN", "status": "RECONCILING", "retrySafe": false,
			"requiresSnapshot": true, "message": message,
		})
	}
	message := "turn.start failed"
	if cause != nil {
		message = cause.Error()
	}
	return mustJSON(map[string]any{"error": message, "retrySafe": false})
}

func (b *bridge) requestAppServerAfter(
	ctx context.Context,
	deviceID string,
	command protocol.Command,
	bd backend.Backend,
	method string,
	params any,
	after func(json.RawMessage) error,
) error {
	if method == "" {
		return errors.New("app-server method is required")
	}
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
		defer cancel()
		result, err := bd.Call(callCtx, method, params)
		if err == nil && method == "thread/read" {
			result, err = mobileThreadReadResult(result)
		}
		if err == nil && after != nil {
			err = after(result)
		}
		if err != nil {
			if command.CommandID != "" && (errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled)) {
				_, _ = b.commandCache.MarkUnknown(command.CommandID, err)
			}
			_ = b.sendEvent(ctx, deviceID, protocol.Event{
				Type: "rpc.response", RequestID: command.RequestID,
				Payload: mustJSON(map[string]any{"error": err.Error()}), CreatedAt: time.Now().UnixMilli(),
			}, "")
			return
		}
		_ = b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "rpc.response", RequestID: command.RequestID,
			Payload: mustJSON(map[string]any{"result": json.RawMessage(result)}), CreatedAt: time.Now().UnixMilli(),
		}, "")
	}()
	return nil
}

const (
	maxMobileThreadResultBytes = 512 << 10
	maxMobileTimelineItems     = 128
	maxMobileTimelineTextBytes = 64 << 10
)

// mobileThreadReadResult turns the app-server's unbounded thread model into the
// stable, recent timeline needed by the phone UI. Authoritative Remote Run
// reconciliation and completion evidence continue to read the full response.
func mobileThreadReadResult(raw json.RawMessage) (json.RawMessage, error) {
	var response map[string]any
	if err := json.Unmarshal(raw, &response); err != nil {
		return nil, fmt.Errorf("decode thread/read for mobile: %w", err)
	}
	thread, ok := response["thread"].(map[string]any)
	if !ok {
		return nil, errors.New("thread/read mobile response is missing thread")
	}
	turns, _ := thread["turns"].([]any)
	projectedTurns := make([]map[string]any, 0, len(turns))
	usedBytes, itemCount := 0, 0
	for turnIndex := len(turns) - 1; turnIndex >= 0 && itemCount < maxMobileTimelineItems; turnIndex-- {
		turn, ok := turns[turnIndex].(map[string]any)
		if !ok {
			continue
		}
		items, _ := turn["items"].([]any)
		projectedItems := make([]map[string]any, 0, len(items))
		for itemIndex := len(items) - 1; itemIndex >= 0 && itemCount < maxMobileTimelineItems; itemIndex-- {
			item, ok := items[itemIndex].(map[string]any)
			if !ok {
				continue
			}
			projected := mobileTimelineItem(item)
			encoded, _ := json.Marshal(projected)
			if usedBytes+len(encoded) > maxMobileThreadResultBytes && itemCount > 0 {
				break
			}
			projectedItems = append([]map[string]any{projected}, projectedItems...)
			usedBytes += len(encoded)
			itemCount++
		}
		if len(projectedItems) == 0 {
			continue
		}
		projectedTurn := map[string]any{"items": projectedItems}
		copyJSONFields(projectedTurn, turn, "id", "status")
		projectedTurns = append([]map[string]any{projectedTurn}, projectedTurns...)
	}
	projectedThread := map[string]any{"turns": projectedTurns}
	copyJSONFields(projectedThread, thread, "id", "cwd", "name", "preview", "status", "updatedAt")
	projectedResponse := map[string]any{"thread": projectedThread}
	copyJSONFields(projectedResponse, response, "mobileHistory")
	return json.Marshal(projectedResponse)
}

func mobileTimelineItem(item map[string]any) map[string]any {
	return mobileTimelineItemWithTextLimit(item, maxMobileTimelineTextBytes)
}

func mobileTimelineItemWithTextLimit(item map[string]any, maxTextBytes int) map[string]any {
	projected := map[string]any{}
	copyJSONFields(projected, item, "id", "type", "status")
	kind, _ := item["type"].(string)
	switch kind {
	case "userMessage", "agentMessage", "reasoning":
		projected["text"] = truncateUTF8(mobileMessageText(item), maxTextBytes)
	case "commandExecution":
		if command, exists := item["command"]; exists {
			projected["command"] = boundedMobileValue(command, minInt(16<<10, maxTextBytes))
		}
		copyJSONFields(projected, item, "cwd", "exitCode", "durationMs")
	case "fileChange":
		if changes, exists := item["changes"]; exists {
			projected["changes"] = boundedMobileValue(changes, minInt(48<<10, maxTextBytes))
		}
	default:
		if text := mobileMessageText(item); text != "" {
			projected["text"] = truncateUTF8(text, maxTextBytes)
		}
	}
	return projected
}

func minInt(left, right int) int {
	if left < right {
		return left
	}
	return right
}

func mobileMessageText(item map[string]any) string {
	if text, ok := item["text"].(string); ok {
		return text
	}
	content, _ := item["content"].([]any)
	parts := make([]string, 0, len(content))
	for _, rawPart := range content {
		part, ok := rawPart.(map[string]any)
		if !ok {
			continue
		}
		if text, ok := part["text"].(string); ok && text != "" {
			parts = append(parts, text)
		}
	}
	return strings.Join(parts, "\n")
}

func mobileCodexEventEnvelope(message backend.Message, processEpoch string) (json.RawMessage, bool) {
	var params map[string]any
	if json.Unmarshal(message.Params, &params) != nil {
		return nil, false
	}
	projectedParams := map[string]any{}
	copyJSONFields(projectedParams, params, "threadId", "turnId", "itemId")
	switch message.Method {
	case "turn/started", "turn/completed":
		if turn, ok := params["turn"].(map[string]any); ok {
			projectedTurn := map[string]any{}
			copyJSONFields(projectedTurn, turn, "id", "status")
			projectedParams["turn"] = projectedTurn
		}
	case "item/started", "item/completed":
		item, ok := params["item"].(map[string]any)
		if !ok {
			return nil, false
		}
		projectedParams["item"] = mobileTimelineItem(item)
	case "item/agentMessage/delta":
		delta, _ := params["delta"].(string)
		projectedParams["delta"] = truncateUTF8(delta, 32<<10)
	default:
		return nil, false
	}
	raw, err := json.Marshal(map[string]any{
		"id": message.ID, "method": message.Method, "params": projectedParams,
		"processEpoch": processEpoch,
	})
	return raw, err == nil
}

func copyJSONFields(target, source map[string]any, fields ...string) {
	for _, field := range fields {
		if value, exists := source[field]; exists {
			target[field] = value
		}
	}
}

func boundedMobileValue(value any, maxBytes int) any {
	if text, ok := value.(string); ok {
		return truncateUTF8(text, maxBytes)
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return "内容无法显示"
	}
	if len(raw) <= maxBytes {
		return value
	}
	return truncateUTF8(string(raw), maxBytes)
}

func truncateUTF8(value string, maxBytes int) string {
	if len(value) <= maxBytes {
		return value
	}
	end := maxBytes - len("\n\n…内容过长，已截断")
	for end > 0 && !utf8.ValidString(value[:end]) {
		end--
	}
	return value[:end] + "\n\n…内容过长，已截断"
}

func (b *bridge) snapshotForRoute(ctx context.Context, route runstate.Route) (runSnapshot, error) {
	if b.terminals != nil {
		if record, found := b.terminals.Lookup(route.RunID); found {
			return terminalSnapshot(route, record), nil
		}
	}
	snapshot := runSnapshot{
		RunID: route.RunID, BackendID: route.BackendID, ThreadID: route.ThreadID, TurnID: route.TurnID,
		Status: "RECONCILING", LatestLine: "正在与 Mac 对账",
	}
	bd := b.backendFor(route.BackendID)
	if route.ThreadID == "" || bd == nil {
		return snapshot, nil
	}
	readCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	result, err := bd.Call(readCtx, "thread/read", map[string]any{
		"threadId": route.ThreadID, "includeTurns": true,
	})
	cancel()
	if err != nil {
		return snapshot, nil
	}
	liveStatus, liveLine := snapshotStatus(result, route.TurnID)
	switch liveStatus {
	case "COMPLETED", "FAILED", "CANCELLED":
		snapshot.ErrorMessage = "terminal run ledger unavailable"
	default:
		snapshot.Status, snapshot.LatestLine = liveStatus, liveLine
	}
	return snapshot, nil
}

func terminalSnapshot(route runstate.Route, record completion.TerminalRunRecord) runSnapshot {
	latestLine := "任务已完成"
	if record.Status == "FAILED" {
		latestLine = "任务失败"
	} else if record.Status == "CANCELLED" {
		latestLine = "任务已停止"
	}
	return runSnapshot{
		RunID: route.RunID, BackendID: route.BackendID, ThreadID: route.ThreadID, TurnID: route.TurnID,
		Status: record.Status, LatestLine: latestLine,
		CompletionJSON: append(json.RawMessage(nil), record.CompletionJSON...), CompletedAt: record.CompletedAt,
	}
}

func (b *bridge) respondApproval(ctx context.Context, deviceID string, command protocol.Command, bd backend.Backend) error {
	if command.CommandID == "" || command.RunID == "" || command.ApprovalID == "" {
		return errors.New("approval response stable identity is required")
	}
	epoch := command.ProcessEpoch
	if epoch == "" {
		epoch = bd.ProcessEpoch()
	}
	return executeApprovalCommand(
		ctx, b.commandCache, command,
		func() error {
			approval, ok := b.routes.Approval(command.ApprovalID)
			if !ok || approval.RunID != command.RunID {
				return errors.New("approval does not belong to run")
			}
			return b.routes.ValidateResponse(command.ApprovalID, epoch, command.ServerRequestID)
		},
		func() error {
			return bd.Respond(ctx, backend.ServerRequestRef{
				ID: command.ServerRequestID, Method: "", Params: nil, ProcessEpoch: epoch,
			}, map[string]string{"decision": command.Decision})
		},
		func(ctx context.Context, ignoredDeviceID, runID, eventType string, payload json.RawMessage) (string, error) {
			eventID, err := b.emitLogicalEvent(ctx, deviceID, runID, eventType, payload)
			if err == nil {
				err = b.routes.MarkApprovalResolved(command.ApprovalID)
			}
			return eventID, err
		},
	)
}

type logicalEventEmitter func(context.Context, string, string, string, json.RawMessage) (string, error)

func executeApprovalCommand(
	ctx context.Context,
	cache *commandcache.Store,
	command protocol.Command,
	validate func() error,
	respond func() error,
	emit logicalEventEmitter,
) error {
	if command.Decision != "accept" && command.Decision != "decline" {
		return errors.New("unsupported mobile approval decision")
	}
	raw, _ := json.Marshal(command)
	digest := sha256.Sum256(raw)
	record, execute, err := cache.Begin(command.CommandID, command.Type, hex.EncodeToString(digest[:]))
	if err != nil {
		return err
	}
	if !execute {
		switch record.Status {
		case commandcache.StatusSucceeded, commandcache.StatusInFlight:
			return nil
		case commandcache.StatusUnknown:
			return errors.New("approval response outcome is unknown; snapshot required")
		default:
			return errors.New(record.LastError)
		}
	}
	if err := validate(); err != nil {
		_, _ = cache.Fail(command.CommandID, err)
		return err
	}
	if err := respond(); err != nil {
		_, _ = cache.MarkUnknown(command.CommandID, err)
		return err
	}
	payload := mustJSON(map[string]any{
		"approvalId": command.ApprovalID, "decision": command.Decision,
		"commandId": command.CommandID, "status": "RESOLVED",
		"latestLine": "审批已提交，任务继续运行",
	})
	eventID, err := emit(ctx, "", command.RunID, "run.approval.resolved", payload)
	if err != nil {
		_, _ = cache.MarkUnknown(command.CommandID, err)
		return err
	}
	result := mustJSON(map[string]string{"approvalId": command.ApprovalID, "eventId": eventID})
	_, err = cache.Complete(command.CommandID, eventID, result)
	return err
}

func (b *bridge) handleAppServer(ctx context.Context, message backend.Message) {
	if message.Method == "" {
		return
	}
	bd := b.backendFor(message.BackendID)
	if bd == nil {
		log.Printf("discard app-server event from unavailable backend %s: %s", message.BackendID, message.Method)
		return
	}
	processEpoch := bd.ProcessEpoch()
	if message.Method == "serverRequest/resolved" {
		var params struct {
			RequestID json.RawMessage `json:"requestId"`
		}
		if json.Unmarshal(message.Params, &params) == nil && len(params.RequestID) > 0 {
			if approval, found, _ := b.routes.MarkServerRequestResolved(message.BackendID, processEpoch, params.RequestID); found {
				if route, ok := b.routes.ByRun(approval.RunID); ok {
					payload := mustJSON(map[string]string{
						"approvalId": approval.ApprovalID, "status": "STALE",
						"latestLine": "审批已在其他位置解决",
					})
					_, _ = b.emitLogicalEvent(ctx, route.DeviceID, route.RunID, "run.approval.resolved", payload)
				}
			}
		}
	}
	eventType, pushKind := "codex.event", ""
	approvalID := ""
	if message.Method == "item/commandExecution/requestApproval" ||
		message.Method == "item/fileChange/requestApproval" ||
		message.Method == "item/permissions/requestApproval" {
		eventType, pushKind = "approval.request", "approval"
		approvalID = stableApprovalID(processEpoch, message.ID)
		if route, ok := b.routeForParams(message.BackendID, message.Params); ok {
			payload := approvalLogicalPayload(message, approvalID, processEpoch)
			var ledger struct {
				Method         string `json:"method"`
				ItemID         string `json:"itemId"`
				ActionType     string `json:"actionType"`
				Target         string `json:"target"`
				CommandPreview string `json:"commandPreview"`
				Risk           string `json:"risk"`
			}
			_ = json.Unmarshal(payload, &ledger)
			_ = b.routes.PutApproval(runstate.Approval{
				ApprovalID: approvalID, RunID: route.RunID, BackendID: message.BackendID,
				ProcessEpoch: processEpoch, ServerRequestID: message.ID,
				Method: ledger.Method, ItemID: ledger.ItemID, ActionType: ledger.ActionType,
				Target: ledger.Target, CommandPreview: ledger.CommandPreview,
				DetailsJSON: "{}", Risk: ledger.Risk, RequestedAt: time.Now().UnixMilli(),
				Status: runstate.ApprovalPending,
			})
			if _, err := b.emitLogicalEvent(ctx, route.DeviceID, route.RunID, "run.approval.requested", payload); err == nil {
				return
			}
		}
	}
	if message.Method == "item/tool/requestUserInput" {
		eventType, pushKind = "user_input.request", "approval"
		if route, ok := b.routeForParams(message.BackendID, message.Params); ok {
			_, _ = b.emitLogicalEvent(
				ctx, route.DeviceID, route.RunID, "run.user_input.requested", userInputLogicalPayload(message.Params),
			)
		}
	}
	if message.Method == "turn/completed" {
		pushKind = "completion"
		if route, ok := b.routeForParams(message.BackendID, message.Params); ok {
			if b.terminals != nil {
				if err := b.terminals.Observe(completion.TerminalObservation{
					RunID: route.RunID, Params: message.Params, ObservedAt: time.Now().UnixMilli(),
				}); err != nil {
					log.Printf("persist terminal observation before reconciliation for %s: %v", route.RunID, err)
				}
			}
			go b.completeRun(ctx, route, message.Params)
		}
	}
	if route, ok := b.routeForParams(message.BackendID, message.Params); ok {
		if logicalType, payload, translated := timelineLogicalPayload(message.Method, message.Params); translated {
			_, _ = b.emitLogicalEvent(ctx, route.DeviceID, route.RunID, logicalType, payload)
		}
	}
	raw, mobileEvent := mobileCodexEventEnvelope(message, processEpoch)
	if !mobileEvent {
		return
	}
	for _, deviceID := range b.eventTargets(message.BackendID, message.Params) {
		_ = b.sendBackendEvent(ctx, message.BackendID, deviceID, protocol.Event{Type: eventType, Method: message.Method, Payload: raw, CreatedAt: time.Now().UnixMilli()}, pushKind)
	}
}

func timelineLogicalPayload(method string, raw json.RawMessage) (string, json.RawMessage, bool) {
	var params map[string]any
	if json.Unmarshal(raw, &params) != nil {
		return "", nil, false
	}
	if method == "item/agentMessage/delta" {
		itemID, _ := params["itemId"].(string)
		delta, _ := params["delta"].(string)
		return "run.agent.delta", mustJSON(map[string]any{
			"itemId": itemID, "delta": redactApprovalText(delta),
			"presentationKind": "AGENT_DELTA", "latestLine": "正在整理结果",
		}), true
	}
	if method != "item/started" && method != "item/completed" {
		return "", nil, false
	}
	item, ok := params["item"].(map[string]any)
	if !ok {
		return "", nil, false
	}
	kind, _ := item["type"].(string)
	presentation := "STATUS"
	latestLine := "正在处理"
	switch kind {
	case "agentMessage":
		presentation, latestLine = "RESULT", "正在整理结果"
	case "commandExecution":
		presentation, latestLine = "TEST", "正在运行命令或测试"
	case "fileChange":
		presentation, latestLine = "FILES", "正在修改文件"
	case "reasoning":
		presentation, latestLine = "ANALYZING", "正在分析"
	case "webSearch":
		presentation, latestLine = "SEARCHING", "正在查找"
	}
	return "run.timeline", mustJSON(map[string]any{
		"itemId": item["id"], "presentationKind": presentation,
		"latestLine": latestLine, "detail": itemSummary(kind, item),
		"diagnostic": sanitizeApprovalDetails(item),
	}), true
}

func itemSummary(kind string, item map[string]any) string {
	for _, key := range []string{"text", "command", "status"} {
		if value, ok := item[key].(string); ok && value != "" {
			return redactApprovalText(value)
		}
	}
	if kind == "fileChange" {
		if changes, ok := item["changes"].([]any); ok {
			return fmt.Sprintf("%d 个文件变更", len(changes))
		}
	}
	return ""
}

func userInputLogicalPayload(raw json.RawMessage) json.RawMessage {
	var params map[string]any
	if json.Unmarshal(raw, &params) != nil {
		params = map[string]any{}
	}
	question := "需要在 Mac 上补充输入"
	for _, key := range []string{"question", "prompt", "message"} {
		if value, ok := params[key].(string); ok && value != "" {
			question = redactApprovalText(value)
			break
		}
	}
	return mustJSON(map[string]any{
		"itemId": params["itemId"], "presentationKind": "RECOVERY",
		"latestLine": question, "detail": "保留证据后在 Mac UI 重新发起",
	})
}

func (b *bridge) completeRun(ctx context.Context, route runstate.Route, params json.RawMessage) {
	if b.terminals != nil {
		if err := b.terminals.Observe(completion.TerminalObservation{
			RunID: route.RunID, Params: params, ObservedAt: time.Now().UnixMilli(),
		}); err != nil {
			log.Printf("persist terminal observation for %s: %v", route.RunID, err)
		}
	}
	if err := b.reconcileTerminalRun(ctx, route, params); err != nil {
		log.Printf("defer terminal run %s: %v", route.RunID, err)
		if b.terminals != nil {
			if persistErr := b.terminals.RecordObservationFailure(route.RunID, err.Error()); persistErr != nil {
				log.Printf("persist terminal reconciliation failure for %s: %v", route.RunID, persistErr)
			}
		}
	}
}

func (b *bridge) reconcileTerminalRun(ctx context.Context, route runstate.Route, params json.RawMessage) error {
	if b.terminals != nil {
		if frozen, found := b.terminals.Lookup(route.RunID); found {
			return b.publishFrozenTerminal(ctx, route, frozen)
		}
	}
	bd := b.backendFor(route.BackendID)
	if bd == nil || route.ThreadID == "" || route.TurnID == "" {
		return errors.New("terminal route or backend is unavailable")
	}
	callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	result, err := bd.Call(callCtx, "thread/read", map[string]any{
		"threadId": route.ThreadID, "includeTurns": true,
	})
	if err != nil {
		return fmt.Errorf("thread/read failed: %w", err)
	}
	turn, ok := turnSnapshot(result, route.TurnID)
	if !ok {
		return fmt.Errorf("thread/read did not contain target turn %s", route.TurnID)
	}
	status := completionTurnStatus(params, turn.Status)
	if status == "cancelled" {
		record, err := b.freezeTerminal(route, "CANCELLED", nil, time.Now().UnixMilli())
		if err != nil {
			return fmt.Errorf("freeze cancelled run before journal: %w", err)
		}
		return b.publishFrozenTerminal(ctx, route, record)
	}
	if status == "failed" {
		record, err := b.freezeTerminal(route, "FAILED", nil, time.Now().UnixMilli())
		if err != nil {
			return fmt.Errorf("freeze failed run before journal: %w", err)
		}
		return b.publishFrozenTerminal(ctx, route, record)
	}
	if status != "completed" {
		return errors.New("target turn status is unknown or non-terminal")
	}
	evidence := buildCompletionEvidence(route, turn)
	record, err := b.freezeTerminal(route, "COMPLETED", mustJSON(evidence), evidence.CompletedAt)
	if err != nil {
		return fmt.Errorf("freeze completed run before journal: %w", err)
	}
	return b.publishFrozenTerminal(ctx, route, record)
}

func (b *bridge) recoverTerminalRuns(ctx context.Context, deviceID string) error {
	if b.terminals == nil {
		return nil
	}
	var recoveryErrors []error
	for _, record := range b.terminals.PendingJournalRecords() {
		route, ok := b.routes.ByRun(record.RunID)
		if !ok || (deviceID != "" && route.DeviceID != deviceID) {
			continue
		}
		if err := b.publishFrozenTerminal(ctx, route, record); err != nil {
			recoveryErrors = append(recoveryErrors, fmt.Errorf("publish frozen terminal %s: %w", record.RunID, err))
		}
	}
	for _, observation := range b.terminals.PendingObservations() {
		route, ok := b.routes.ByRun(observation.RunID)
		if !ok || (deviceID != "" && route.DeviceID != deviceID) {
			continue
		}
		if err := b.reconcileTerminalRun(ctx, route, observation.Params); err != nil {
			_ = b.terminals.RecordObservationFailure(observation.RunID, err.Error())
			recoveryErrors = append(recoveryErrors, fmt.Errorf("reconcile observed terminal %s: %w", observation.RunID, err))
		}
	}
	return errors.Join(recoveryErrors...)
}

func (b *bridge) publishFrozenTerminal(ctx context.Context, route runstate.Route, record completion.TerminalRunRecord) error {
	eventType, payload := frozenTerminalPayload(route, record)
	if b.terminals == nil {
		_, err := b.emitLogicalEvent(ctx, route.DeviceID, route.RunID, eventType, payload)
		return err
	}
	if b.journal == nil {
		return errors.New("logical journal is unavailable")
	}
	eventID := frozenTerminalEventID(b.state.HostID, route, record)
	if !b.journal.Has(eventID) {
		event := protocol.LogicalEvent{
			SchemaVersion: 1, EventID: eventID, HostID: b.state.HostID, DeviceID: route.DeviceID,
			RunID: route.RunID, BackendID: route.BackendID, Type: eventType, Payload: payload, CreatedAt: record.CompletedAt,
		}
		if err := b.sendLogicalEvent(ctx, event); err != nil && !b.journal.Has(eventID) {
			return err
		}
	}
	return b.terminals.MarkJournaled(route.RunID, eventID)
}

func frozenTerminalEventID(hostID string, route runstate.Route, record completion.TerminalRunRecord) string {
	identity := strings.Join([]string{
		hostID, route.DeviceID, route.RunID, record.Status,
		strconv.FormatInt(record.CompletedAt, 10), record.CompletionSHA256,
	}, "\x00")
	digest := sha256.Sum256([]byte(identity))
	return "terminal-" + hex.EncodeToString(digest[:16])
}

func frozenTerminalPayload(route runstate.Route, record completion.TerminalRunRecord) (string, json.RawMessage) {
	payload := map[string]any{"threadId": route.ThreadID, "turnId": route.TurnID}
	switch record.Status {
	case "COMPLETED":
		payload["presentationKind"] = "COMPLETION"
		payload["latestLine"] = "任务已完成"
		payload["completion"] = record.CompletionJSON
		return "run.completed", mustJSON(payload)
	case "FAILED":
		payload["presentationKind"] = "RECOVERY"
		payload["latestLine"] = "任务失败"
		return "run.failed", mustJSON(payload)
	default:
		payload["presentationKind"] = "CANCELLED"
		payload["latestLine"] = "任务已停止"
		return "run.cancelled", mustJSON(payload)
	}
}

func (b *bridge) freezeTerminal(
	route runstate.Route,
	status string,
	completionJSON json.RawMessage,
	completedAt int64,
) (completion.TerminalRunRecord, error) {
	record := completion.TerminalRunRecord{
		RunID: route.RunID, Status: status, CompletionJSON: completionJSON,
		CompletedAt: completedAt, Workspace: completionWorkspace(route),
	}
	if b.terminals == nil {
		return record, nil
	}
	frozen, _, err := b.terminals.Freeze(record)
	return frozen, err
}

func completionWorkspace(route runstate.Route) completion.WorkspaceLocator {
	var baseline runstate.WorkspaceBaseline
	_ = json.Unmarshal([]byte(route.BaselineJSON), &baseline)
	return completion.WorkspaceLocator{
		WorkspaceID: route.WorkspaceID, RepositoryFingerprint: baseline.RepositoryFingerprint, CWD: baseline.CWD,
	}
}

func buildCompletionEvidence(route runstate.Route, turn completedTurn) completion.RunCompletion {
	var baseline runstate.WorkspaceBaseline
	_ = json.Unmarshal([]byte(route.BaselineJSON), &baseline)
	before := workspace.Baseline{
		IsGit: baseline.IsGit, Head: baseline.Head, Branch: baseline.Branch,
		PorcelainV2Z: baseline.PorcelainV2Z, CapturedAt: baseline.CapturedAt,
	}
	after, inspectErr := workspace.CaptureBaseline(baseline.CWD)
	inspectionErrors := make([]string, 0, 2)
	if inspectErr != nil {
		inspectionErrors = append(inspectionErrors, "capture workspace state: "+inspectErr.Error())
	} else if before.IsGit && !after.IsGit {
		inspectionErrors = append(inspectionErrors, "workspace is no longer verifiably a Git repository")
	}
	committed := []string(nil)
	if inspectErr == nil && (before.IsGit == after.IsGit) {
		var diffErr error
		committed, diffErr = workspace.ChangedFilesBetween(baseline.CWD, before.Head, after.Head)
		if diffErr != nil {
			inspectionErrors = append(inspectionErrors, "inspect committed files: "+diffErr.Error())
		}
	}
	observed := workspace.ChangedFilesFromStatus(before.PorcelainV2Z, after.PorcelainV2Z)
	return completion.Build(completion.Input{
		RunID: route.RunID, Workspace: completionWorkspace(route),
		Before: before, After: after, CommittedFiles: committed, ObservedFiles: observed,
		Items: turn.Items, StructuredOutput: turn.StructuredOutput,
		LastAgentMessage: turn.LastAgentMessage, CompletedAt: time.Now().UnixMilli(),
		GitInspectionError: strings.Join(inspectionErrors, "; "),
	})
}

type completedTurn struct {
	Status           json.RawMessage
	Items            []json.RawMessage
	StructuredOutput json.RawMessage
	LastAgentMessage string
}

func turnSnapshot(raw json.RawMessage, turnID string) (completedTurn, bool) {
	var result struct {
		Thread struct {
			Turns []struct {
				ID               string            `json:"id"`
				Status           json.RawMessage   `json:"status"`
				Items            []json.RawMessage `json:"items"`
				Output           json.RawMessage   `json:"output"`
				StructuredOutput json.RawMessage   `json:"structuredOutput"`
			} `json:"turns"`
		} `json:"thread"`
	}
	if json.Unmarshal(raw, &result) != nil {
		return completedTurn{}, false
	}
	for _, turn := range result.Thread.Turns {
		if turn.ID != turnID {
			continue
		}
		structured := turn.StructuredOutput
		if len(structured) == 0 || string(structured) == "null" {
			structured = turn.Output
		}
		lastAgent := ""
		for _, item := range turn.Items {
			var decoded struct {
				Type string `json:"type"`
				Text string `json:"text"`
			}
			if json.Unmarshal(item, &decoded) == nil && decoded.Type == "agentMessage" && decoded.Text != "" {
				lastAgent = decoded.Text
			}
		}
		return completedTurn{
			Status: turn.Status, Items: turn.Items, StructuredOutput: structured, LastAgentMessage: lastAgent,
		}, true
	}
	return completedTurn{}, false
}

func completionTurnStatus(params, fallback json.RawMessage) string {
	for _, raw := range []json.RawMessage{params, fallback} {
		var direct string
		if json.Unmarshal(raw, &direct) == nil && direct != "" {
			if status := normalizeCompletionStatus(direct); status != "unknown" {
				return status
			}
		}
		var object map[string]any
		if json.Unmarshal(raw, &object) != nil {
			continue
		}
		candidates := []any{object["status"]}
		if turn, ok := object["turn"].(map[string]any); ok {
			candidates = append(candidates, turn["status"])
		}
		candidates = append(candidates, object["type"])
		for _, candidate := range candidates {
			if status := normalizeCompletionStatus(statusText(candidate)); status != "unknown" {
				return status
			}
		}
	}
	return "unknown"
}

func statusText(value any) string {
	switch status := value.(type) {
	case string:
		return status
	case map[string]any:
		if kind, _ := status["type"].(string); kind != "" {
			return kind
		}
		return statusText(status["status"])
	default:
		return ""
	}
}

func normalizeCompletionStatus(status string) string {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "completed":
		return "completed"
	case "failed":
		return "failed"
	case "interrupted", "cancelled", "canceled":
		return "cancelled"
	default:
		return "unknown"
	}
}

func approvalLogicalPayload(message backend.Message, approvalID, processEpoch string) json.RawMessage {
	var params map[string]any
	if json.Unmarshal(message.Params, &params) != nil {
		params = map[string]any{}
	}
	actionType := "UNKNOWN"
	switch message.Method {
	case "item/commandExecution/requestApproval":
		actionType = "COMMAND_EXECUTION"
	case "item/fileChange/requestApproval":
		actionType = "FILE_CHANGE"
	case "item/permissions/requestApproval":
		actionType = "PERMISSIONS"
	}
	target := redactApprovalText(approvalTarget(params))
	return mustJSON(map[string]any{
		"approvalId": approvalID, "serverRequestId": message.ID,
		"processEpoch": processEpoch, "method": message.Method,
		"itemId": params["itemId"], "actionType": actionType,
		"target": target, "commandPreview": target, "details": sanitizeApprovalDetails(params),
		"availableDecisions": appserverrpc.MobileApprovalDecisions(),
		"risk":               approvalRisk(target, actionType), "latestLine": "等待手机审批",
	})
}

func sanitizeApprovalDetails(value any) any {
	switch current := value.(type) {
	case map[string]any:
		clean := make(map[string]any, len(current))
		for key, child := range current {
			lower := strings.ToLower(key)
			if strings.Contains(lower, "token") || strings.Contains(lower, "secret") ||
				strings.Contains(lower, "password") || strings.Contains(lower, "api_key") ||
				strings.Contains(lower, "apikey") || strings.Contains(lower, "authorization") {
				clean[key] = "[REDACTED]"
			} else {
				clean[key] = sanitizeApprovalDetails(child)
			}
		}
		return clean
	case []any:
		clean := make([]any, len(current))
		for index, child := range current {
			clean[index] = sanitizeApprovalDetails(child)
		}
		return clean
	case string:
		return redactApprovalText(current)
	default:
		return current
	}
}

func redactApprovalText(value string) string {
	words := strings.Fields(value)
	for index, word := range words {
		if index > 0 && strings.EqualFold(words[index-1], "bearer") {
			words[index] = "[REDACTED]"
			continue
		}
		lower := strings.ToLower(word)
		for _, marker := range []string{"access_token=", "token=", "api_key=", "secret=", "password="} {
			if offset := strings.Index(lower, marker); offset >= 0 {
				end := strings.IndexAny(word[offset:], "&'\"")
				if end < 0 {
					end = len(word) - offset
				}
				word = word[:offset+len(marker)] + "[REDACTED]" + word[offset+end:]
				lower = strings.ToLower(word)
			}
		}
		words[index] = word
	}
	return strings.Join(words, " ")
}

func approvalRisk(target, actionType string) string {
	normalized := strings.ToLower(actionType + " " + target)
	if actionType == "PERMISSIONS" || strings.Contains(normalized, "sudo ") ||
		strings.Contains(normalized, "rm -rf") || strings.Contains(normalized, "--force") {
		return "HIGH"
	}
	if actionType == "FILE_CHANGE" || strings.Contains(normalized, "git push") ||
		strings.Contains(normalized, "curl ") || strings.Contains(normalized, "wget ") {
		return "MEDIUM"
	}
	if target == "" {
		return "UNKNOWN"
	}
	return "LOW"
}

func approvalTarget(params map[string]any) string {
	for _, key := range []string{"command", "reason", "path", "cwd"} {
		if value := params[key]; value != nil {
			if text, ok := value.(string); ok && text != "" {
				return text
			}
			raw, _ := json.Marshal(value)
			if len(raw) > 0 && string(raw) != "null" {
				return string(raw)
			}
		}
	}
	return "Codex 请求执行受保护操作"
}

func (b *bridge) routeForParams(backendID string, params json.RawMessage) (runstate.Route, bool) {
	threadID, turnID := routeIdentityFromParams(params)
	if threadID == "" || b.routes == nil {
		return runstate.Route{}, false
	}
	if turnID != "" {
		return b.routes.ByThreadTurnBackend(threadID, turnID, backendID)
	}
	active := make([]runstate.Route, 0, 1)
	for _, route := range b.routes.ByThreadAllBackend(threadID, backendID) {
		if b.terminals != nil {
			if _, terminal := b.terminals.Lookup(route.RunID); terminal {
				continue
			}
		}
		active = append(active, route)
		if len(active) > 1 {
			return runstate.Route{}, false
		}
	}
	if len(active) != 1 {
		return runstate.Route{}, false
	}
	return active[0], true
}

func (b *bridge) claimThread(command protocol.Command, deviceID string) error {
	if command.ThreadID == "" || deviceID == "" {
		return errors.New("thread and device are required")
	}
	if b.routes == nil {
		return errors.New("route store is unavailable")
	}
	runID := command.RunID
	if runID == "" {
		runID = "legacy:" + command.ThreadID
	}
	route, _ := b.routes.ByRun(runID)
	route.RunID = runID
	route.HostID = b.state.HostID
	route.DeviceID = deviceID
	route.ThreadID = command.ThreadID
	route.BackendID = command.BackendID
	if command.BindingID != "" {
		route.BindingID = command.BindingID
	}
	if command.WorkspaceID != "" {
		route.WorkspaceID = command.WorkspaceID
	}
	if command.Type == "turn.steer" {
		route.TurnID = firstNonEmpty(command.TurnID, command.ExpectedTurnID)
	} else {
		route.TurnID = command.TurnID
	}
	return b.routes.Put(route)
}

func (b *bridge) backfillTurnRoute(command protocol.Command, result json.RawMessage) error {
	var envelope struct {
		TurnID string `json:"turnId"`
		Turn   struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	if json.Unmarshal(result, &envelope) != nil {
		return errors.New("turn response is invalid")
	}
	turnID := firstNonEmpty(envelope.TurnID, envelope.Turn.ID, command.TurnID, command.ExpectedTurnID)
	if turnID == "" {
		return errors.New("turn response is missing turn id")
	}
	runID := command.RunID
	if runID == "" {
		runID = "legacy:" + command.ThreadID
	}
	return b.routes.UpdateTurn(runID, command.ThreadID, turnID)
}

func (b *bridge) eventTargets(backendID string, params json.RawMessage) []string {
	route, ok := b.routeForParams(backendID, params)
	if !ok || route.DeviceID == "" {
		return nil
	}
	return []string{route.DeviceID}
}

func threadIDFromParams(params json.RawMessage) string {
	threadID, _ := routeIdentityFromParams(params)
	return threadID
}

func routeIdentityFromParams(params json.RawMessage) (string, string) {
	var envelope struct {
		ThreadID string `json:"threadId"`
		TurnID   string `json:"turnId"`
		Thread   struct {
			ID string `json:"id"`
		} `json:"thread"`
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	if json.Unmarshal(params, &envelope) != nil {
		return "", ""
	}
	if envelope.ThreadID != "" {
		return envelope.ThreadID, firstNonEmpty(envelope.TurnID, envelope.Turn.ID)
	}
	return envelope.Thread.ID, firstNonEmpty(envelope.TurnID, envelope.Turn.ID)
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func stableApprovalID(epoch string, serverRequestID json.RawMessage) string {
	digest := sha256.Sum256(append(append([]byte(epoch), 0), serverRequestID...))
	return "approval-" + hex.EncodeToString(digest[:16])
}

// sendBackendEvent attributes an event to one backend before transmission, so
// the phone can group and filter events by backend.
func (b *bridge) sendBackendEvent(ctx context.Context, backendID, deviceID string, event protocol.Event, pushKind string) error {
	event.BackendID = backendID
	return b.sendEvent(ctx, deviceID, event, pushKind)
}

func (b *bridge) sendEvent(ctx context.Context, deviceID string, event protocol.Event, pushKind string) error {
	b.mu.Lock()
	encoded := b.state.DeviceSecrets[deviceID]
	b.state.Sequences[deviceID]++
	sequence := b.state.Sequences[deviceID]
	_ = b.persistStateLocked()
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
	_ = b.persistStateLocked()
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
	_ = b.persistStateLocked()
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

func (b *bridge) threadContinuationsSnapshot() map[string]bridgestate.ThreadContinuation {
	b.mu.Lock()
	defer b.mu.Unlock()
	return cloneThreadContinuations(b.state.ThreadContinuations)
}

func (b *bridge) continuationForLatestThread(threadID string) *bridgestate.ThreadContinuation {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, record := range b.state.ThreadContinuations {
		if len(record.ThreadIDs) == 0 || record.ThreadIDs[len(record.ThreadIDs)-1] != threadID {
			continue
		}
		copy := record
		copy.ThreadIDs = append([]string(nil), record.ThreadIDs...)
		return &copy
	}
	return nil
}

func (b *bridge) persistStateLocked() error {
	candidate := cloneBridgeState(b.state)
	var persisted bridgeState
	err := b.updateBridge(func(onDisk *bridgeState) error {
		for ticket, secret := range onDisk.Pending {
			if _, exists := candidate.Pending[ticket]; !exists {
				candidate.Pending[ticket] = secret
			}
		}
		for deviceID, secret := range onDisk.DeviceSecrets {
			if _, exists := candidate.DeviceSecrets[deviceID]; !exists {
				candidate.DeviceSecrets[deviceID] = secret
			}
		}
		candidate.RegisteredWorkspaces = append([]string(nil), onDisk.RegisteredWorkspaces...)
		*onDisk = candidate
		persisted = cloneBridgeState(candidate)
		return nil
	})
	if err == nil {
		b.state = persisted
	}
	return err
}

func (b *bridge) claimPairingSecretLocked(ticket, deviceID string) (string, error) {
	candidate := cloneBridgeState(b.state)
	var secret string
	var persisted bridgeState
	err := b.updateBridge(func(onDisk *bridgeState) error {
		for pendingTicket, pendingSecret := range onDisk.Pending {
			if _, exists := candidate.Pending[pendingTicket]; !exists {
				candidate.Pending[pendingTicket] = pendingSecret
			}
		}
		for existingDeviceID, existingSecret := range onDisk.DeviceSecrets {
			if _, exists := candidate.DeviceSecrets[existingDeviceID]; !exists {
				candidate.DeviceSecrets[existingDeviceID] = existingSecret
			}
		}
		candidate.RegisteredWorkspaces = append([]string(nil), onDisk.RegisteredWorkspaces...)
		secret = candidate.Pending[ticket]
		if secret != "" {
			candidate.DeviceSecrets[deviceID] = secret
			delete(candidate.Pending, ticket)
		}
		*onDisk = candidate
		persisted = cloneBridgeState(candidate)
		return nil
	})
	if err != nil {
		return "", err
	}
	b.state = persisted
	return secret, nil
}

func (b *bridge) updateBridge(update func(*bridgeState) error) error {
	if b.updateState != nil {
		return b.updateState(b.path, update)
	}
	return bridgestate.UpdateBridge(b.path, update)
}

func cloneBridgeState(source bridgeState) bridgeState {
	cloned := source
	cloned.Pending = make(map[string]string, len(source.Pending))
	for key, value := range source.Pending {
		cloned.Pending[key] = value
	}
	cloned.DeviceSecrets = make(map[string]string, len(source.DeviceSecrets))
	for key, value := range source.DeviceSecrets {
		cloned.DeviceSecrets[key] = value
	}
	cloned.Sequences = make(map[string]uint64, len(source.Sequences))
	for key, value := range source.Sequences {
		cloned.Sequences[key] = value
	}
	cloned.PendingOutbound = make(map[string]map[string]string, len(source.PendingOutbound))
	for deviceID, pending := range source.PendingOutbound {
		clonedPending := make(map[string]string, len(pending))
		for messageID, raw := range pending {
			clonedPending[messageID] = raw
		}
		cloned.PendingOutbound[deviceID] = clonedPending
	}
	cloned.RegisteredWorkspaces = append([]string(nil), source.RegisteredWorkspaces...)
	cloned.ThreadContinuations = cloneThreadContinuations(source.ThreadContinuations)
	return cloned
}

func cloneThreadContinuations(source map[string]bridgestate.ThreadContinuation) map[string]bridgestate.ThreadContinuation {
	cloned := make(map[string]bridgestate.ThreadContinuation, len(source))
	for rootThreadID, record := range source {
		record.ThreadIDs = append([]string(nil), record.ThreadIDs...)
		cloned[rootThreadID] = record
	}
	return cloned
}

func loadBridgeState(path string) (bridgeState, error) { return bridgestate.LoadBridge(path) }
func saveBridgeState(path string, s bridgeState) error { return bridgestate.SaveBridge(path, s) }
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
func decodeRaw(raw json.RawMessage) any {
	var value any = map[string]any{}
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &value)
	}
	return value
}
func defaultStatePath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./bridge.json"
	}
	return filepath.Join(home, ".harness-remote", "bridge.json")
}
func hostname() string { name, _ := os.Hostname(); return name }
func usage() {
	fmt.Fprintln(os.Stderr, "usage: harness-bridge <init|recover|pair|workspace|serve> [options]")
}
