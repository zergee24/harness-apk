package backend

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/harnessapk/remote/internal/agent"
)

// AppServerAdapter translates provider-neutral agent operations to the
// canonical app-server protocol spoken by Backend.
type AppServerAdapter struct {
	raw      Backend
	manifest agent.Manifest
}

// NewAppServerAdapter wraps one canonical app-server backend as an
// agent.Executor. A nil backend is retained as an invalid executor so callers
// receive agent.ErrInvalid instead of a construction-time panic.
func NewAppServerAdapter(raw Backend) *AppServerAdapter {
	adapter := &AppServerAdapter{raw: raw}
	if raw == nil {
		return adapter
	}

	operations := map[agent.OperationKind]bool{
		agent.OperationListThreads:   true,
		agent.OperationReadThread:    true,
		agent.OperationStartThread:   true,
		agent.OperationStartTurn:     true,
		agent.OperationSteerTurn:     true,
		agent.OperationInterruptTurn: raw.ID() != "dsh",
	}
	adapter.manifest = agent.Manifest{
		BackendID:          raw.ID(),
		Name:               raw.Name(),
		ProcessEpoch:       raw.ProcessEpoch(),
		MobileCapabilities: append([]string(nil), raw.Capabilities()...),
		Operations:         operations,
	}
	return adapter
}

// Manifest returns an isolated snapshot so callers cannot mutate adapter
// capabilities or operation support.
func (a *AppServerAdapter) Manifest() agent.Manifest {
	manifest := a.manifest
	manifest.MobileCapabilities = append([]string(nil), a.manifest.MobileCapabilities...)
	manifest.Operations = make(map[agent.OperationKind]bool, len(a.manifest.Operations))
	for kind, supported := range a.manifest.Operations {
		manifest.Operations[kind] = supported
	}
	return manifest
}

// Execute validates and translates one typed operation, calls the raw
// app-server method, and normalizes its response.
func (a *AppServerAdapter) Execute(ctx context.Context, operation agent.Operation) (agent.Outcome, error) {
	if a == nil || a.raw == nil || operation == nil {
		return agent.Outcome{}, fmt.Errorf("%w: adapter, backend, and operation are required", agent.ErrInvalid)
	}
	if !a.manifest.Operations[operation.Kind()] {
		return agent.Outcome{}, fmt.Errorf("%w: operation %q is not supported by backend %q", agent.ErrUnsupported, operation.Kind(), a.manifest.BackendID)
	}
	if err := ctx.Err(); err != nil {
		return agent.Outcome{}, fmt.Errorf("%w: backend %q: %v", agent.ErrUnavailable, a.manifest.BackendID, err)
	}

	switch op := operation.(type) {
	case agent.ListThreads:
		return a.listThreads(ctx, op)
	case agent.ReadThread:
		return a.readThread(ctx, op)
	case agent.StartThread:
		return a.startThread(ctx, op)
	case agent.StartTurn:
		return a.startTurn(ctx, op)
	case agent.SteerTurn:
		return a.steerTurn(ctx, op)
	case agent.InterruptTurn:
		return a.interruptTurn(ctx, op)
	default:
		return agent.Outcome{}, fmt.Errorf("%w: unknown operation %q", agent.ErrUnsupported, operation.Kind())
	}
}

