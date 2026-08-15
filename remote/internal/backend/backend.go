// Package backend defines the M4 backend abstraction: one process speaking
// the canonical app-server JSON-RPC protocol (codex app-server today; the dsh
// appserver profile in G2) plus an in-process fake for tests.
package backend

import (
	"context"
	"encoding/json"

	"github.com/harnessapk/remote/internal/protocol"
)

// Message is one normalized app-server event (the BackendEvent of the M4
// spec). Bridge-side translation (approvals, timeline, completion) consumes
// these, decoupled from the concrete protocol.
type Message struct {
	BackendID string
	ID        json.RawMessage // server request id (approvals), may be empty
	Method    string
	Params    json.RawMessage
}

// ServerRequestRef identifies a pending server request (approval/user input)
// inside one backend process epoch.
type ServerRequestRef struct {
	ID           json.RawMessage
	Method       string
	Params       json.RawMessage
	ProcessEpoch string
}

// Backend is one supervised agent process on the Mac.
type Backend interface {
	ID() string
	Name() string
	Capabilities() []string
	ProcessEpoch() string
	Call(ctx context.Context, method string, params any) (json.RawMessage, error)
	Notify(ctx context.Context, method string, params any) error
	Respond(ctx context.Context, ref ServerRequestRef, result any) error
	// Messages delivers normalized app-server events until the backend stops.
	Messages() <-chan Message
	// Done resolves once with the client/process exit cause.
	Done() <-chan error
	Close() error
}

// NormalizeID maps the legacy empty backend id to the default backend.
func NormalizeID(id string) string {
	if id == "" {
		return protocol.DefaultBackendID
	}
	return id
}

// CodexCapabilities is the capability set advertised by a codex backend
// (canonical app-server protocol with approvals and user input).
func CodexCapabilities() []string {
	return []string{
		"workspace.candidates.v1",
		"run.lifecycle.v1",
		"logical-replay.v1",
		"completion-evidence.v2",
		"turn-command-idempotency.v1",
		"thread-history-pagination.v1",
		"thread-latest-user-message.v1",
		"thread-execution-status.v1",
		"thread-lazy-continuation.v1",
		protocol.CapabilityApprovals,
		protocol.CapabilityUserInput,
	}
}
