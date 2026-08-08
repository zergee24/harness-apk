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

type ControlCommand struct {
	Type           string `json:"type"`
	CommandID      string `json:"commandId"`
	RunID          string `json:"runId"`
	DeviceID       string `json:"deviceId"`
	ExpectedTurnID string `json:"expectedTurnId"`
	Text           string `json:"text,omitempty"`
}

func (c ControlCommand) PayloadSHA256() string {
	raw, _ := json.Marshal(c)
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:])
}

type ControlCoordinator struct {
	Cache  *commandcache.Store
	Routes *RouteStore
	App    AppServerCaller
	Emit   func(context.Context, string, string, string, json.RawMessage) (string, error)
}

func (c ControlCoordinator) Execute(ctx context.Context, command ControlCommand) error {
	if err := validateControlCommand(command); err != nil {
		return err
	}
	record, execute, err := c.Cache.Begin(command.CommandID, command.Type, command.PayloadSHA256())
	if err != nil {
		return err
	}
	if !execute {
		switch record.Status {
		case commandcache.StatusSucceeded, commandcache.StatusInFlight:
			return nil
		case commandcache.StatusUnknown:
			return ErrControlOutcomeUnknown
		default:
			return errors.New(record.LastError)
		}
	}
	if c.Routes == nil || c.App == nil || c.Emit == nil {
		return c.fail(command.CommandID, errors.New("run control dependencies are incomplete"))
	}
	route, ok := c.Routes.ByRun(command.RunID)
	if !ok || route.DeviceID != command.DeviceID {
		return c.fail(command.CommandID, errors.New("run route does not belong to device"))
	}
	if route.ThreadID == "" || route.TurnID == "" || route.TurnID != command.ExpectedTurnID {
		return c.fail(command.CommandID, errors.New("run turn changed; snapshot required"))
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
			"threadId": route.ThreadID, "expectedTurnId": route.TurnID,
			"input": []map[string]string{{"type": "text", "text": command.Text}},
		}
		eventType, latestLine, presentationKind = "run.steered", "已补充方向", "STEER"
	case "run.interrupt":
		method = "turn/interrupt"
		params = map[string]string{"threadId": route.ThreadID, "turnId": route.TurnID}
		eventType, latestLine, presentationKind = "run.interrupt.accepted", "正在停止任务", "INTERRUPT"
	default:
		return c.fail(command.CommandID, fmt.Errorf("unsupported run control command %q", command.Type))
	}
	if _, err := c.App.Call(ctx, method, params); err != nil {
		_, _ = c.Cache.MarkUnknown(command.CommandID, err)
		return ErrControlOutcomeUnknown
	}
	payload := map[string]any{
		"commandId": command.CommandID, "threadId": route.ThreadID, "turnId": route.TurnID,
		"latestLine": latestLine, "presentationKind": presentationKind,
	}
	if command.Text != "" {
		payload["text"] = command.Text
	}
	raw, _ := json.Marshal(payload)
	eventID, err := c.Emit(ctx, route.DeviceID, route.RunID, eventType, raw)
	if err != nil {
		_, _ = c.Cache.MarkUnknown(command.CommandID, err)
		return ErrControlOutcomeUnknown
	}
	result, _ := json.Marshal(map[string]string{"runId": route.RunID, "eventId": eventID})
	_, err = c.Cache.Complete(command.CommandID, eventID, result)
	return err
}

func validateControlCommand(command ControlCommand) error {
	if command.CommandID == "" || command.RunID == "" || command.DeviceID == "" || command.ExpectedTurnID == "" {
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