func (a *AppServerAdapter) listThreads(ctx context.Context, op agent.ListThreads) (agent.Outcome, error) {
	if op.Query.CWD == "" {
		return agent.Outcome{}, invalidField("list_threads", "cwd")
	}
	params := struct {
		Limit       int      `json:"limit"`
		SortKey     string   `json:"sortKey"`
		SortOrder   string   `json:"sortOrder"`
		SourceKinds []string `json:"sourceKinds"`
	}{
		Limit:       50,
		SortKey:     "updated_at",
		SortOrder:   "desc",
		SourceKinds: []string{"cli", "vscode", "exec", "appServer", "subAgent", "unknown"},
	}
	raw, err := a.call(ctx, "thread/list", params)
	if err != nil {
		return agent.Outcome{}, err
	}
	var response struct {
		Data []struct {
			ID  string `json:"id"`
			CWD string `json:"cwd"`
		} `json:"data"`
	}
	if err := decodeResult(raw, &response); err != nil {
		return agent.Outcome{}, protocolError("thread/list", err)
	}
	threads := make([]agent.ThreadSummary, 0, len(response.Data))
	for _, thread := range response.Data {
		if thread.ID == "" {
			return agent.Outcome{}, protocolError("thread/list", fmt.Errorf("thread id is missing"))
		}
		if thread.CWD == op.Query.CWD {
			threads = append(threads, agent.ThreadSummary{ID: thread.ID, CWD: thread.CWD})
		}
	}
	return agent.Outcome{Threads: &agent.ThreadPage{Threads: threads}}, nil
}

func (a *AppServerAdapter) readThread(ctx context.Context, op agent.ReadThread) (agent.Outcome, error) {
	if op.ThreadID == "" {
		return agent.Outcome{}, invalidField("read_thread", "thread_id")
	}
	params := struct {
		ThreadID     string `json:"threadId"`
		IncludeTurns bool   `json:"includeTurns"`
	}{ThreadID: op.ThreadID, IncludeTurns: op.IncludeTurns}
	raw, err := a.call(ctx, "thread/read", params)
	if err != nil {
		return agent.Outcome{}, err
	}
	var response struct {
		Thread struct {
			ID    string `json:"id"`
			CWD   string `json:"cwd"`
			Turns []struct {
				ID     string `json:"id"`
				Status string `json:"status"`
			} `json:"turns"`
		} `json:"thread"`
	}
	if err := decodeResult(raw, &response); err != nil {
		return agent.Outcome{}, protocolError("thread/read", err)
	}
	if response.Thread.ID == "" {
		return agent.Outcome{}, protocolError("thread/read", fmt.Errorf("thread id is missing"))
	}
	thread := &agent.ThreadSnapshot{ID: response.Thread.ID, CWD: response.Thread.CWD}
	thread.Turns = make([]agent.TurnSnapshot, 0, len(response.Thread.Turns))
	for _, turn := range response.Thread.Turns {
		if turn.ID == "" {
			return agent.Outcome{}, protocolError("thread/read", fmt.Errorf("turn id is missing"))
		}
		thread.Turns = append(thread.Turns, agent.TurnSnapshot{ID: turn.ID, Status: turn.Status})
	}
	return agent.Outcome{Thread: thread}, nil
}

func (a *AppServerAdapter) startThread(ctx context.Context, op agent.StartThread) (agent.Outcome, error) {
	if op.CWD == "" {
		return agent.Outcome{}, invalidField("start_thread", "cwd")
	}
	params := struct {
		CWD string `json:"cwd"`
	}{CWD: op.CWD}
	raw, err := a.call(ctx, "thread/start", params)
	if err != nil {
		return agent.Outcome{}, err
	}
	id, err := decodeNestedID(raw, "thread")
	if err != nil {
		return agent.Outcome{}, protocolError("thread/start", err)
	}
	return agent.Outcome{StartedThread: &agent.ThreadRef{ID: id}}, nil
}

func (a *AppServerAdapter) startTurn(ctx context.Context, op agent.StartTurn) (agent.Outcome, error) {
	if op.ThreadID == "" {
		return agent.Outcome{}, invalidField("start_turn", "thread_id")
	}
	if op.Text == "" {
		return agent.Outcome{}, invalidField("start_turn", "text")
	}
	if op.ClientMessageID == "" {
		return agent.Outcome{}, invalidField("start_turn", "client_message_id")
	}
	params := struct {
		ThreadID            string         `json:"threadId"`
		Input               []textInput    `json:"input"`
		ClientUserMessageID string         `json:"clientUserMessageId"`
		OutputSchema        map[string]any `json:"outputSchema"`
	}{
		ThreadID: op.ThreadID, Input: []textInput{{Type: "text", Text: op.Text}},
		ClientUserMessageID: op.ClientMessageID, OutputSchema: op.CompletionSchema,
	}
	raw, err := a.call(ctx, "turn/start", params)
	if err != nil {
		return agent.Outcome{}, err
	}
	id, err := decodeNestedID(raw, "turn")
	if err != nil {
		return agent.Outcome{}, protocolError("turn/start", err)
	}
	return agent.Outcome{StartedTurn: &agent.TurnRef{ID: id}}, nil
}

