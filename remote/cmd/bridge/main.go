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
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	appserverrpc "github.com/harnessapk/remote/internal/appserver"
	"github.com/harnessapk/remote/internal/commandcache"
	"github.com/harnessapk/remote/internal/journal"
	"github.com/harnessapk/remote/internal/protocol"
	runstate "github.com/harnessapk/remote/internal/run"
	bridgestate "github.com/harnessapk/remote/internal/state"
	"github.com/harnessapk/remote/internal/workspace"
	qrcode "github.com/skip2/go-qrcode"
)

type bridgeState = bridgestate.BridgeData

type bridge struct {
	mu           sync.Mutex
	writeMu      sync.Mutex
	state        bridgeState
	path         string
	conn         *websocket.Conn
	app          *appProcess
	journal      *journal.Store
	commandCache *commandcache.Store
	routes       *runstate.RouteStore
	seen         map[string]time.Time
}

type appProcess struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	client *appserverrpc.Client
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
		paths := make(map[string]struct{}, len(state.RegisteredWorkspaces)+1)
		for _, registered := range state.RegisteredWorkspaces {
			paths[registered] = struct{}{}
		}
		if remaining[0] == "add" {
			paths[cwd] = struct{}{}
		} else {
			delete(paths, cwd)
		}
		state.RegisteredWorkspaces = state.RegisteredWorkspaces[:0]
		for registered := range paths {
			state.RegisteredWorkspaces = append(state.RegisteredWorkspaces, registered)
		}
		sort.Strings(state.RegisteredWorkspaces)
		if err := saveBridgeState(*statePath, state); err != nil {
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
		journalKey, err := base64.RawURLEncoding.DecodeString(state.JournalKey)
		if err != nil {
			log.Printf("decode journal key: %v", err)
			app.close()
			time.Sleep(5 * time.Second)
			continue
		}
		journalStore, err := journal.Open(filepath.Join(filepath.Dir(*statePath), "logical-events.log"), journalKey, 10_000)
		if err != nil {
			log.Printf("open logical journal: %v", err)
			app.close()
			time.Sleep(5 * time.Second)
			continue
		}
		commandStore, err := commandcache.Open(filepath.Join(filepath.Dir(*statePath), "commands.json"))
		if err != nil {
			log.Printf("open command cache: %v", err)
			app.close()
			time.Sleep(5 * time.Second)
			continue
		}
		routeStore, err := runstate.OpenRoutes(filepath.Join(filepath.Dir(*statePath), "routes.json"))
		if err != nil {
			log.Printf("open run routes: %v", err)
			app.close()
			time.Sleep(5 * time.Second)
			continue
		}
		if err := routeStore.BeginProcessEpoch(app.client.ProcessEpoch()); err != nil {
			log.Printf("start app-server process epoch: %v", err)
			app.close()
			time.Sleep(5 * time.Second)
			continue
		}
		b := &bridge{
			state: state, path: *statePath, app: app, journal: journalStore,
			commandCache: commandStore, routes: routeStore, seen: map[string]time.Time{},
		}
		if err := b.run(context.Background()); err != nil {
			log.Printf("bridge disconnected: %v", err)
		}
		app.close()
		state, _ = loadBridgeState(*statePath)
		time.Sleep(3 * time.Second)
	}
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
	errorsCh := make(chan error, 2)
	b.app.client.SetNotificationHandler(func(message appserverrpc.Message) {
		b.handleAppServer(ctx, message)
	})
	b.app.client.Start(ctx)
	go func() { errorsCh <- <-b.app.client.Done() }()
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
	initializeCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if _, err := b.app.client.Call(initializeCtx, "initialize", map[string]any{
		"clientInfo": map[string]string{
			"name": "harness_remote_bridge", "title": "Harness Remote Bridge", "version": "0.2.0",
		},
	}); err != nil {
		return err
	}
	if err := b.app.client.Notify("initialized", map[string]any{}); err != nil {
		return err
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
		return b.requestAppServer(ctx, deviceID, command, "thread/list", map[string]any{
			"limit": 50, "sortKey": "updated_at", "sortDirection": "desc",
			"sourceKinds": []string{"cli", "vscode", "exec", "appServer"},
		})
	case "workspace.list":
		return b.requestWorkspaceCandidates(ctx, deviceID, command)
	case "thread.read":
		return b.requestAppServer(ctx, deviceID, command, "thread/read", map[string]any{"threadId": command.ThreadID, "includeTurns": true})
	case "thread.start":
		return b.requestAppServer(ctx, deviceID, command, "thread/start", map[string]any{"cwd": command.CWD})
	case "turn.start":
		if err := b.claimThread(command, deviceID); err != nil {
			return err
		}
		return b.requestAppServer(ctx, deviceID, command, "turn/start", map[string]any{"threadId": command.ThreadID, "input": []map[string]string{{"type": "text", "text": command.Text}}})
	case "turn.steer":
		if err := b.claimThread(command, deviceID); err != nil {
			return err
		}
		return b.requestAppServer(ctx, deviceID, command, "turn/steer", map[string]any{"threadId": command.ThreadID, "expectedTurnId": command.ExpectedTurnID, "input": []map[string]string{{"type": "text", "text": command.Text}}})
	case "turn.interrupt":
		return b.requestAppServer(ctx, deviceID, command, "turn/interrupt", map[string]string{"threadId": command.ThreadID, "turnId": command.TurnID})
	case "approval.respond":
		return b.respondApproval(command)
	case "event.ack":
		if b.journal == nil {
			return errors.New("logical journal is unavailable")
		}
		return b.journal.Ack(b.state.HostID, deviceID, command.HighestContiguousSequence)
	case "rpc":
		return b.requestAppServer(ctx, deviceID, command, command.Method, decodeRaw(command.Params))
	default:
		return b.sendEvent(ctx, deviceID, protocol.Event{Type: "error", RequestID: command.RequestID, Message: "unsupported command: " + command.Type, CreatedAt: time.Now().UnixMilli()}, "")
	}
}

