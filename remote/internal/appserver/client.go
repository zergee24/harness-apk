package appserver

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
)

type Message struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
	Result json.RawMessage `json:"result"`
	Error  json.RawMessage `json:"error"`
}

type ServerRequestRef struct {
	ID           json.RawMessage
	Method       string
	Params       json.RawMessage
	ProcessEpoch string
}

type callResult struct {
	result json.RawMessage
	err    error
}

type Client struct {
	reader       io.Reader
	writer       io.Writer
	processEpoch string

	startOnce sync.Once
	writeMu   sync.Mutex
	mu        sync.Mutex
	nextID    int64
	pending   map[string]chan callResult
	handler   func(Message)
	done      chan error
}

func NewClient(reader io.Reader, writer io.Writer, processEpoch string) *Client {
	return &Client{
		reader: reader, writer: writer, processEpoch: processEpoch,
		nextID: 100, pending: map[string]chan callResult{}, done: make(chan error, 1),
	}
}

func (c *Client) ProcessEpoch() string { return c.processEpoch }

func (c *Client) SetNotificationHandler(handler func(Message)) {
	c.mu.Lock()
	c.handler = handler
	c.mu.Unlock()
}

func (c *Client) Start(ctx context.Context) {
	c.startOnce.Do(func() { go c.readLoop(ctx) })
}

func (c *Client) Done() <-chan error { return c.done }

func (c *Client) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	if method == "" {
		return nil, errors.New("app-server method is required")
	}
	c.mu.Lock()
	c.nextID++
	id := c.nextID
	key := fmt.Sprint(id)
	future := make(chan callResult, 1)
	c.pending[key] = future
	c.mu.Unlock()
	if err := c.write(map[string]any{"method": method, "id": id, "params": params}); err != nil {
		c.removePending(key)
		return nil, err
	}
	select {
	case response := <-future:
		return response.result, response.err
	case <-ctx.Done():
		c.removePending(key)
		return nil, ctx.Err()
	}
}

func (c *Client) Notify(method string, params any) error {
	if method == "" {
		return errors.New("app-server notification method is required")
	}
	return c.write(map[string]any{"method": method, "params": params})
}

func (c *Client) Respond(request ServerRequestRef, result any) error {
	if request.ProcessEpoch != c.processEpoch {
		return errors.New("server request belongs to an expired process epoch")
	}
	var decodedID any
	if len(request.ID) == 0 || json.Unmarshal(request.ID, &decodedID) != nil {
		return errors.New("invalid server request id")
	}
	return c.write(map[string]any{"id": decodedID, "result": result})
}

func (c *Client) readLoop(ctx context.Context) {
	scanner := bufio.NewScanner(c.reader)
	scanner.Buffer(make([]byte, 64<<10), 4<<20)
	for scanner.Scan() {
		var message Message
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
			continue
		}
		if message.Method == "" && len(message.ID) > 0 && string(message.ID) != "null" {
			if c.resolve(message) {
				continue
			}
		}
		c.mu.Lock()
		handler := c.handler
		c.mu.Unlock()
		if handler != nil && message.Method != "" {
			handler(message)
		}
	}
	err := scanner.Err()
	if err == nil {
		err = io.EOF
	}
	select {
	case <-ctx.Done():
		err = ctx.Err()
	default:
	}
	c.failPending(err)
	c.done <- err
}

func (c *Client) resolve(message Message) bool {
	key := string(message.ID)
	c.mu.Lock()
	future := c.pending[key]
	delete(c.pending, key)
	c.mu.Unlock()
	if future == nil {
		return false
	}
	if len(message.Error) > 0 && string(message.Error) != "null" {
		future <- callResult{err: fmt.Errorf("app-server error: %s", message.Error)}
	} else {
		future <- callResult{result: append(json.RawMessage(nil), message.Result...)}
	}
	return true
}

func (c *Client) removePending(key string) {
	c.mu.Lock()
	delete(c.pending, key)
	c.mu.Unlock()
}

func (c *Client) failPending(cause error) {
	c.mu.Lock()
	pending := c.pending
	c.pending = map[string]chan callResult{}
	c.mu.Unlock()
	for _, future := range pending {
		future <- callResult{err: cause}
	}
}

func (c *Client) write(value any) error {
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_, err = c.writer.Write(append(raw, '\n'))
	return err
}
