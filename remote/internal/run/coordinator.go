package run

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/harnessapk/remote/internal/agent"
	"github.com/harnessapk/remote/internal/commandcache"
	"github.com/harnessapk/remote/internal/protocol"
	"github.com/harnessapk/remote/internal/workspace"
)

var (
	ErrBindingMismatch = errors.New("workspace binding fingerprint mismatch")
	ErrCommandUnknown  = errors.New("run start outcome is unknown and requires reconciliation")
	ErrCommandInFlight = errors.New("run start is already in flight")
)

type StartCommand struct {
	CommandID             string `json:"commandId"`
	RunID                 string `json:"runId"`
	BindingID             string `json:"bindingId"`
	WorkspaceID           string `json:"workspaceId"`
	DeviceID              string `json:"deviceId"`
	BackendID             string `json:"backendId"`
	RepositoryFingerprint string `json:"repositoryFingerprint"`
	Objective             string `json:"objective"`
}

func (c StartCommand) PayloadSHA256() string {
	raw, _ := json.Marshal(c)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func (c StartCommand) legacyPayloadSHA256() string {
	legacy := struct {
		CommandID             string `json:"commandId"`
		RunID                 string `json:"runId"`
		BindingID             string `json:"bindingId"`
		WorkspaceID           string `json:"workspaceId"`
		DeviceID              string `json:"deviceId"`
		RepositoryFingerprint string `json:"repositoryFingerprint"`
		Objective             string `json:"objective"`
	}{c.CommandID, c.RunID, c.BindingID, c.WorkspaceID, c.DeviceID, c.RepositoryFingerprint, c.Objective}
	raw, _ := json.Marshal(legacy)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

type StartResult struct {
	RunID    string `json:"runId"`
	ThreadID string `json:"threadId"`
	TurnID   string `json:"turnId"`
	EventID  string `json:"eventId"`
}

type Coordinator struct {
	Cache            *commandcache.Store
	Routes           *RouteStore
	Runtime          agent.Executor
	HostID           string
	ResolveWorkspace func(deviceID, workspaceID string) (workspace.Candidate, bool)
	InspectWorkspace func(cwd string) (workspace.Candidate, error)
	CaptureBaseline  func(cwd string) (workspace.Baseline, error)
	ExecuteTurn      func(ctx context.Context, operation agent.Operation) (agent.Outcome, error)
	Emit             func(ctx context.Context, deviceID, runID, eventType string, payload json.RawMessage) (string, error)
}

func (c Coordinator) Start(ctx context.Context, command StartCommand) (StartResult, error) {
	if c.Cache == nil {
		return StartResult{}, errors.New("run command cache is required")
	}
	if err := validateStartCommand(c.HostID, command); err != nil {
		return StartResult{}, err
	}
	payloadHash := command.PayloadSHA256()
	legacyCache := false
	if existing, ok := c.Cache.Lookup(command.CommandID); ok && existing.PayloadSHA256 == command.legacyPayloadSHA256() {
		ownerBackendID := protocol.DefaultBackendID
		if c.Routes != nil {
			if route, found := c.Routes.ByRun(command.RunID); found {
				ownerBackendID = route.BackendID
			}
		}
		if command.BackendID != ownerBackendID {
			return StartResult{}, errors.New("legacy run.start cache belongs to another backend")
		}
		payloadHash = existing.PayloadSHA256
		legacyCache = true
	}
	record, execute, err := c.Cache.Begin(command.CommandID, "run.start", payloadHash)
	if err != nil {
		return StartResult{}, err
	}
	if !execute {
		if legacyCache {
			if _, err := c.Cache.MovePayloadHash(command.CommandID, record.PayloadSHA256, command.PayloadSHA256()); err != nil {
				return StartResult{}, err
			}
		}
		return cachedStartResult(record)
	}
	if c.Routes == nil || c.Runtime == nil || c.ResolveWorkspace == nil || c.InspectWorkspace == nil || c.Emit == nil {
		return StartResult{}, c.fail(command.CommandID, errors.New("run coordinator dependencies are incomplete"))
	}
	route := Route{
		RunID: command.RunID, BindingID: command.BindingID, WorkspaceID: command.WorkspaceID,
		HostID: c.HostID, DeviceID: command.DeviceID, BackendID: command.BackendID,
	}
	if err := c.Routes.Reserve(route); err != nil {
		return StartResult{}, c.fail(command.CommandID, err)
	}
	startingPayload, _ := json.Marshal(map[string]string{
		"commandId":  command.CommandID,
		"latestLine": "Mac 正在启动任务",
	})
	if _, err := c.Emit(ctx, command.DeviceID, command.RunID, "run.starting", startingPayload); err != nil {
		return StartResult{}, c.fail(command.CommandID, err)
	}

	registered, ok := c.ResolveWorkspace(command.DeviceID, command.WorkspaceID)
	if !ok {
		return StartResult{}, c.fail(command.CommandID, errors.New("workspace is not registered for this device"))
	}
	current, err := c.InspectWorkspace(registered.CWD)
	if err != nil {
		return StartResult{}, c.fail(command.CommandID, fmt.Errorf("inspect workspace: %w", err))
	}
	if registered.RepositoryFingerprint != command.RepositoryFingerprint ||
		current.RepositoryFingerprint != command.RepositoryFingerprint ||
		current.WorkspaceID != command.WorkspaceID {
		return StartResult{}, c.fail(command.CommandID, ErrBindingMismatch)
	}

	workspaceBaseline := workspace.Baseline{
		IsGit: current.RepositoryLabel != "", Branch: current.Branch,
	}
	if c.CaptureBaseline != nil {
		workspaceBaseline, err = c.CaptureBaseline(current.CWD)
		if err != nil {
			return StartResult{}, c.fail(command.CommandID, fmt.Errorf("capture workspace baseline: %w", err))
		}
	}
	baseline, _ := json.Marshal(WorkspaceBaseline{
		CWD: current.CWD, RepositoryFingerprint: current.RepositoryFingerprint,
		IsGit: workspaceBaseline.IsGit, Head: workspaceBaseline.Head, Branch: workspaceBaseline.Branch,
		PorcelainV2Z: workspaceBaseline.PorcelainV2Z, CapturedAt: workspaceBaseline.CapturedAt,
	})
	route.BaselineJSON = string(baseline)
	if err := c.Routes.Put(route); err != nil {
		return StartResult{}, c.fail(command.CommandID, err)
	}

	threadID, err := c.findRecentThread(ctx, current.CWD)
	if err != nil {
		return StartResult{}, c.fail(command.CommandID, err)
	}
	reusedRecentThread := threadID != ""
	if threadID == "" {
		outcome, callErr := c.Runtime.Execute(ctx, agent.StartThread{CWD: current.CWD})
		if callErr != nil {
			return StartResult{}, c.unknown(command.CommandID, callErr)
		}
		if outcome.StartedThread == nil || outcome.StartedThread.ID == "" {
			return StartResult{}, c.unknown(command.CommandID, fmt.Errorf(
				"%w: start thread outcome is missing thread id", agent.ErrProtocol))
		}
		threadID = outcome.StartedThread.ID
	}
	route.ThreadID = threadID
	if err := c.Routes.Put(route); err != nil {
		return StartResult{}, c.unknown(command.CommandID, err)
	}

	turnOutcome, err := c.executeTurn(ctx, command, threadID)
	if err != nil && reusedRecentThread && isThreadNotFoundError(err, threadID) {
		outcome, callErr := c.Runtime.Execute(ctx, agent.StartThread{CWD: current.CWD})
		if callErr != nil {
			return StartResult{}, c.unknown(command.CommandID, callErr)
		}
		if outcome.StartedThread == nil || outcome.StartedThread.ID == "" {
			return StartResult{}, c.unknown(command.CommandID, fmt.Errorf(
				"%w: start thread outcome is missing thread id", agent.ErrProtocol))
		}
		threadID = outcome.StartedThread.ID
		route.ThreadID = threadID
		route.TurnID = ""
		if callErr = c.Routes.Put(route); callErr != nil {
			return StartResult{}, c.unknown(command.CommandID, callErr)
		}
		turnOutcome, err = c.executeTurn(ctx, command, threadID)
	}
	if err != nil {
		return StartResult{}, c.unknown(command.CommandID, err)
	}
	if turnOutcome.StartedTurn == nil || turnOutcome.StartedTurn.ID == "" {
		return StartResult{}, c.unknown(command.CommandID, fmt.Errorf(
			"%w: start turn outcome is missing turn id", agent.ErrProtocol))
	}
	turnID := turnOutcome.StartedTurn.ID
	route.TurnID = turnID
	if err := c.Routes.Put(route); err != nil {
		return StartResult{}, c.unknown(command.CommandID, err)
	}
	payload, _ := json.Marshal(map[string]string{
		"commandId": command.CommandID, "runId": command.RunID, "threadId": threadID, "turnId": turnID,
		"latestLine": "Mac 已接收任务",
	})
	eventID, err := c.Emit(ctx, command.DeviceID, command.RunID, "run.started", payload)
	if err != nil {
		return StartResult{}, c.unknown(command.CommandID, err)
	}
	result := StartResult{RunID: command.RunID, ThreadID: threadID, TurnID: turnID, EventID: eventID}
	raw, _ := json.Marshal(result)
	if _, err := c.Cache.Complete(command.CommandID, eventID, raw); err != nil {
		return StartResult{}, err
	}
	return result, nil
}

func (c Coordinator) executeTurn(ctx context.Context, command StartCommand, threadID string) (agent.Outcome, error) {
	operation := agent.StartTurn{
		ThreadID:        threadID,
		Text:            command.Objective,
		ClientMessageID: command.CommandID,
		CompletionSchema: completionOutputSchema(),
	}
	if c.ExecuteTurn != nil {
		return c.ExecuteTurn(ctx, operation)
	}
	return c.Runtime.Execute(ctx, operation)
}

func isThreadNotFoundError(err error, threadID string) bool {
	return err != nil && threadID != "" && strings.Contains(err.Error(), "thread not found: "+threadID)
}

func validateStartCommand(hostID string, command StartCommand) error {
	if hostID == "" || command.CommandID == "" || command.RunID == "" || command.BindingID == "" ||
		command.WorkspaceID == "" || command.DeviceID == "" || command.RepositoryFingerprint == "" || command.Objective == "" {
		return errors.New("run.start stable identity and objective are required")
	}
	return nil
}

func (c Coordinator) findRecentThread(ctx context.Context, cwd string) (string, error) {
	outcome, err := c.Runtime.Execute(ctx, agent.ListThreads{Query: agent.ThreadQuery{CWD: cwd}})
	if err != nil {
		return "", err
	}
	if outcome.Threads == nil {
		return "", fmt.Errorf("%w: list threads outcome is missing", agent.ErrProtocol)
	}
	for _, thread := range outcome.Threads.Threads {
		if thread.ID != "" && thread.CWD == cwd {
			return thread.ID, nil
		}
	}
	return "", nil
}

func completionOutputSchema() map[string]any {
	return map[string]any{
		"type": "object",
		"properties": map[string]any{
			"summary":    map[string]string{"type": "string"},
			"unresolved": map[string]any{"type": "array", "items": map[string]string{"type": "string"}},
		},
		"required":             []string{"summary", "unresolved"},
		"additionalProperties": false,
	}
}

func cachedStartResult(record commandcache.Record) (StartResult, error) {
	switch record.Status {
	case commandcache.StatusSucceeded:
		var result StartResult
		if err := json.Unmarshal(record.ResultJSON, &result); err != nil {
			return StartResult{}, err
		}
		return result, nil
	case commandcache.StatusUnknown:
		return StartResult{}, ErrCommandUnknown
	case commandcache.StatusInFlight:
		return StartResult{}, ErrCommandInFlight
	case commandcache.StatusFailed:
		if record.LastError == ErrBindingMismatch.Error() {
			return StartResult{}, ErrBindingMismatch
		}
		return StartResult{}, errors.New(record.LastError)
	default:
		return StartResult{}, errors.New("unsupported cached command status")
	}
}

func (c Coordinator) fail(commandID string, cause error) error {
	_, persistErr := c.Cache.Fail(commandID, cause)
	return errors.Join(cause, persistErr)
}

func (c Coordinator) unknown(commandID string, cause error) error {
	_, persistErr := c.Cache.MarkUnknown(commandID, cause)
	return errors.Join(ErrCommandUnknown, cause, persistErr)
}
