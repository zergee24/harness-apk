package run

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/harnessapk/remote/internal/commandcache"
)

var ErrControlOutcomeUnknown = errors.New("run control outcome is unknown and requires reconciliation")

var errControlFinalizePending = errors.New("run control result is durable but cache finalization is pending")

type ControlCommand struct {
	Type           string `json:"type"`
	CommandID      string `json:"commandId"`
	RunID          string `json:"runId"`
	DeviceID       string `json:"deviceId"`
	BackendID      string `json:"backendId"`
	ExpectedTurnID string `json:"expectedTurnId"`
	DispatchTurnID string `json:"-"`
	Text           string `json:"text,omitempty"`
}

func (c ControlCommand) PayloadSHA256() string {
	raw, _ := json.Marshal(c)
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:])
}

func (c ControlCommand) legacyPayloadSHA256() string {
	legacy := struct {
		Type           string `json:"type"`
		CommandID      string `json:"commandId"`
		RunID          string `json:"runId"`
		DeviceID       string `json:"deviceId"`
		ExpectedTurnID string `json:"expectedTurnId"`
		Text           string `json:"text,omitempty"`
	}{c.Type, c.CommandID, c.RunID, c.DeviceID, c.ExpectedTurnID, c.Text}
	raw, _ := json.Marshal(legacy)
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:])
}

type ControlCoordinator struct {
	Cache      *commandcache.Store
	Routes     *RouteStore
	App        AppServerCaller
	Emit       func(context.Context, string, string, string, json.RawMessage) (string, error)
	EmitStable func(context.Context, string, string, string, string, json.RawMessage) (string, error)
}

type ControlReconciliationResult struct {
	Resolved bool
	ThreadID string
	TurnID   string
}

type controlReconciliationContext struct {
	Type           string `json:"type"`
	RunID          string `json:"runId"`
	DeviceID       string `json:"deviceId"`
	BackendID      string `json:"backendId"`
	ThreadID       string `json:"threadId"`
	ExpectedTurnID string `json:"expectedTurnId"`
	Text           string `json:"text,omitempty"`
}

func (c ControlCoordinator) Replay(command ControlCommand) (bool, error) {
	if err := validateControlCommand(command); err != nil {
		return true, err
	}
	if c.Cache == nil {
		return true, errors.New("run control command cache is unavailable")
	}
	record, ok := c.Cache.Lookup(command.CommandID)
	if !ok {
		return false, nil
	}
	canonicalHash := command.PayloadSHA256()
	if record.Type != command.Type {
		return true, errors.New("command id already belongs to another payload")
	}
	if record.PayloadSHA256 != canonicalHash {
		if record.PayloadSHA256 != command.legacyPayloadSHA256() {
			return true, errors.New("command id already belongs to another payload")
		}
		if c.Routes == nil {
			return true, errors.New("run route store is unavailable")
		}
		route, found := c.Routes.ByRunBackend(command.BackendID, command.RunID)
		if !found || route.DeviceID != command.DeviceID {
			return true, errors.New("legacy control route does not belong to backend and device")
		}
		migrated, err := c.Cache.MovePayloadHash(command.CommandID, record.PayloadSHA256, canonicalHash)
		if err != nil {
			return true, err
		}
		record = migrated
	}
	return true, reuseControlRecord(record)
}

func controlResultEventID(commandID string) string {
	digest := sha256.Sum256([]byte("run-control\x00" + commandID))
	return "control-" + hex.EncodeToString(digest[:16])
}

func (c ControlCoordinator) emitResult(
	ctx context.Context,
	commandID, deviceID, runID, eventType string,
	payload, result json.RawMessage,
) (string, error) {
	record, _ := c.Cache.Lookup(commandID)
	eventID := record.ResultEventID
	if eventID != "" {
		if len(record.ResultJSON) != 0 {
			result = record.ResultJSON
		}
	} else {
		eventID = controlResultEventID(commandID)
	}
	if c.EmitStable != nil {
		if record.ResultEventID == "" {
			if _, err := c.Cache.AttachResult(commandID, eventID, result); err != nil {
				return "", err
			}
		}
		if _, err := c.EmitStable(ctx, eventID, deviceID, runID, eventType, payload); err != nil {
			return "", err
		}
	} else {
		var err error
		eventID, err = c.Emit(ctx, deviceID, runID, eventType, payload)
		if err != nil {
			return "", err
		}
	}
	if _, err := c.Cache.Succeed(commandID, eventID, result); err != nil {
		if c.EmitStable != nil {
			return eventID, fmt.Errorf("%w: %v", errControlFinalizePending, err)
		}
		return "", err
	}
	return eventID, nil
}

