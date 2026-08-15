package backend

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"sync"

	appserverrpc "github.com/harnessapk/remote/internal/appserver"
	"github.com/harnessapk/remote/internal/protocol"
)

// Spec describes how to start one backend process. The process must speak the
// canonical app-server JSON-RPC protocol over stdio (codex app-server today,
// `dsh --profile appserver` in G2).
type Spec struct {
	ID           string
	Name         string
	Capabilities []string
	Exec         string // executable
	Args         []string
}

// Codex is a supervised stdio backend process speaking the canonical
// app-server protocol.
type Codex struct {
	spec     Spec
	cmd      *exec.Cmd
	stdin    io.WriteCloser
	client   *appserverrpc.Client
	messages chan Message
	done     chan error

	closeOnce sync.Once
	closeErr  error
}

// StartCodex launches one backend process and wires its notification stream.
func StartCodex(spec Spec) (*Codex, error) {
	if spec.ID == "" || spec.Exec == "" {
		return nil, errors.New("backend id and executable are required")
	}
	args := append([]string{"app-server", "--listen", "stdio://"}, spec.Args...)
	cmd := exec.Command(spec.Exec, args...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return nil, err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		_ = stdin.Close()
		return nil, err
	}
	epoch, err := protocol.NewID()
	if err != nil {
		_ = stdin.Close()
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		return nil, err
	}
	id := NormalizeID(spec.ID)
	c := &Codex{
		spec: Spec{
			ID: id, Name: spec.Name, Capabilities: spec.Capabilities,
			Exec: spec.Exec, Args: spec.Args,
		},
		cmd: cmd, stdin: stdin,
		client:   appserverrpc.NewClient(stdout, stdin, epoch),
		messages: make(chan Message, 64),
		done:     make(chan error, 1),
	}
	c.client.SetNotificationHandler(func(message appserverrpc.Message) {
		c.messages <- Message{
			BackendID: id, ID: message.ID,
			Method: message.Method, Params: message.Params,
		}
	})
	return c, nil
}

func (c *Codex) ID() string             { return c.spec.ID }
func (c *Codex) Name() string           { return c.spec.Name }
func (c *Codex) Capabilities() []string { return append([]string(nil), c.spec.Capabilities...) }
func (c *Codex) ProcessEpoch() string   { return c.client.ProcessEpoch() }

func (c *Codex) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	return c.client.Call(ctx, method, params)
}

func (c *Codex) Notify(ctx context.Context, method string, params any) error {
	return c.client.Notify(method, params)
}

func (c *Codex) Respond(ctx context.Context, ref ServerRequestRef, result any) error {
	return c.client.Respond(appserverrpc.ServerRequestRef{
		ID: ref.ID, Method: ref.Method, Params: ref.Params, ProcessEpoch: ref.ProcessEpoch,
	}, result)
}

func (c *Codex) Messages() <-chan Message { return c.messages }
func (c *Codex) Done() <-chan error       { return c.done }

// Start launches the client read loop and forwards its exit to Done.
func (c *Codex) Start(ctx context.Context) {
	c.client.Start(ctx)
	go func() {
		err := <-c.client.Done()
		select {
		case c.done <- err:
		default:
		}
	}()
}

// Close stops the process and closes the message stream.
func (c *Codex) Close() error {
	c.closeOnce.Do(func() {
		_ = c.stdin.Close()
		if c.cmd.Process != nil {
			_ = c.cmd.Process.Kill()
		}
		_ = c.cmd.Wait()
		close(c.messages)
	})
	return c.closeErr
}
