package imagegen

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// TestMain keeps the tests hermetic: a developer's proxy configuration must not
// intercept the fake OpenAI servers. Tests that exercise proxying set
// HARNESS_IMAGE_PROXY themselves.
func TestMain(m *testing.M) {
	_ = os.Unsetenv("HARNESS_IMAGE_PROXY")
	_ = os.Setenv("NO_PROXY", "127.0.0.1,localhost,::1")
	_ = os.Setenv("no_proxy", "127.0.0.1,localhost,::1")
	os.Exit(m.Run())
}

// testPNG encodes a PNG of the requested size. randomPixels fills it with
// incompressible data so the encoded payload grows past MaxInlinePNGBytes.
func testPNG(t *testing.T, width, height int, randomPixels bool) []byte {
	t.Helper()
	imageBytes := image.NewRGBA(image.Rect(0, 0, width, height))
	if randomPixels {
		state := uint32(1)
		next := func() byte {
			state = state*1664525 + 1013904223
			return byte(state >> 24)
		}
		for offset := 0; offset < len(imageBytes.Pix); offset += 4 {
			imageBytes.Pix[offset] = next()
			imageBytes.Pix[offset+1] = next()
			imageBytes.Pix[offset+2] = next()
			imageBytes.Pix[offset+3] = 255
		}
	} else {
		imageBytes.Set(0, 0, color.RGBA{R: 200, G: 30, B: 30, A: 255})
	}
	var buffer bytes.Buffer
	if err := png.Encode(&buffer, imageBytes); err != nil {
		t.Fatalf("encode test png: %v", err)
	}
	return buffer.Bytes()
}

// jsonImageResponse builds the Images API response carrying one b64 PNG.
func jsonImageResponse(t *testing.T, imageBytes []byte) []byte {
	t.Helper()
	payload, err := json.Marshal(map[string]any{
		"data": []map[string]string{{"b64_json": base64.StdEncoding.EncodeToString(imageBytes)}},
	})
	if err != nil {
		t.Fatalf("encode fake response: %v", err)
	}
	return payload
}

// writeCredentials writes a DSH-shaped credentials file into home.
func writeCredentials(t *testing.T, home, key string) {
	t.Helper()
	dir := filepath.Join(home, ".dsh")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	content := "version: 1\nrefs:\n  OPENAI_API_KEY: " + key + "\n"
	if err := os.WriteFile(filepath.Join(dir, ".credentials.yaml"), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestGenerateSuccessReturnsPNGAndArchivesOriginal(t *testing.T) {
	imageBytes := testPNG(t, 1, 1, false)
	var requestPath, authorization, requestBody string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestPath = r.URL.Path
		authorization = r.Header.Get("Authorization")
		body, _ := io.ReadAll(r.Body)
		requestBody = string(body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jsonImageResponse(t, imageBytes))
	}))
	defer server.Close()
	home := t.TempDir()
	service := New(Config{APIKey: "sk-test", BaseURL: server.URL + "/v1/", HomeDir: home, Timeout: 5 * time.Second})

	result, err := service.Generate(context.Background(), Request{
		Prompt: "a flat red fox icon", Size: "1024x1536", Quality: "low",
	})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if requestPath != "/v1/images/generations" {
		t.Fatalf("path = %q", requestPath)
	}
	if authorization != "Bearer sk-test" {
		t.Fatalf("authorization = %q", authorization)
	}
	var sent generationRequest
	if err := json.Unmarshal([]byte(requestBody), &sent); err != nil {
		t.Fatalf("decode request body: %v", err)
	}
	if sent.Model != DefaultModel || sent.Size != "1024x1536" || sent.Quality != "low" || sent.Prompt != "a flat red fox icon" {
		t.Fatalf("request body = %#v", sent)
	}
	if result.MimeType != "image/png" || !bytes.Equal(result.Data, imageBytes) {
		t.Fatalf("result mime=%q bytes=%d", result.MimeType, len(result.Data))
	}
	if result.Width != 1 || result.Height != 1 {
		t.Fatalf("dimensions = %dx%d", result.Width, result.Height)
	}
	// low @ 1024x1536 = 0.006 * 1.5
	if result.CostEstimateUsd != 0.009 {
		t.Fatalf("cost = %v", result.CostEstimateUsd)
	}
	if result.ID == "" || result.DurationMs < 0 {
		t.Fatalf("id=%q durationMs=%d", result.ID, result.DurationMs)
	}
	wantPath := filepath.Join(home, ".harness-remote", "imagegen", result.ID+".png")
	if result.SavedPath != wantPath {
		t.Fatalf("savedPath = %q, want %q", result.SavedPath, wantPath)
	}
	saved, err := os.ReadFile(wantPath)
	if err != nil {
		t.Fatalf("read archived image: %v", err)
	}
	if !bytes.Equal(saved, imageBytes) {
		t.Fatal("archived image differs from the upstream PNG")
	}
	if info, err := os.Stat(filepath.Dir(wantPath)); err != nil {
		t.Fatal(err)
	} else if info.Mode().Perm() != 0o700 {
		t.Fatalf("archive dir mode = %v", info.Mode().Perm())
	}
}