func (c ControlCoordinator) Execute(ctx context.Context, command ControlCommand) error {
	if replayed, err := c.Replay(command); replayed {
		return err
	}
	if c.Cache == nil || c.Routes == nil || c.App == nil || c.Emit == nil {
		return errors.New("run control dependencies are incomplete")
	}
	route, ok := c.Routes.ByRunBackend(command.BackendID, command.RunID)
	if !ok || route.DeviceID != command.DeviceID {
		return errors.New("run route does not belong to device")
	}
	if route.BackendID != command.BackendID {
		return errors.New("run route does not belong to backend")
	}
	dispatchTurnID := command.DispatchTurnID
	if dispatchTurnID == "" {
		dispatchTurnID = command.ExpectedTurnID
	}
	if route.ThreadID == "" || route.TurnID == "" || route.TurnID != dispatchTurnID {
		return errors.New("run turn changed; snapshot required")
	}
	contextJSON, _ := json.Marshal(controlReconciliationContext{
		Type: command.Type, RunID: command.RunID, DeviceID: command.DeviceID, BackendID: command.BackendID,
		ThreadID: route.ThreadID, ExpectedTurnID: dispatchTurnID, Text: command.Text,
	})
	record, execute, err := c.Cache.BeginWithContext(command.CommandID, command.Type, command.PayloadSHA256(), contextJSON)
	if err != nil {
		return err
	}
	if !execute {
		return reuseControlRecord(record)
	}

	var method string
	var params any
	var eventType, latestLine, presentationKind string
	switch command.Type {
	case "run.steer":
		if command.Text == "" {
			return c.fail(command.CommandID, errors.New("steer text is required"))
		}
		method = "turn/steer"
		params = map[string]any{
			"threadId": route.ThreadID, "expectedTurnId": dispatchTurnID,
			"input": []map[string]string{{"type": "text", "text": command.Text}},
		}
		eventType, latestLine, presentationKind = "run.steered", "已补充方向", "STEER"
	case "run.interrupt":
		method = "turn/interrupt"
		params = map[string]string{"threadId": route.ThreadID, "turnId": dispatchTurnID}
		eventType, latestLine, presentationKind = "run.interrupt.accepted", "正在停止任务", "INTERRUPT"
	default:
		return c.fail(command.CommandID, fmt.Errorf("unsupported run control command %q", command.Type))
	}
	callResult, err := c.App.Call(ctx, method, params)
	if err != nil {
		_, _ = c.Cache.MarkUnknown(command.CommandID, err)
		return ErrControlOutcomeUnknown
	}
	if command.Type == "run.steer" {
		var response struct {
			TurnID string `json:"turnId"`
			Turn   struct {
				ID string `json:"id"`
			} `json:"turn"`
		}
		if err := json.Unmarshal(callResult, &response); err != nil {
			_, _ = c.Cache.MarkUnknown(command.CommandID, err)
			return ErrControlOutcomeUnknown
		}
		nextTurnID := response.TurnID
		if nextTurnID == "" {
			nextTurnID = response.Turn.ID
		}
		if nextTurnID == "" {
			cause := errors.New("steer response is missing turn id")
			_, _ = c.Cache.MarkUnknown(command.CommandID, cause)
			return ErrControlOutcomeUnknown
		}
		if err := c.Routes.AdvanceTurnBackend(route.BackendID, route.RunID, route.ThreadID, dispatchTurnID, nextTurnID); err != nil {
			_, _ = c.Cache.MarkUnknown(command.CommandID, err)
			return ErrControlOutcomeUnknown
		}
		route.TurnID = nextTurnID
	}
	payload := map[string]any{
		"commandId": command.CommandID, "threadId": route.ThreadID, "turnId": route.TurnID,
		"latestLine": latestLine, "presentationKind": presentationKind,
	}
	if command.Text != "" {
		payload["text"] = command.Text
	}
	raw, _ := json.Marshal(payload)
	result, _ := json.Marshal(map[string]string{
		"runId": route.RunID, "eventId": controlResultEventID(command.CommandID),
		"threadId": route.ThreadID, "turnId": route.TurnID,
	})
	if _, err := c.emitResult(ctx, command.CommandID, route.DeviceID, route.RunID, eventType, raw, result); err != nil {
		if errors.Is(err, errControlFinalizePending) {
			return nil
		}
		_, _ = c.Cache.MarkUnknown(command.CommandID, err)
		return ErrControlOutcomeUnknown
	}
	return nil
}