func (b *bridge) requestWorkspaceCandidates(ctx context.Context, deviceID string, command protocol.Command) error {
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		result, err := b.app.client.Call(callCtx, "thread/list", map[string]any{
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
		_ = b.sendEvent(ctx, deviceID, protocol.Event{
			Type: "workspace.candidates", RequestID: command.RequestID,
			Payload: mustJSON(candidates), CreatedAt: time.Now().UnixMilli(),
		}, "")
	}()
	return nil
}

func (b *bridge) sendLogicalEvent(ctx context.Context, event protocol.LogicalEvent) error {
	if b.journal == nil {
		return errors.New("logical journal is unavailable")
	}
	if err := b.journal.Append(event); err != nil {
		return err
	}
	b.mu.Lock()
	encoded := b.state.DeviceSecrets[event.DeviceID]
	b.state.Sequences[event.DeviceID]++
	transportSequence := b.state.Sequences[event.DeviceID]
	stateErr := saveBridgeState(b.path, b.state)
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
	method string,
	params any,
) error {
	if method == "" {
		return errors.New("app-server method is required")
	}
	go func() {
		callCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
		defer cancel()
		result, err := b.app.client.Call(callCtx, method, params)
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

func (b *bridge) respondApproval(command protocol.Command) error {
	epoch := command.ProcessEpoch
	if epoch == "" {
		epoch = b.app.client.ProcessEpoch()
	}
	if command.ApprovalID != "" {
		if err := b.routes.ValidateResponse(command.ApprovalID, epoch, command.ServerRequestID); err != nil {
			return err
		}
	}
	return b.app.client.Respond(appserverrpc.ServerRequestRef{
		ID: command.ServerRequestID, ProcessEpoch: epoch,
	}, map[string]string{"decision": command.Decision})
}

func (b *bridge) handleAppServer(ctx context.Context, message appserverrpc.Message) {
	if message.Method == "" {
		return
	}
	if message.Method == "serverRequest/resolved" {
		var params struct {
			RequestID json.RawMessage `json:"requestId"`
		}
		if json.Unmarshal(message.Params, &params) == nil && len(params.RequestID) > 0 {
			_, _, _ = b.routes.MarkServerRequestResolved(b.app.client.ProcessEpoch(), params.RequestID)
		}
	}
	eventType, pushKind := "codex.event", ""
	approvalID := ""
	if message.Method == "item/commandExecution/requestApproval" ||
		message.Method == "item/fileChange/requestApproval" ||
		message.Method == "item/permissions/requestApproval" {
		eventType, pushKind = "approval.request", "approval"
		approvalID = stableApprovalID(b.app.client.ProcessEpoch(), message.ID)
		if route, ok := b.routeForParams(message.Params); ok {
			_ = b.routes.PutApproval(runstate.Approval{
				ApprovalID: approvalID, RunID: route.RunID,
				ProcessEpoch: b.app.client.ProcessEpoch(), ServerRequestID: message.ID,
				Status: runstate.ApprovalPending,
			})
		}
	}
	if message.Method == "item/tool/requestUserInput" {
		eventType, pushKind = "user_input.request", "approval"
	}
	if message.Method == "turn/completed" {
		pushKind = "completion"
	}
	envelope := map[string]any{
		"id": message.ID, "method": message.Method, "params": json.RawMessage(message.Params),
		"processEpoch": b.app.client.ProcessEpoch(),
	}
	if approvalID != "" {
		envelope["approvalId"] = approvalID
	}
	raw, err := json.Marshal(envelope)
	if err != nil {
		return
	}
	for _, deviceID := range b.eventTargets(message.Params) {
		_ = b.sendEvent(ctx, deviceID, protocol.Event{Type: eventType, Method: message.Method, Payload: raw, CreatedAt: time.Now().UnixMilli()}, pushKind)
	}
}

func (b *bridge) routeForParams(params json.RawMessage) (runstate.Route, bool) {
	threadID := threadIDFromParams(params)
	if threadID == "" || b.routes == nil {
		return runstate.Route{}, false
	}
	return b.routes.ByThread(threadID)
}

func (b *bridge) claimThread(command protocol.Command, deviceID string) error {
	if command.ThreadID == "" || deviceID == "" {
		return errors.New("thread and device are required")
	}
	runID := command.RunID
	if runID == "" {
		runID = "legacy:" + command.ThreadID
	}
	return b.routes.Put(runstate.Route{
		RunID: runID, BindingID: command.BindingID, WorkspaceID: command.WorkspaceID,
		HostID: b.state.HostID, DeviceID: deviceID, ThreadID: command.ThreadID, TurnID: command.TurnID,
	})
}

func (b *bridge) eventTargets(params json.RawMessage) []string {
	route, ok := b.routeForParams(params)
	if !ok || route.DeviceID == "" {
		return nil
	}
	return []string{route.DeviceID}
}

func threadIDFromParams(params json.RawMessage) string {
	var envelope struct {
		ThreadID string `json:"threadId"`
		Thread   struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if json.Unmarshal(params, &envelope) != nil {
		return ""
	}
	if envelope.ThreadID != "" {
		return envelope.ThreadID
	}
	return envelope.Thread.ID
}

func stableApprovalID(epoch string, serverRequestID json.RawMessage) string {
	digest := sha256.Sum256(append(append([]byte(epoch), 0), serverRequestID...))
	return "approval-" + hex.EncodeToString(digest[:16])
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

func startAppServer(codex string) (*appProcess, error) {
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
	epoch, err := protocol.NewID()
	if err != nil {
		_ = stdin.Close()
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		return nil, err
	}
	return &appProcess{cmd: cmd, stdin: stdin, client: appserverrpc.NewClient(stdout, stdin, epoch)}, nil
}

func (a *appProcess) close() {
	_ = a.stdin.Close()
	if a.cmd.Process != nil {
		_ = a.cmd.Process.Kill()
	}
	_ = a.cmd.Wait()
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
