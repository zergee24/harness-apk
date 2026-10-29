package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/harnessapk/remote/internal/imagegen"
	"github.com/harnessapk/remote/internal/protocol"
)

// imageGenerator is the bridge-side image backend. *imagegen.Service implements
// it in production; tests substitute a fake so command dispatch and rate
// limiting can be exercised without network access.
type imageGenerator interface {
	Generate(ctx context.Context, request imagegen.Request) (imagegen.Result, error)
}

// imageError codes that are produced by the bridge itself (the imagegen package
// has its own for upstream failures).
const (
	codeImageInvalidParams = "INVALID_PARAMS"
	codeImageBusy          = "IMAGE_BUSY"
	codeImageRateLimited   = "IMAGE_RATE_LIMITED"
	codeImageDailyLimit    = "IMAGE_DAILY_LIMIT"
)

// Per-device limits. The protocol keeps them in bridge memory, so a restart
// clears the counters (explicitly acceptable in remote/docs/image-generate.md).
const (
	imageConcurrentPerDevice = 1
	imagePerMinutePerDevice  = 4
	imagePerDayPerDevice     = 200
)

// imageCommandError is a bridge-side (validation/rate limit) error that the APK
// receives as image.error with the same code and Chinese message.
type imageCommandError struct {
	code    string
	message string
}

func (e *imageCommandError) Error() string { return e.message }

// imageGeneratedPayload is the image.generated payload defined by
// remote/docs/image-generate.md §2.
type imageGeneratedPayload struct {
	ID              string  `json:"id"`
	Prompt          string  `json:"prompt"`
	Model           string  `json:"model"`
	Size            string  `json:"size"`
	Quality         string  `json:"quality"`
	MimeType        string  `json:"mimeType"`
	DataBase64      string  `json:"dataBase64"`
	Bytes           int     `json:"bytes"`
	Width           int     `json:"width"`
	Height          int     `json:"height"`
	DurationMs      int64   `json:"durationMs"`
	CostEstimateUsd float64 `json:"costEstimateUsd"`
	SavedPath       string  `json:"savedPath,omitempty"`
}

// newImageService builds the production image generator. proxyURL is the
// optional --image-proxy override; empty means HARNESS_IMAGE_PROXY (or the
// standard HTTPS_PROXY/HTTP_PROXY lookup inside the service).
func newImageService(proxyURL string) imageGenerator {
	return imagegen.New(imagegen.Config{ProxyURL: proxyURL})
}

// imageGeneratorOrDefault mirrors imagegen.Service lazily when serve did not
// install one (mainly tests). executeCommand is serialized by the relay read
// loop, so lazy initialization needs no extra lock.
func (b *bridge) imageGeneratorOrDefault() imageGenerator {
	if b.imageGen == nil {
		b.imageGen = newImageService("")
	}
	return b.imageGen
}

// imageLimiterOrDefault installs the in-memory per-device limiter on first use
// (see imageGeneratorOrDefault for the locking argument).
func (b *bridge) imageLimiterOrDefault() *imageRateLimiter {
	if b.imageLimiter == nil {
		b.imageLimiter = newImageRateLimiter(time.Now)
	}
	return b.imageLimiter
}

// sendImageEvent emits one image event. Tests replace imageSend to capture
// events without a websocket connection.
func (b *bridge) sendImageEvent(ctx context.Context, deviceID string, event protocol.Event) error {
	if b.imageSend != nil {
		return b.imageSend(ctx, deviceID, event)
	}
	return b.sendEvent(ctx, deviceID, event, "")
}