func TestGenerateResolvesKeyFromCredentialsWhenEnvMissing(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "")
	imageBytes := testPNG(t, 1, 1, false)
	var authorization string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authorization = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jsonImageResponse(t, imageBytes))
	}))
	defer server.Close()
	home := t.TempDir()
	writeCredentials(t, home, "sk-from-credentials")
	service := New(Config{BaseURL: server.URL, HomeDir: home, Timeout: 5 * time.Second})

	if _, err := service.Generate(context.Background(), Request{Prompt: "a fox"}); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if authorization != "Bearer sk-from-credentials" {
		t.Fatalf("authorization = %q", authorization)
	}
}

func TestGenerateConvertsOversizedPNGToJPEG(t *testing.T) {
	largePNG := testPNG(t, 1024, 1024, true)
	if len(largePNG) <= MaxInlinePNGBytes {
		t.Fatalf("test PNG is only %d bytes; expected more than %d", len(largePNG), MaxInlinePNGBytes)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jsonImageResponse(t, largePNG))
	}))
	defer server.Close()
	home := t.TempDir()
	service := New(Config{APIKey: "sk-test", BaseURL: server.URL, HomeDir: home, Timeout: 15 * time.Second})

	result, err := service.Generate(context.Background(), Request{Prompt: "noise", Size: "1024x1024", Quality: "medium"})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if result.MimeType != "image/jpeg" {
		t.Fatalf("mimeType = %q", result.MimeType)
	}
	if len(result.Data) < 3 || result.Data[0] != 0xFF || result.Data[1] != 0xD8 || result.Data[2] != 0xFF {
		t.Fatal("result is not a JPEG")
	}
	if len(result.Data) >= len(largePNG) {
		t.Fatalf("converted JPEG (%d) is not smaller than the original PNG (%d)", len(result.Data), len(largePNG))
	}
	decoded, err := jpeg.Decode(bytes.NewReader(result.Data))
	if err != nil {
		t.Fatalf("decode returned JPEG: %v", err)
	}
	if result.Width != 1024 || result.Height != 1024 || decoded.Bounds().Dx() != 1024 || decoded.Bounds().Dy() != 1024 {
		t.Fatalf("dimensions = %dx%d", result.Width, result.Height)
	}
	// The archive keeps the untouched original PNG, not the converted JPEG.
	saved, err := os.ReadFile(result.SavedPath)
	if err != nil {
		t.Fatalf("read archived image: %v", err)
	}
	if !bytes.Equal(saved, largePNG) {
		t.Fatal("archived image is not the original PNG")
	}
	if result.CostEstimateUsd != 0.053 {
		t.Fatalf("cost = %v", result.CostEstimateUsd)
	}
}

func TestGenerateRejectsImageAboveEightMiB(t *testing.T) {
	oversized := bytes.Repeat([]byte{0x89}, MaxOriginalBytes+1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jsonImageResponse(t, oversized))
	}))
	defer server.Close()
	home := t.TempDir()
	service := New(Config{APIKey: "sk-test", BaseURL: server.URL, HomeDir: home, Timeout: 15 * time.Second})

	_, err := service.Generate(context.Background(), Request{Prompt: "too big"})
	var generationError *Error
	if !errors.As(err, &generationError) || generationError.Code != CodeImageTooLarge {
		t.Fatalf("err = %v", err)
	}
	if _, statErr := os.Stat(filepath.Join(home, ".harness-remote", "imagegen")); !os.IsNotExist(statErr) {
		t.Fatalf("rejected image was archived: %v", statErr)
	}
}

