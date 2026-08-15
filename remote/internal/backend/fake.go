package backend

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
)

// Fake is an in-process Backend for bridge supervision and routing tests. It
// is scriptable: canned responses per method, scripted notifications, and an
// explicit Crash that resolves Done without Close.
type Fake struct {
	id     string
	name   string
	caps   []string
	epoch  string
	mu     sync.Mutex
	closed bool

	responses map[string]func(method string, params any) (json.RawMessage, error)
	messages  chan Message
	done      chan error
	calls     []FakeCall
}

// FakeCall records one Call invocation.
type FakeCall struct {
	Method string
	Params any
}

// NewFake builds a fake backend whose default Call answers
// `{"result":{"echo":<method>}}` for every method.
func NewFake(id string) *Fake {
	return &Fake{
		id:        NormalizeID(id),
		name:      "Fake " + NormalizeID(id),
		caps:      []string{"run.lifecycle.v1", "workspace.candidates.v1"},
		epoch:     "fake-epoch-" + NormalizeID(id),
		responses: map[string]func(method string, params any) (json.RawMessage, error){},
		messages:  make(chan Message, 64),
		done:      make(chan error, 1),
	}
}

func (f *Fake) ID() string             { return f.id }
func (f *Fake) Name() string           { return f.name }
func (f *Fake) Capabilities() []string { return append([]string(nil), f.caps...) }
func (f *Fake) ProcessEpoch() string   { return f.epoch }

// SetCapabilities overrides the advertised capability list.
func (f *Fake) SetCapabilities(caps []string) *Fake {
	f.caps = append([]string(nil), caps...)
	return f
}

// OnScript registers a canned response for one method.
func (f *Fake) OnScript(method string, respond func(method string, params any) (json.RawMessage, error)) *Fake {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.responses[method] = respond
	return f
}

// Emit pushes one notification into the message stream.
func (f *Fake) Emit(method string, params any) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return
	}
	raw, _ := json.Marshal(params)
	f.messages <- Message{BackendID: f.id, Method: method, Params: raw}
}

// Crash resolves Done with an error without closing the message stream, as a
// real process death would.
func (f *Fake) Crash(cause error) {
	select {
	case f.done <- cause:
	default:
	}
}

func (f *Fake) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	f.mu.Lock()
	f.calls = append(f.calls, FakeCall{Method: method, Params: params})
	respond, scripted := f.responses[method]
	f.mu.Unlock()
	if scripted {
		return respond(method, params)
	}
	return json.RawMessage(`{"result":{"echo":"` + method + `"}}`), nil
}

// Calls returns the recorded Call invocations.
func (f *Fake) Calls() []FakeCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]FakeCall(nil), f.calls...)
}

func (f *Fake) Notify(ctx context.Context, method string, params any) error { return nil }

func (f *Fake) Respond(ctx context.Context, ref ServerRequestRef, result any) error { return nil }

// Start is a no-op for the in-process fake.
func (f *Fake) Start(ctx context.Context) {}

func (f *Fake) Messages() <-chan Message { return f.messages }
func (f *Fake) Done() <-chan error       { return f.done }

// Close marks the fake closed and closes the message stream.
func (f *Fake) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return nil
	}
	f.closed = true
	close(f.messages)
	return nil
}

// Closed reports whether Close was called.
func (f *Fake) Closed() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.closed
}

// ErrFakeClosed is returned by scripted responses that observe a closed fake.
var ErrFakeClosed = errors.New("fake backend is closed")
