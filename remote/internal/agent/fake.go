package agent

import (
	"context"
	"sync"
)

const defaultFakeEventBuffer = 64

type fakeScript struct {
	outcome Outcome
	err     error
}

// FakeRuntime is an in-process Runtime for seam and orchestration tests. Its
// scripts and calls use typed operations only.
type FakeRuntime struct {
	mu       sync.Mutex
	manifest Manifest
	scripts  map[OperationKind]fakeScript
	calls    []Operation
	events   chan Event
	done     chan error
	closed   bool
}

// NewFake creates a bounded, initially open fake runtime.
func NewFake(manifest Manifest) *FakeRuntime {
	return &FakeRuntime{
		manifest: cloneManifest(manifest),
		scripts:  make(map[OperationKind]fakeScript),
		events:   make(chan Event, defaultFakeEventBuffer),
		done:     make(chan error),
	}
}

// Manifest returns a defensive copy of the advertised runtime manifest.
func (f *FakeRuntime) Manifest() Manifest {
	f.mu.Lock()
	defer f.mu.Unlock()
	return cloneManifest(f.manifest)
}

// Script configures the typed result for one operation kind. A script remains
// active for subsequent calls until replaced.
func (f *FakeRuntime) Script(kind OperationKind, outcome Outcome, err error) *FakeRuntime {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.scripts == nil {
		f.scripts = make(map[OperationKind]fakeScript)
	}
	f.scripts[kind] = fakeScript{outcome: cloneOutcome(outcome), err: err}
	return f
}

// Execute records a supported typed operation and returns its scripted result.
// Unsupported operations are rejected before recording a call or consulting a
// script.
func (f *FakeRuntime) Execute(ctx context.Context, op Operation) (Outcome, error) {
	if ctx == nil || op == nil {
		return Outcome{}, ErrInvalid
	}
	select {
	case <-ctx.Done():
		return Outcome{}, ctx.Err()
	default:
	}

	call, ok := cloneOperation(op)
	if !ok {
		return Outcome{}, ErrInvalid
	}
	kind := call.Kind()

	f.mu.Lock()
	if !f.manifest.Operations[kind] {
		f.mu.Unlock()
		return Outcome{}, ErrUnsupported
	}
	if f.closed {
		f.mu.Unlock()
		return Outcome{}, ErrUnavailable
	}
	f.calls = append(f.calls, call)
	script, scripted := f.scripts[kind]
	f.mu.Unlock()

	if !scripted {
		return Outcome{}, nil
	}
	return cloneOutcome(script.outcome), script.err
}

// Calls returns defensive copies of the typed operations recorded by Execute.
func (f *FakeRuntime) Calls() []Operation {
	f.mu.Lock()
	defer f.mu.Unlock()
	calls := make([]Operation, 0, len(f.calls))
	for _, call := range f.calls {
		cloned, ok := cloneOperation(call)
		if ok {
			calls = append(calls, cloned)
		}
	}
	return calls
}

// Events returns the bounded normalized event stream.
func (f *FakeRuntime) Events() <-chan Event { return f.events }

// Emit queues one normalized event without blocking. ErrUnavailable indicates
// that the runtime is closed or the bounded queue is full.
func (f *FakeRuntime) Emit(event Event) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return ErrUnavailable
	}
	select {
	case f.events <- cloneEvent(event):
		return nil
	default:
		return ErrUnavailable
	}
}

// Done resolves when Close is called. Closing the channel makes completion
// observable to every current and future receiver.
func (f *FakeRuntime) Done() <-chan error { return f.done }

// Close is idempotent and closes both runtime streams.
func (f *FakeRuntime) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return nil
	}
	f.closed = true
	close(f.events)
	close(f.done)
	return nil
}

func cloneManifest(manifest Manifest) Manifest {
	clone := manifest
	clone.MobileCapabilities = append([]string(nil), manifest.MobileCapabilities...)
	if manifest.Operations != nil {
		clone.Operations = make(map[OperationKind]bool, len(manifest.Operations))
		for kind, supported := range manifest.Operations {
			clone.Operations[kind] = supported
		}
	}
	return clone
}

func cloneOperation(op Operation) (Operation, bool) {
	switch value := op.(type) {
	case ListThreads:
		return value, true
	case *ListThreads:
		if value == nil {
			return nil, false
		}
		return *value, true
	case ReadThread:
		return value, true
	case *ReadThread:
		if value == nil {
			return nil, false
		}
		return *value, true
	case StartThread:
		return value, true
	case *StartThread:
		if value == nil {
			return nil, false
		}
		return *value, true
	case StartTurn:
		value.CompletionSchema = cloneMap(value.CompletionSchema)
		return value, true
	case *StartTurn:
		if value == nil {
			return nil, false
		}
		clone := *value
		clone.CompletionSchema = cloneMap(value.CompletionSchema)
		return clone, true
	case SteerTurn:
		return value, true
	case *SteerTurn:
		if value == nil {
			return nil, false
		}
		return *value, true
	case InterruptTurn:
		return value, true
	case *InterruptTurn:
		if value == nil {
			return nil, false
		}
		return *value, true
	default:
		return nil, false
	}
}

func cloneMap(input map[string]any) map[string]any {
	if input == nil {
		return nil
	}
	clone := make(map[string]any, len(input))
	for key, value := range input {
		clone[key] = cloneSchemaValue(value)
	}
	return clone
}

func cloneSchemaValue(value any) any {
	switch value := value.(type) {
	case nil:
		return nil
	case map[string]any:
		return cloneMap(value)
	case []any:
		clone := make([]any, len(value))
		for index, item := range value {
			clone[index] = cloneSchemaValue(item)
		}
		return clone
	case []map[string]any:
		clone := make([]map[string]any, len(value))
		for index, item := range value {
			clone[index] = cloneMap(item)
		}
		return clone
	case []string:
		return append([]string(nil), value...)
	case []bool:
		return append([]bool(nil), value...)
	case []float64:
		return append([]float64(nil), value...)
	case []int:
		return append([]int(nil), value...)
	case []int64:
		return append([]int64(nil), value...)
	default:
		return value
	}
}

func cloneOutcome(outcome Outcome) Outcome {
	clone := outcome
	if outcome.Threads != nil {
		threads := *outcome.Threads
		threads.Threads = append([]ThreadSummary(nil), outcome.Threads.Threads...)
		clone.Threads = &threads
	}
	if outcome.Thread != nil {
		thread := *outcome.Thread
		thread.Turns = append([]TurnSnapshot(nil), outcome.Thread.Turns...)
		clone.Thread = &thread
	}
	if outcome.StartedThread != nil {
		started := *outcome.StartedThread
		clone.StartedThread = &started
	}
	if outcome.StartedTurn != nil {
		started := *outcome.StartedTurn
		clone.StartedTurn = &started
	}
	return clone
}

func cloneEvent(event Event) Event {
	clone := event
	if event.Turn != nil {
		turn := *event.Turn
		clone.Turn = &turn
	}
	return clone
}
