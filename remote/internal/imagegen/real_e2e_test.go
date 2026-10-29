package imagegen

// 真实 OpenAI 调用（默认跳过）。手动跑：
//   REAL_IMAGE_E2E=1 HTTPS_PROXY=http://127.0.0.1:7897 \
//     go test ./internal/imagegen -run TestRealOpenAI -v -timeout 300s
// 成本：1024x1024 low ≈ $0.006。

import (
	"context"
	"os"
	"testing"
	"time"
)

func TestRealOpenAI(t *testing.T) {
	if os.Getenv("REAL_IMAGE_E2E") == "" {
		t.Skip("set REAL_IMAGE_E2E=1 to call the real OpenAI Images API")
	}
	if _, err := ResolveAPIKey(""); err != nil {
		t.Fatalf("no api key: %v", err)
	}
	service := New(Config{Timeout: 240 * time.Second})
	ctx, cancel := context.WithTimeout(context.Background(), 240*time.Second)
	defer cancel()
	started := time.Now()
	result, err := service.Generate(ctx, Request{
		Prompt:  "A single flat-design blue circle on white background, minimal, no text",
		Size:    "1024x1024",
		Quality: "low",
	})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	t.Logf("id=%s mime=%s bytes=%d size=%dx%d cost=%.4f took=%s saved=%q",
		result.ID, result.MimeType, len(result.Data), result.Width, result.Height,
		result.CostEstimateUsd, time.Since(started).Round(100*time.Millisecond), result.SavedPath)
	if len(result.Data) < 1024 {
		t.Fatalf("image too small: %d bytes", len(result.Data))
	}
	if result.Width <= 0 || result.Height <= 0 {
		t.Fatalf("bad dimensions: %dx%d", result.Width, result.Height)
	}
	if result.MimeType != "image/png" && result.MimeType != "image/jpeg" {
		t.Fatalf("unexpected mime: %s", result.MimeType)
	}
	if result.SavedPath != "" {
		if info, statErr := os.Stat(result.SavedPath); statErr != nil || info.Size() == 0 {
			t.Fatalf("archived file missing: %v", statErr)
		}
	}
}
