package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/harnessapk/remote/internal/imagegen"
	"github.com/harnessapk/remote/internal/protocol"
)

// fakeImageGenerator scripts generation results for bridge tests.
type fakeImageGenerator struct {
	result   imagegen.Result
	err      error
	started  chan<- struct{}
	block    <-chan struct{}
	requests []imagegen.Request
}

func (f *fakeImageGenerator) Generate(ctx context.Context, request imagegen.Request) (imagegen.Result, error) {
	f.requests = append(f.requests, request)
	if f.started != nil {
		f.started <- struct{}{}
	}
	if f.block != nil {
		select {
		case <-f.block:
		case <-ctx.Done():
			return imagegen.Result{}, ctx.Err()
		}
	}
	return f.result, f.err
}

// newImageTestBridge builds a bridge whose image events are captured instead of
// encrypted onto a websocket. No backend is registered on purpose: image.generate
// must be dispatched as a host-level command.
func newImageTestBridge(generator imageGenerator) (*bridge, <-chan protocol.Event) {
	events := make(chan protocol.Event, 4)
	return &bridge{
		imageGen: generator,
		imageSend: func(_ context.Context, _ string, event protocol.Event) error {
			events <- event
			return nil
		},
	}, events
}

func waitImageEvent(t *testing.T, events <-chan protocol.Event) protocol.Event {
	t.Helper()
	select {
	case event := <-events:
		return event
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for an image event")
		return protocol.Event{}
	}
}

func TestExecuteCommandImageGenerateIsHostLevel(t *testing.T) {
	generator := &fakeImageGenerator{result: imagegen.Result{
		ID: "img-1", Model: imagegen.DefaultModel, Size: "1024x1536", Quality: "low",
		MimeType: "image/png", Data: []byte("png-bytes"), Width: 1, Height: 1,
		DurationMs: 12, CostEstimateUsd: 0.009, SavedPath: "/Users/mac/.harness-remote/imagegen/img-1.png",
	}}
	b, events := newImageTestBridge(generator)

	// BackendID is empty and no backend is registered: falling through to backend
	// resolution would emit an error (or panic without a connection).
	err := b.executeCommand(context.Background(), "phone-123456789", protocol.Command{
		Type: "image.generate", RequestID: "req-1",
		Params: json.RawMessage(`{"prompt":"a flat red fox icon","size":"1024x1536","quality":"low"}`),
	})
	if err != nil {
		t.Fatalf("executeCommand: %v", err)
	}
	event := waitImageEvent(t, events)
	if event.Type != "image.generated" || event.RequestID != "req-1" || event.BackendID != "" {
		t.Fatalf("event = %#v", event)
	}
	var payload imageGeneratedPayload
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		t.Fatalf("decode payload: %v", err)
	}
	// Spec §2: the success payload echoes the original prompt and the resolved
	// model/size/quality for the APK's UI.
	if payload.ID != "img-1" || payload.Prompt != "a flat red fox icon" {
		t.Fatalf("payload = %#v", payload)
	}
	if payload.Model != imagegen.DefaultModel || payload.Size != "1024x1536" || payload.Quality != "low" {
		t.Fatalf("payload meta = %#v", payload)
	}
	if payload.MimeType != "image/png" || payload.DataBase64 != base64.StdEncoding.EncodeToString([]byte("png-bytes")) {
		t.Fatalf("payload image = %#v", payload)
	}
	if payload.Bytes != len("png-bytes") || payload.Width != 1 || payload.Height != 1 || payload.DurationMs != 12 {
		t.Fatalf("payload sizes = %#v", payload)
	}
	if payload.CostEstimateUsd != 0.009 || payload.SavedPath == "" {
		t.Fatalf("payload cost/savedPath = %#v", payload)
	}
	if len(generator.requests) != 1 || generator.requests[0].Model != imagegen.DefaultModel {
		t.Fatalf("generator requests = %#v", generator.requests)
	}
}

func TestExecuteCommandImageGenerateRejectsInvalidParams(t *testing.T) {
	generator := &fakeImageGenerator{}
	b, events := newImageTestBridge(generator)

	err := b.executeCommand(context.Background(), "phone-1", protocol.Command{
		Type: "image.generate", RequestID: "req-bad",
		Params: json.RawMessage(`{"prompt":"","size":"512x512"}`),
	})
	if err != nil {
		t.Fatalf("executeCommand: %v", err)
	}
	event := waitImageEvent(t, events)
	if event.Type != "image.error" || event.RequestID != "req-bad" || event.Message == "" {
		t.Fatalf("event = %#v", event)
	}
	var payload map[string]string
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		t.Fatalf("decode payload: %v", err)
	}
	if payload["code"] != codeImageInvalidParams {
		t.Fatalf("payload = %#v", payload)
	}
	if len(generator.requests) != 0 {
		t.Fatalf("generator was called for invalid params: %#v", generator.requests)
	}
}

