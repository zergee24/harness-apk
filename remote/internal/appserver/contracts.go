package appserver

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
)

type InteractionKind string

const (
	InteractionUnknown   InteractionKind = "unknown"
	InteractionApproval  InteractionKind = "approval"
	InteractionUserInput InteractionKind = "userInput"
)

type ServerRequest struct {
	ID     json.RawMessage
	Method string
	Params json.RawMessage
	Kind   InteractionKind
}

func (r ServerRequest) IsApproval() bool {
	return r.Kind == InteractionApproval
}

func DecodeServerRequest(raw []byte) (ServerRequest, error) {
	var wire struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Params json.RawMessage `json:"params"`
	}
	if err := json.Unmarshal(raw, &wire); err != nil {
		return ServerRequest{}, fmt.Errorf("decode server request: %w", err)
	}
	if len(bytes.TrimSpace(wire.ID)) == 0 || bytes.Equal(bytes.TrimSpace(wire.ID), []byte("null")) {
		return ServerRequest{}, errors.New("server request id is required")
	}
	if wire.Method == "" {
		return ServerRequest{}, errors.New("server request method is required")
	}
	if len(bytes.TrimSpace(wire.Params)) == 0 || bytes.Equal(bytes.TrimSpace(wire.Params), []byte("null")) {
		return ServerRequest{}, errors.New("server request params are required")
	}

	kind := interactionKind(wire.Method)
	if err := validateInteractionIdentity(kind, wire.Params); err != nil {
		return ServerRequest{}, err
	}
	return ServerRequest{
		ID:     wire.ID,
		Method: wire.Method,
		Params: wire.Params,
		Kind:   kind,
	}, nil
}

func MobileApprovalDecisions() []string {
	return []string{"accept", "decline"}
}

func interactionKind(method string) InteractionKind {
	switch method {
	case "item/commandExecution/requestApproval",
		"item/fileChange/requestApproval",
		"item/permissions/requestApproval":
		return InteractionApproval
	case "item/tool/requestUserInput":
		return InteractionUserInput
	default:
		return InteractionUnknown
	}
}

func validateInteractionIdentity(kind InteractionKind, params json.RawMessage) error {
	if kind != InteractionApproval && kind != InteractionUserInput {
		return nil
	}
	var identity struct {
		ThreadID string `json:"threadId"`
		TurnID   string `json:"turnId"`
		ItemID   string `json:"itemId"`
	}
	if err := json.Unmarshal(params, &identity); err != nil {
		return fmt.Errorf("decode interaction identity: %w", err)
	}
	if identity.ThreadID == "" || identity.TurnID == "" || identity.ItemID == "" {
		return errors.New("server interaction threadId, turnId, and itemId are required")
	}
	return nil
}

type ThreadReadResponse struct {
	Thread ThreadSnapshot
}

type ThreadSnapshot struct {
	ID    string         `json:"id"`
	CWD   string         `json:"cwd"`
	Turns []TurnSnapshot `json:"turns"`
}

type TurnSnapshot struct {
	ID     string            `json:"id"`
	Status json.RawMessage   `json:"status"`
	Items  []json.RawMessage `json:"items"`
}

func DecodeThreadRead(raw []byte) (ThreadReadResponse, error) {
	var wire struct {
		Result struct {
			Thread ThreadSnapshot `json:"thread"`
		} `json:"result"`
	}
	if err := json.Unmarshal(raw, &wire); err != nil {
		return ThreadReadResponse{}, fmt.Errorf("decode thread/read response: %w", err)
	}
	if wire.Result.Thread.ID == "" {
		return ThreadReadResponse{}, errors.New("thread/read thread id is required")
	}
	if wire.Result.Thread.CWD == "" {
		return ThreadReadResponse{}, errors.New("thread/read cwd is required")
	}
	if wire.Result.Thread.Turns == nil {
		return ThreadReadResponse{}, errors.New("thread/read turns are required; call with includeTurns=true")
	}
	for index, turn := range wire.Result.Thread.Turns {
		if turn.ID == "" {
			return ThreadReadResponse{}, fmt.Errorf("thread/read turn %d id is required", index)
		}
		if turn.Items == nil {
			return ThreadReadResponse{}, fmt.Errorf("thread/read turn %q items are required", turn.ID)
		}
	}
	return ThreadReadResponse{Thread: wire.Result.Thread}, nil
}