func (c ControlCoordinator) ReconcileUnknown(ctx context.Context, commandID string) (ControlReconciliationResult, error) {
	if c.Cache == nil || c.Routes == nil || c.App == nil || c.Emit == nil {
		return ControlReconciliationResult{}, errors.New("run control reconciliation dependencies are incomplete")
	}
	record, ok := c.Cache.Lookup(commandID)
	if !ok {
		return ControlReconciliationResult{}, errors.New("control command not found")
	}
	if record.Status != commandcache.StatusUnknown || record.Type != "run.steer" || len(record.ContextJSON) == 0 {
		return ControlReconciliationResult{}, nil
	}
	var reconciliation controlReconciliationContext
	if err := json.Unmarshal(record.ContextJSON, &reconciliation); err != nil {
		return ControlReconciliationResult{}, fmt.Errorf("decode control reconciliation context: %w", err)
	}
	if reconciliation.Type != "run.steer" || reconciliation.RunID == "" || reconciliation.DeviceID == "" ||
		reconciliation.BackendID == "" || reconciliation.ThreadID == "" || reconciliation.ExpectedTurnID == "" {
		return ControlReconciliationResult{}, errors.New("control reconciliation identity is incomplete")
	}
	route, ok := c.Routes.ByRunBackend(reconciliation.BackendID, reconciliation.RunID)
	if !ok || route.DeviceID != reconciliation.DeviceID || route.ThreadID != reconciliation.ThreadID {
		return ControlReconciliationResult{}, errors.New("control reconciliation route ownership changed")
	}
	result, err := c.App.Call(ctx, "thread/read", map[string]any{
		"threadId": reconciliation.ThreadID, "includeTurns": true,
	})
	if err != nil {
		return ControlReconciliationResult{}, err
	}
	var envelope struct {
		Thread struct {
			Turns []struct {
				ID string `json:"id"`
			} `json:"turns"`
		} `json:"thread"`
	}
	if err := json.Unmarshal(result, &envelope); err != nil {
		return ControlReconciliationResult{}, fmt.Errorf("decode control reconciliation thread: %w", err)
	}
	nextTurnID := ""
	for index, turn := range envelope.Thread.Turns {
		if turn.ID == reconciliation.ExpectedTurnID && index+1 < len(envelope.Thread.Turns) {
			nextTurnID = envelope.Thread.Turns[index+1].ID
			break
		}
	}
	if nextTurnID == "" {
		return ControlReconciliationResult{ThreadID: reconciliation.ThreadID}, nil
	}
	if route.TurnID == reconciliation.ExpectedTurnID {
		if err := c.Routes.AdvanceTurnBackend(
			reconciliation.BackendID, reconciliation.RunID, reconciliation.ThreadID,
			reconciliation.ExpectedTurnID, nextTurnID,
		); err != nil {
			return ControlReconciliationResult{}, err
		}
	} else if route.TurnID != nextTurnID {
		nextIndex, routeIndex := -1, -1
		for index, turn := range envelope.Thread.Turns {
			if turn.ID == nextTurnID {
				nextIndex = index
			}
			if turn.ID == route.TurnID {
				routeIndex = index
			}
		}
		if nextIndex < 0 || routeIndex < nextIndex {
			return ControlReconciliationResult{}, errors.New("control reconciliation turn identity changed")
		}
	}
	payload := map[string]any{
		"commandId": commandID, "threadId": reconciliation.ThreadID, "turnId": nextTurnID,
		"latestLine": "已补充方向", "presentationKind": "STEER",
	}
	if reconciliation.Text != "" {
		payload["text"] = reconciliation.Text
	}
	raw, _ := json.Marshal(payload)
	cacheResult, _ := json.Marshal(map[string]string{
		"runId": reconciliation.RunID, "eventId": controlResultEventID(commandID),
		"threadId": reconciliation.ThreadID, "turnId": nextTurnID,
	})
	if _, err := c.emitResult(
		ctx, commandID, reconciliation.DeviceID, reconciliation.RunID, "run.steered", raw, cacheResult,
	); err != nil {
		return ControlReconciliationResult{}, err
	}
	return ControlReconciliationResult{Resolved: true, ThreadID: reconciliation.ThreadID, TurnID: nextTurnID}, nil
}

func reuseControlRecord(record commandcache.Record) error {
	switch record.Status {
	case commandcache.StatusSucceeded, commandcache.StatusInFlight:
		return nil
	case commandcache.StatusUnknown:
		return ErrControlOutcomeUnknown
	default:
		return errors.New(record.LastError)
	}
}

func validateControlCommand(command ControlCommand) error {
	if command.CommandID == "" || command.RunID == "" || command.DeviceID == "" || command.BackendID == "" || command.ExpectedTurnID == "" {
		return errors.New("run control stable identity is required")
	}
	if command.Type != "run.steer" && command.Type != "run.interrupt" {
		return errors.New("unsupported run control command")
	}
	return nil
}

func (c ControlCoordinator) fail(commandID string, cause error) error {
	if c.Cache != nil {
		_, _ = c.Cache.Fail(commandID, cause)
	}
	return cause
}
