// Package agent defines the provider-neutral seam used by the host gateway to
// execute agent operations. Provider wire details belong behind this package.
package agent

import (
	"context"
	"errors"
)

// Executor is the smallest seam needed by callers that issue typed operations.
type Executor interface {
	Manifest() Manifest
	Execute(context.Context, Operation) (Outcome, error)
}

// Runtime adds lifecycle and normalized event streams to an Executor.
type Runtime interface {
	Executor
	Events() <-chan Event
	Done() <-chan error
	Close() error
}

// OperationKind identifies a provider-neutral operation. Values are semantic
// names and are intentionally independent of any provider wire method.
type OperationKind string

const (
	OperationListThreads   OperationKind = "list_threads"
	OperationReadThread    OperationKind = "read_thread"
	OperationStartThread   OperationKind = "start_thread"
	OperationStartTurn     OperationKind = "start_turn"
	OperationSteerTurn     OperationKind = "steer_turn"
	OperationInterruptTurn OperationKind = "interrupt_turn"
)

// Operation is a closed, typed union. The unexported marker prevents a
// provider wire request from escaping through this seam.
type Operation interface {
	Kind() OperationKind
	isOperation()
}

// ThreadQuery selects threads for a working directory.
type ThreadQuery struct {
	CWD string
}

// ListThreads lists threads matching Query.
type ListThreads struct {
	Query ThreadQuery
}

func (ListThreads) Kind() OperationKind { return OperationListThreads }
func (ListThreads) isOperation()        {}

// ReadThread reads one thread. IncludeTurns asks the provider for its turn
// snapshots when supported.
type ReadThread struct {
	ThreadID     string
	IncludeTurns bool
}

func (ReadThread) Kind() OperationKind { return OperationReadThread }
func (ReadThread) isOperation()        {}

// StartThread starts a new thread in CWD.
type StartThread struct {
	CWD string
}

func (StartThread) Kind() OperationKind { return OperationStartThread }
func (StartThread) isOperation()        {}

// StartTurn starts a turn in an existing thread.
type StartTurn struct {
	ThreadID         string
	Text             string
	ClientMessageID  string
	CompletionSchema map[string]any
}

func (StartTurn) Kind() OperationKind { return OperationStartTurn }
func (StartTurn) isOperation()        {}

// SteerTurn adds text to the expected active turn.
type SteerTurn struct {
	ThreadID       string
	ExpectedTurnID string
	Text           string
}

func (SteerTurn) Kind() OperationKind { return OperationSteerTurn }
func (SteerTurn) isOperation()        {}

// InterruptTurn requests interruption of a turn.
type InterruptTurn struct {
	ThreadID string
	TurnID   string
}

func (InterruptTurn) Kind() OperationKind { return OperationInterruptTurn }
func (InterruptTurn) isOperation()        {}

// ThreadSummary is the list-level representation of a thread.
type ThreadSummary struct {
	ID  string
	CWD string
}

// ThreadPage is a provider-neutral page of thread summaries.
type ThreadPage struct {
	Threads []ThreadSummary
}

// ThreadRef identifies a newly created or otherwise selected thread.
type ThreadRef struct {
	ID string
}

// TurnRef identifies a newly created or otherwise selected turn.
type TurnRef struct {
	ID string
}

// TurnSnapshot is the stable portion of a turn used by reconciliation and
// future event normalization.
type TurnSnapshot struct {
	ID     string
	Status string
}

// ThreadSnapshot is an authoritative thread view. Turns may be empty when the
// caller did not request them or when the provider has no turns.
type ThreadSnapshot struct {
	ID    string
	CWD   string
	Turns []TurnSnapshot
}

// Outcome is the provider-neutral one-of result of an Operation. Callers read
// the field corresponding to the operation they issued.
type Outcome struct {
	Threads       *ThreadPage
	Thread        *ThreadSnapshot
	StartedThread *ThreadRef
	StartedTurn   *TurnRef
	Empty         bool
}

// EventKind identifies a normalized runtime event.
type EventKind string

const (
	EventTurnStarted    EventKind = "turn.started"
	EventTurnCompleted  EventKind = "turn.completed"
	EventRuntimeStopped EventKind = "runtime.stopped"
)

// Event is the minimal provider-neutral event shape. It deliberately contains
// no raw method, request, or JSON payload fields.
type Event struct {
	BackendID    string
	ProcessEpoch string
	Kind         EventKind
	ThreadID     string
	TurnID       string
	ItemID       string
	Delta        string
	Turn         *TurnSnapshot
}

// Manifest advertises runtime identity and precise operation support.
type Manifest struct {
	BackendID          string
	Name               string
	ProcessEpoch       string
	MobileCapabilities []string
	Operations         map[OperationKind]bool
}

// Stable runtime error categories. Adapters may wrap these with a diagnostic
// cause while callers can still classify errors with errors.Is.
var (
	ErrInvalid        = errors.New("invalid runtime operation")
	ErrUnsupported    = errors.New("runtime operation unsupported")
	ErrUnavailable    = errors.New("runtime unavailable")
	ErrProtocol       = errors.New("runtime protocol violation")
	ErrExpired        = errors.New("runtime interaction expired")
	ErrOutcomeUnknown = errors.New("runtime outcome unknown")
)
