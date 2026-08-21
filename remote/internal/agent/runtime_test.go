package agent

import (
	"context"
	"errors"
	"testing"
)

func TestFakeRuntimeRecordsTypedStartTurnAndReturnsTurnRef(t *testing.T) {
	fake := NewFake(Manifest{
		BackendID: "dsh",
		Operations: map[OperationKind]bool{
			OperationStartTurn: true,
		},
	})
	fake.Script(OperationStartTurn, Outcome{
		StartedTurn: &TurnRef{ID: "turn-2"},
	}, nil)

	outcome, err := fake.Execute(context.Background(), StartTurn{
		ThreadID:        "thread-1",
		Text:            "继续",
		ClientMessageID: "command-1",
	})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if outcome.StartedTurn == nil || outcome.StartedTurn.ID != "turn-2" {
		t.Fatalf("Execute() outcome = %#v", outcome)
	}

	calls := fake.Calls()
	if len(calls) != 1 {
		t.Fatalf("Calls() length = %d, want 1", len(calls))
	}
	call, ok := calls[0].(StartTurn)
	if !ok {
		t.Fatalf("Calls()[0] type = %T, want StartTurn", calls[0])
	}
	if call.ThreadID != "thread-1" || call.Text != "继续" || call.ClientMessageID != "command-1" {
		t.Fatalf("Calls()[0] = %#v", call)
	}
}

func TestFakeRuntimeRejectsUnsupportedInterruptBeforeRecording(t *testing.T) {
	fake := NewFake(Manifest{BackendID: "dsh"})

	_, err := fake.Execute(context.Background(), InterruptTurn{
		ThreadID: "thread-1",
		TurnID:   "turn-1",
	})
	if !errors.Is(err, ErrUnsupported) {
		t.Fatalf("Execute() error = %v, want ErrUnsupported", err)
	}
	if calls := fake.Calls(); len(calls) != 0 {
		t.Fatalf("Calls() = %#v, want no recorded calls", calls)
	}
}

func TestFakeRuntimeCloseIsIdempotentAndSignalsDone(t *testing.T) {
	fake := NewFake(Manifest{BackendID: "codex"})

	if err := fake.Close(); err != nil {
		t.Fatalf("first Close() error = %v", err)
	}
	if err := fake.Close(); err != nil {
		t.Fatalf("second Close() error = %v", err)
	}

	select {
	case err, ok := <-fake.Done():
		if ok && err != nil {
			t.Fatalf("Done() error = %v", err)
		}
	default:
		t.Fatal("Done() was not observable after Close()")
	}
}
