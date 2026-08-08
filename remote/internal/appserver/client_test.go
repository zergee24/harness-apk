package appserver

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"testing"
	"time"
)

func TestCallCorrelatesResponseWithoutBlockingEventDispatch(t *testing.T) {
	serverOutputReader, serverOutputWriter := io.Pipe()
	clientOutputReader, clientOutputWriter := io.Pipe()
	client := NewClient(serverOutputReader, clientOutputWriter, "epoch-1")
	notifications := make(chan Message, 1)
	client.SetNotificationHandler(func(message Message) { notifications <- message })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client.Start(ctx)

	resultCh := make(chan json.RawMessage, 1)
	errCh := make(chan error, 1)
	go func() {
		result, err := client.Call(ctx, "thread/read", map[string]any{"threadId": "thread-1"})
		if err != nil {
			errCh <- err
			return
		}
		resultCh <- result
	}()

	outgoing, err := bufio.NewReader(clientOutputReader).ReadBytes('\n')
	if err != nil {
		t.Fatal(err)
	}
	var request struct {
		ID int64 `json:"id"`
	}
	if err := json.Unmarshal(outgoing, &request); err != nil {
		t.Fatal(err)
	}
	_, _ = serverOutputWriter.Write([]byte(`{"method":"turn/started","params":{"threadId":"thread-1"}}` + "\n"))
	select {
	case notification := <-notifications:
		if notification.Method != "turn/started" {
			t.Fatalf("notification = %#v", notification)
		}
	case <-time.After(time.Second):
		t.Fatal("notification dispatch blocked behind pending call")
	}
	response, _ := json.Marshal(map[string]any{"id": request.ID, "result": map[string]any{"thread": map[string]string{"id": "thread-1"}}})
	_, _ = serverOutputWriter.Write(append(response, '\n'))
	select {
	case result := <-resultCh:
		if string(result) != `{"thread":{"id":"thread-1"}}` {
			t.Fatalf("result = %s", result)
		}
	case err := <-errCh:
		t.Fatal(err)
	case <-time.After(time.Second):
		t.Fatal("call response was not correlated")
	}
}