func TestExecuteCommandImageGenerateUpstreamErrorEvent(t *testing.T) {
	generator := &fakeImageGenerator{err: &imagegen.Error{
		Code: "OPENAI_HTTP_401", Message: "OpenAI 鉴权失败（HTTP 401），请检查 OPENAI_API_KEY", HTTPStatus: 401,
	}}
	b, events := newImageTestBridge(generator)

	err := b.executeCommand(context.Background(), "phone-1", protocol.Command{
		Type: "image.generate", RequestID: "req-401",
		Params: json.RawMessage(`{"prompt":"top secret prompt"}`),
	})
	if err != nil {
		t.Fatalf("executeCommand: %v", err)
	}
	event := waitImageEvent(t, events)
	if event.Type != "image.error" || event.RequestID != "req-401" {
		t.Fatalf("event = %#v", event)
	}
	if !strings.Contains(event.Message, "鉴权失败") {
		t.Fatalf("message = %q", event.Message)
	}
	// Failed events must never echo the prompt; the APK only gets the code.
	if strings.Contains(event.Message, "top secret prompt") || strings.Contains(string(event.Payload), "top secret prompt") {
		t.Fatalf("image.error leaked the prompt: %#v", event)
	}
	var payload map[string]string
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		t.Fatalf("decode payload: %v", err)
	}
	if payload["code"] != "OPENAI_HTTP_401" {
		t.Fatalf("payload = %#v", payload)
	}
}

func TestImageGenerateBusyPerDevice(t *testing.T) {
	release := make(chan struct{})
	started := make(chan struct{}, 1)
	blocked := &fakeImageGenerator{
		result:  imagegen.Result{ID: "img-1", MimeType: "image/png", Data: []byte("png")},
		started: started,
		block:   release,
	}
	b, events := newImageTestBridge(blocked)
	ctx := context.Background()

	if err := b.executeCommand(ctx, "phone-1", protocol.Command{
		Type: "image.generate", RequestID: "req-first", Params: json.RawMessage(`{"prompt":"a fox"}`),
	}); err != nil {
		t.Fatalf("first executeCommand: %v", err)
	}
	// Wait until the first request owns the slot.
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("first generation did not start")
	}
	if err := b.executeCommand(ctx, "phone-1", protocol.Command{
		Type: "image.generate", RequestID: "req-busy", Params: json.RawMessage(`{"prompt":"a fox"}`),
	}); err != nil {
		t.Fatalf("second executeCommand: %v", err)
	}
	busy := waitImageEvent(t, events)
	if busy.Type != "image.error" || busy.RequestID != "req-busy" {
		t.Fatalf("event = %#v", busy)
	}
	var payload map[string]string
	_ = json.Unmarshal(busy.Payload, &payload)
	if payload["code"] != codeImageBusy {
		t.Fatalf("payload = %#v", payload)
	}
	close(release)
	done := waitImageEvent(t, events)
	if done.Type != "image.generated" || done.RequestID != "req-first" {
		t.Fatalf("event = %#v", done)
	}
}

func TestImageRateLimiterWindowAndDailyQuota(t *testing.T) {
	now := time.Date(2026, 10, 29, 12, 0, 0, 0, time.Local)
	limiter := newImageRateLimiter(func() time.Time { return now })

	// One in-flight request per device; a second one is rejected without
	// consuming quota.
	if err := limiter.acquire("phone-1"); err != nil {
		t.Fatalf("acquire first: %v", err)
	}
	assertImageErrorCode(t, limiter.acquire("phone-1"), codeImageBusy)
	limiter.release("phone-1")
	// Other devices get their own counters.
	if err := limiter.acquire("phone-2"); err != nil {
		t.Fatalf("acquire other device: %v", err)
	}
	limiter.release("phone-2")
	// Fill the four-per-minute sliding window (one already used above).
	for index := 0; index < imagePerMinutePerDevice-1; index++ {
		if err := limiter.acquire("phone-1"); err != nil {
			t.Fatalf("acquire within window: %v", err)
		}
		limiter.release("phone-1")
	}
	assertImageErrorCode(t, limiter.acquire("phone-1"), codeImageRateLimited)
	// The window slides: after a minute a new request is allowed again.
	now = now.Add(61 * time.Second)
	if err := limiter.acquire("phone-1"); err != nil {
		t.Fatalf("acquire after window: %v", err)
	}
	limiter.release("phone-1")
}

func TestImageRateLimiterDailyQuota(t *testing.T) {
	now := time.Date(2026, 10, 29, 12, 0, 0, 0, time.Local)
	limiter := &imageRateLimiter{
		perMinute: 1000, perDay: 2, now: func() time.Time { return now },
		devices: map[string]*imageDeviceUsage{},
	}
	for index := 0; index < 2; index++ {
		if err := limiter.acquire("phone-1"); err != nil {
			t.Fatalf("acquire %d: %v", index, err)
		}
		limiter.release("phone-1")
	}
	assertImageErrorCode(t, limiter.acquire("phone-1"), codeImageDailyLimit)
	// A new local day resets the daily counter.
	now = now.Add(24 * time.Hour)
	if err := limiter.acquire("phone-1"); err != nil {
		t.Fatalf("acquire next day: %v", err)
	}
	limiter.release("phone-1")
}

func assertImageErrorCode(t *testing.T, err error, wantCode string) {
	t.Helper()
	var commandError *imageCommandError
	if !errors.As(err, &commandError) {
		t.Fatalf("err = %v, want imageCommandError", err)
	}
	if commandError.code != wantCode {
		t.Fatalf("code = %q, want %q", commandError.code, wantCode)
	}
	if commandError.message == "" {
		t.Fatal("error message is empty")
	}
}

func TestShortID(t *testing.T) {
	if got := shortID("phone-123456789"); got != "phone-12" {
		t.Fatalf("shortID = %q", got)
	}
	if got := shortID("short"); got != "short" {
		t.Fatalf("shortID = %q", got)
	}
}