func (a *AppServerAdapter) steerTurn(ctx context.Context, op agent.SteerTurn) (agent.Outcome, error) {
	if op.ThreadID == "" {
		return agent.Outcome{}, invalidField("steer_turn", "thread_id")
	}
	if op.ExpectedTurnID == "" {
		return agent.Outcome{}, invalidField("steer_turn", "expected_turn_id")
	}
	if op.Text == "" {
		return agent.Outcome{}, invalidField("steer_turn", "text")
	}
	params := struct {
		ThreadID       string      `json:"threadId"`
		ExpectedTurnID string      `json:"expectedTurnId"`
		Input          []textInput `json:"input"`
	}{ThreadID: op.ThreadID, ExpectedTurnID: op.ExpectedTurnID, Input: []textInput{{Type: "text", Text: op.Text}}}
	raw, err := a.call(ctx, "turn/steer", params)
	if err != nil {
		return agent.Outcome{}, err
	}
	var response struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
		TurnID string `json:"turnId"`
	}
	if err := decodeResult(raw, &response); err != nil {
		return agent.Outcome{}, protocolError("turn/steer", err)
	}
	id := response.Turn.ID
	if id == "" {
		id = response.TurnID
	}
	if id == "" {
		return agent.Outcome{}, protocolError("turn/steer", fmt.Errorf("turn id is missing"))
	}
	return agent.Outcome{StartedTurn: &agent.TurnRef{ID: id}}, nil
}

func (a *AppServerAdapter) interruptTurn(ctx context.Context, op agent.InterruptTurn) (agent.Outcome, error) {
	if op.ThreadID == "" {
		return agent.Outcome{}, invalidField("interrupt_turn", "thread_id")
	}
	if op.TurnID == "" {
		return agent.Outcome{}, invalidField("interrupt_turn", "turn_id")
	}
	params := struct {
		ThreadID string `json:"threadId"`
		TurnID   string `json:"turnId"`
	}{ThreadID: op.ThreadID, TurnID: op.TurnID}
	if _, err := a.call(ctx, "turn/interrupt", params); err != nil {
		return agent.Outcome{}, err
	}
	return agent.Outcome{Empty: true}, nil
}

type textInput struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

func (a *AppServerAdapter) call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	raw, err := a.raw.Call(ctx, method, params)
	if err != nil {
		return nil, fmt.Errorf("%w: backend %q method %q: %v", agent.ErrUnavailable, a.manifest.BackendID, method, err)
	}
	return raw, nil
}

func decodeNestedID(raw json.RawMessage, field string) (string, error) {
	var response map[string]json.RawMessage
	if err := decodeResult(raw, &response); err != nil {
		return "", err
	}
	var ref struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(response[field], &ref); err != nil {
		return "", err
	}
	if ref.ID == "" {
		return "", fmt.Errorf("%s id is missing", field)
	}
	return ref.ID, nil
}

func decodeResult(raw json.RawMessage, destination any) error {
	var envelope struct {
		Result json.RawMessage `json:"result"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return err
	}
	if len(envelope.Result) != 0 && string(envelope.Result) != "null" {
		raw = envelope.Result
	}
	return json.Unmarshal(raw, destination)
}

func invalidField(operation, field string) error {
	return fmt.Errorf("%w: %s requires %s", agent.ErrInvalid, operation, field)
}

func protocolError(method string, cause error) error {
	return fmt.Errorf("%w: method %q: %v", agent.ErrProtocol, method, cause)
}