func TestGenerateMapsHTTPError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"error":{"message":"Incorrect API key provided"}}`)
	}))
	defer server.Close()
	service := New(Config{APIKey: "sk-test", BaseURL: server.URL, HomeDir: t.TempDir(), Timeout: 5 * time.Second})

	_, err := service.Generate(context.Background(), Request{Prompt: "a fox"})
	var generationError *Error
	if !errors.As(err, &generationError) {
		t.Fatalf("err = %v", err)
	}
	if generationError.Code != "OPENAI_HTTP_401" || generationError.HTTPStatus != http.StatusUnauthorized {
		t.Fatalf("code=%q status=%d", generationError.Code, generationError.HTTPStatus)
	}
	// The APK-facing message must not leak the key or the upstream body.
	if strings.Contains(generationError.Message, "sk-test") || strings.Contains(generationError.Message, "Incorrect API key") {
		t.Fatalf("message leaks detail: %q", generationError.Message)
	}
}

func TestGenerateMissingAPIKeyFailsBeforeNetwork(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "")
	service := New(Config{HomeDir: t.TempDir(), BaseURL: "http://127.0.0.1:1/v1", Timeout: time.Second})

	_, err := service.Generate(context.Background(), Request{Prompt: "a fox"})
	var generationError *Error
	if !errors.As(err, &generationError) || generationError.Code != CodeMissingAPIKey {
		t.Fatalf("err = %v", err)
	}
}

func TestGenerateRejectsInvalidParameters(t *testing.T) {
	longPrompt := strings.Repeat("字", MaxPromptChars+1)
	cases := []struct {
		name    string
		request Request
	}{
		{"blank prompt", Request{Prompt: "   "}},
		{"long prompt", Request{Prompt: longPrompt}},
		{"bad size", Request{Prompt: "a fox", Size: "512x512"}},
		{"bad quality", Request{Prompt: "a fox", Quality: "ultra"}},
		{"bad model", Request{Prompt: "a fox", Model: "dall-e-3"}},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			service := New(Config{APIKey: "sk-test", BaseURL: "http://127.0.0.1:1/v1", HomeDir: t.TempDir()})
			_, err := service.Generate(context.Background(), testCase.request)
			var generationError *Error
			if !errors.As(err, &generationError) || generationError.Code != CodeInvalidParams {
				t.Fatalf("err = %v", err)
			}
			if generationError.Message == "" {
				t.Fatal("error message is empty")
			}
		})
	}
}

func TestGenerateUsesDefaultModelSizeQuality(t *testing.T) {
	imageBytes := testPNG(t, 1, 1, false)
	var sent generationRequest
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &sent)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jsonImageResponse(t, imageBytes))
	}))
	defer server.Close()
	service := New(Config{APIKey: "sk-test", BaseURL: server.URL, HomeDir: t.TempDir(), Timeout: 5 * time.Second})

	result, err := service.Generate(context.Background(), Request{Prompt: "a fox"})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if sent.Model != DefaultModel || sent.Size != DefaultSize || sent.Quality != DefaultQuality {
		t.Fatalf("defaults not sent: %#v", sent)
	}
	if result.Model != DefaultModel || result.Size != DefaultSize || result.Quality != DefaultQuality {
		t.Fatalf("result defaults = %#v", result)
	}
}

func TestHARNESSImageProxyOverridesDefaultTransport(t *testing.T) {
	imageBytes := testPNG(t, 1, 1, false)
	var proxied atomic.Bool
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxied.Store(true)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jsonImageResponse(t, imageBytes))
	}))
	defer proxy.Close()
	t.Setenv("HARNESS_IMAGE_PROXY", proxy.URL)
	// The target host does not resolve: only the explicit proxy can deliver.
	service := New(Config{APIKey: "sk-test", BaseURL: "http://images.invalid/v1", HomeDir: t.TempDir(), Timeout: 5 * time.Second})

	if _, err := service.Generate(context.Background(), Request{Prompt: "a fox"}); err != nil {
		t.Fatalf("Generate through proxy: %v", err)
	}
	if !proxied.Load() {
		t.Fatal("image request did not go through HARNESS_IMAGE_PROXY")
	}
}

func TestEstimateCostScalesByArea(t *testing.T) {
	cases := []struct {
		quality string
		size    string
		want    float64
	}{
		{"low", "1024x1024", 0.006},
		{"medium", "1024x1024", 0.053},
		{"high", "1024x1024", 0.211},
		{"medium", "1024x1536", 0.0795},
		{"high", "1536x1024", 0.3165},
		{"unknown", "1024x1024", 0},
		{"low", "512x512", 0},
	}
	for _, testCase := range cases {
		if got := EstimateCost(testCase.quality, testCase.size); math.Abs(got-testCase.want) > 1e-9 {
			t.Fatalf("EstimateCost(%q, %q) = %v, want %v", testCase.quality, testCase.size, got, testCase.want)
		}
	}
}