// handleImageGenerate runs one host-level image.generate command. Request
// validation and quota reservation happen synchronously so a bad or blocked
// request fails fast, then generation continues in the background: an image can
// take 5-30s and the relay read loop must keep processing other messages.
func (b *bridge) handleImageGenerate(ctx context.Context, deviceID string, command protocol.Command) error {
	request := imagegen.Request{}
	if len(command.Params) > 0 {
		if err := json.Unmarshal(command.Params, &request); err != nil {
			return b.sendImageError(ctx, deviceID, command.RequestID,
				&imageCommandError{code: codeImageInvalidParams, message: "生图参数格式错误"})
		}
	}
	request = request.Normalize()
	if err := request.Validate(); err != nil {
		return b.sendImageError(ctx, deviceID, command.RequestID, err)
	}
	generator := b.imageGeneratorOrDefault()
	limiter := b.imageLimiterOrDefault()
	if err := limiter.acquire(deviceID); err != nil {
		return b.sendImageError(ctx, deviceID, command.RequestID, err)
	}
	go func() {
		defer limiter.release(deviceID)
		result, err := generator.Generate(ctx, request)
		if err != nil {
			code, _ := imageErrorDetails(err)
			// Never log the prompt or the API key: only requestId/deviceId
			// prefixes plus the classification are allowed.
			log.Printf("image.generate failed requestId=%s deviceId=%s code=%s",
				shortID(command.RequestID), shortID(deviceID), code)
			if sendErr := b.sendImageError(ctx, deviceID, command.RequestID, err); sendErr != nil {
				log.Printf("image.generate send error requestId=%s: %v", shortID(command.RequestID), sendErr)
			}
			return
		}
		payload := imageGeneratedPayload{
			ID:              result.ID,
			Prompt:          request.Prompt,
			Model:           firstNonEmpty(result.Model, request.Model),
			Size:            firstNonEmpty(result.Size, request.Size),
			Quality:         firstNonEmpty(result.Quality, request.Quality),
			MimeType:        result.MimeType,
			DataBase64:      base64.StdEncoding.EncodeToString(result.Data),
			Bytes:           len(result.Data),
			Width:           result.Width,
			Height:          result.Height,
			DurationMs:      result.DurationMs,
			CostEstimateUsd: result.CostEstimateUsd,
			SavedPath:       result.SavedPath,
		}
		log.Printf("image.generate done requestId=%s deviceId=%s model=%s size=%s quality=%s bytes=%d durationMs=%d",
			shortID(command.RequestID), shortID(deviceID), payload.Model, payload.Size, payload.Quality,
			payload.Bytes, payload.DurationMs)
		if sendErr := b.sendImageEvent(ctx, deviceID, protocol.Event{
			Type: "image.generated", RequestID: command.RequestID,
			Payload: mustJSON(payload), CreatedAt: time.Now().UnixMilli(),
		}); sendErr != nil {
			log.Printf("image.generate send result requestId=%s: %v", shortID(command.RequestID), sendErr)
		}
	}()
	return nil
}

// sendImageError emits image.error with a Chinese message and the machine
// readable code in payload.code.
func (b *bridge) sendImageError(ctx context.Context, deviceID, requestID string, cause error) error {
	code, message := imageErrorDetails(cause)
	return b.sendImageEvent(ctx, deviceID, protocol.Event{
		Type: "image.error", RequestID: requestID, Message: message,
		Payload: mustJSON(map[string]string{"code": code}), CreatedAt: time.Now().UnixMilli(),
	})
}

// imageErrorDetails classifies a failure for the APK without exposing prompts or
// keys.
func imageErrorDetails(err error) (code, message string) {
	var generatorError *imagegen.Error
	if errors.As(err, &generatorError) {
		return generatorError.Code, generatorError.Message
	}
	var commandError *imageCommandError
	if errors.As(err, &commandError) {
		return commandError.code, commandError.message
	}
	return "IMAGE_ERROR", "生图失败，请稍后重试"
}

// shortID truncates ids in logs to their first 8 bytes.
func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

// imageRateLimiter is the in-memory per-device ledger: one in-flight request,
// a 4-requests-per-minute sliding window and 200 requests per local day.
type imageRateLimiter struct {
	mu        sync.Mutex
	perMinute int
	perDay    int
	now       func() time.Time
	devices   map[string]*imageDeviceUsage
}

// imageDeviceUsage is the accounting for one device.
type imageDeviceUsage struct {
	inFlight int
	recent   []time.Time
	day      string
	dayCount int
}

func newImageRateLimiter(now func() time.Time) *imageRateLimiter {
	if now == nil {
		now = time.Now
	}
	return &imageRateLimiter{
		perMinute: imagePerMinutePerDevice,
		perDay:    imagePerDayPerDevice,
		now:       now,
		devices:   map[string]*imageDeviceUsage{},
	}
}

// acquire reserves one slot; it returns a user-facing error when the device is
// busy, over the sliding window or over the daily quota.
func (l *imageRateLimiter) acquire(deviceID string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	usage := l.devices[deviceID]
	if usage == nil {
		usage = &imageDeviceUsage{}
		l.devices[deviceID] = usage
	}
	now := l.now()
	cutoff := now.Add(-time.Minute)
	recent := usage.recent[:0]
	for _, at := range usage.recent {
		if at.After(cutoff) {
			recent = append(recent, at)
		}
	}
	usage.recent = recent
	if day := now.Format("2006-01-02"); usage.day != day {
		usage.day = day
		usage.dayCount = 0
	}
	switch {
	case usage.inFlight >= imageConcurrentPerDevice:
		return &imageCommandError{code: codeImageBusy, message: "已有生图任务正在进行，请等待完成后再试"}
	case len(usage.recent) >= l.perMinute:
		return &imageCommandError{
			code:    codeImageRateLimited,
			message: fmt.Sprintf("生图请求太频繁（每分钟最多 %d 次），请稍后再试", l.perMinute),
		}
	case usage.dayCount >= l.perDay:
		return &imageCommandError{
			code:    codeImageDailyLimit,
			message: fmt.Sprintf("今日生图次数已达上限（%d 次），请明天再试", l.perDay),
		}
	}
	usage.inFlight++
	usage.recent = append(usage.recent, now)
	usage.dayCount++
	return nil
}

func (l *imageRateLimiter) release(deviceID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if usage := l.devices[deviceID]; usage != nil && usage.inFlight > 0 {
		usage.inFlight--
	}
}
