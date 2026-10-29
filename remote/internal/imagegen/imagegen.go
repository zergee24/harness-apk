// Package imagegen backs the bridge's host-level image.generate command: it
// validates the request, resolves the OpenAI API key, calls the Images API,
// returns the image (PNG, or JPEG when the payload would exceed the relay frame
// budget) and archives the original PNG on the Mac.
//
// The protocol is defined in remote/docs/image-generate.md; this package only
// implements the Mac side of it. It never logs or sends prompts or API keys
// beyond the fields the protocol explicitly asks for.
package imagegen

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	// DefaultModel is used when the APK omits model.
	DefaultModel = "gpt-image-2.5-flare"
	// DefaultSize is used when the APK omits size.
	DefaultSize = "1024x1024"
	// DefaultQuality is used when the APK omits quality.
	DefaultQuality = "medium"
	// DefaultBaseURL is used when OPENAI_BASE_URL is not set.
	DefaultBaseURL = "https://api.openai.com/v1"
	// DefaultTimeout bounds one upstream image generation request.
	DefaultTimeout = 180 * time.Second
	// MaxPromptChars is the protocol limit on prompt length, counted in runes.
	MaxPromptChars = 4000

	// MaxInlinePNGBytes is the largest original PNG the bridge forwards as-is.
	// Above it the image is re-encoded as JPEG to stay inside the relay frame
	// budget (see remote/docs/image-generate.md §4).
	MaxInlinePNGBytes = 400 << 10
	// MaxOriginalBytes is the largest upstream image the bridge accepts. Bigger
	// payloads are rejected instead of risking an oversized wire frame.
	MaxOriginalBytes = 8 << 20

	jpegQuality      = 85
	maxLongEdge      = 2048
	maxResponseBytes = 16 << 20
)

// AllowedSizes are the sizes the protocol accepts.
var AllowedSizes = []string{"1024x1024", "1024x1536", "1536x1024"}

// AllowedQualities are the qualities the protocol accepts.
var AllowedQualities = []string{"low", "medium", "high"}

// AllowedModels are the models the protocol accepts.
var AllowedModels = []string{"gpt-image-2.5-flare", "gpt-image-2.5-sunburst", "gpt-image-2"}

// Error codes returned to the APK in the image.error payload.
const (
	CodeInvalidParams    = "INVALID_PARAMS"
	CodeMissingAPIKey    = "MISSING_API_KEY"
	CodeImageTooLarge    = "IMAGE_TOO_LARGE"
	CodeDecodeFailed     = "IMAGE_DECODE_FAILED"
	CodeEncodeFailed     = "IMAGE_ENCODE_FAILED"
	CodeUpstreamResponse = "UPSTREAM_INVALID_RESPONSE"
	CodeNetwork          = "OPENAI_NETWORK"
	CodeTimeout          = "IMAGE_TIMEOUT"
	CodeInternal         = "IMAGE_INTERNAL_ERROR"
)

// sizeDimensions maps the accepted size strings to pixel dimensions.
var sizeDimensions = map[string][2]int{
	"1024x1024": {1024, 1024},
	"1024x1536": {1024, 1536},
	"1536x1024": {1536, 1024},
}

// qualityPriceUSD maps quality to the published 1024x1024 price; other sizes are
// scaled by area. The estimate is for display only.
var qualityPriceUSD = map[string]float64{
	"low":    0.006,
	"medium": 0.053,
	"high":   0.211,
}

// Error is a generation failure that can travel back to the APK as image.error:
// Code goes into payload.code and Message is a human-readable Chinese error.
// Prompts and API keys must never appear in either field.
type Error struct {
	Code       string
	Message    string
	HTTPStatus int
	Err        error
}

func (e *Error) Error() string {
	if e.Err == nil {
		return fmt.Sprintf("%s: %s", e.Code, e.Message)
	}
	return fmt.Sprintf("%s: %s: %v", e.Code, e.Message, e.Err)
}

// Unwrap exposes the underlying cause for errors.Is/errors.As.
func (e *Error) Unwrap() error { return e.Err }

// Request is one image.generate params object.
type Request struct {
	Prompt  string `json:"prompt"`
	Size    string `json:"size,omitempty"`
	Quality string `json:"quality,omitempty"`
	Model   string `json:"model,omitempty"`
}

// Normalize applies the protocol defaults and trims enum fields.
func (r Request) Normalize() Request {
	r.Model = strings.TrimSpace(r.Model)
	if r.Model == "" {
		r.Model = DefaultModel
	}
	r.Size = strings.TrimSpace(r.Size)
	if r.Size == "" {
		r.Size = DefaultSize
	}
	r.Quality = strings.TrimSpace(r.Quality)
	if r.Quality == "" {
		r.Quality = DefaultQuality
	}
	return r
}

// Validate checks the protocol bounds before any quota, key or network work.
func (r Request) Validate() error {
	if strings.TrimSpace(r.Prompt) == "" {
		return &Error{Code: CodeInvalidParams, Message: "prompt 不能为空"}
	}
	if utf8.RuneCountInString(r.Prompt) > MaxPromptChars {
		return &Error{Code: CodeInvalidParams, Message: fmt.Sprintf("prompt 过长（最多 %d 字符）", MaxPromptChars)}
	}
	if !containsString(AllowedSizes, r.Size) {
		return &Error{
			Code:    CodeInvalidParams,
			Message: fmt.Sprintf("不支持的尺寸 %q（仅支持 %s）", r.Size, strings.Join(AllowedSizes, "、")),
		}
	}
	if !containsString(AllowedQualities, r.Quality) {
		return &Error{
			Code:    CodeInvalidParams,
			Message: fmt.Sprintf("不支持的质量 %q（仅支持 %s）", r.Quality, strings.Join(AllowedQualities, "、")),
		}
	}
	if !containsString(AllowedModels, r.Model) {
		return &Error{
			Code:    CodeInvalidParams,
			Message: fmt.Sprintf("不支持的模型 %q（仅支持 %s）", r.Model, strings.Join(AllowedModels, "、")),
		}
	}
	return nil
}

// Result is one successful generation. Data is what the bridge sends to the APK
// (PNG, or JPEG when the original exceeded MaxInlinePNGBytes) and SavedPath
// points at the archived original PNG on the Mac (empty when archiving failed).
type Result struct {
	ID              string
	Model           string
	Size            string
	Quality         string
	MimeType        string
	Data            []byte
	Width           int
	Height          int
	DurationMs      int64
	CostEstimateUsd float64
	SavedPath       string
}

// Config configures a Service. Zero values fall back to the environment:
// OPENAI_BASE_URL for the endpoint, HARNESS_IMAGE_PROXY (then
// HTTPS_PROXY/HTTP_PROXY via http.ProxyFromEnvironment) for the proxy, $HOME for
// archiving, and a 180s timeout. OPENAI_API_KEY is deliberately not read here:
// the key is resolved per request.
type Config struct {
	APIKey     string        // optional test override; otherwise resolved per request
	BaseURL    string        // optional; OPENAI_BASE_URL otherwise
	ProxyURL   string        // optional; HARNESS_IMAGE_PROXY otherwise
	Timeout    time.Duration // optional; DefaultTimeout otherwise
	HomeDir    string        // optional; the current user's home otherwise
	HTTPClient *http.Client  // optional; a proxy-aware client is built otherwise
}

// Service calls the OpenAI Images API.
type Service struct {
	apiKey  string
	baseURL string
	timeout time.Duration
	homeDir string
	client  *http.Client
}

// New builds a Service. Only the base URL, proxy and timeout are read at
// construction time, so a proxy change needs a bridge restart while a rotated
// API key does not.
func New(config Config) *Service {
	baseURL := strings.TrimSpace(config.BaseURL)
	if baseURL == "" {
		baseURL = strings.TrimSpace(os.Getenv("OPENAI_BASE_URL"))
	}
	if baseURL == "" {
		baseURL = DefaultBaseURL
	}
	timeout := config.Timeout
	if timeout <= 0 {
		timeout = DefaultTimeout
	}
	client := config.HTTPClient
	if client == nil {
		client = newHTTPClient(config.ProxyURL, timeout)
	}
	return &Service{
		apiKey:  strings.TrimSpace(config.APIKey),
		baseURL: strings.TrimRight(baseURL, "/"),
		timeout: timeout,
		homeDir: config.HomeDir,
		client:  client,
	}
}

// Generate runs one image generation: validate, resolve the key, call the
// upstream API, then decode/convert/archive the returned PNG.
func (s *Service) Generate(ctx context.Context, request Request) (Result, error) {
	started := time.Now()
	request = request.Normalize()
	if err := request.Validate(); err != nil {
		return Result{}, err
	}
	apiKey, err := s.apiKeyForRequest()
	if err != nil {
		return Result{}, err
	}
	requestCtx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	raw, err := s.requestImage(requestCtx, apiKey, request)
	if err != nil {
		return Result{}, err
	}
	result, err := s.buildResult(raw, request)
	if err != nil {
		return Result{}, err
	}
	result.DurationMs = time.Since(started).Milliseconds()
	return result, nil
}

// apiKeyForRequest prefers the configured override, then the per-request
// resolver (env, then ~/.dsh/.credentials.yaml).
func (s *Service) apiKeyForRequest() (string, error) {
	if s.apiKey != "" {
		return s.apiKey, nil
	}
	return ResolveAPIKey(s.homeDir)
}

// generationRequest is the JSON body of POST /images/generations.
type generationRequest struct {
	Model   string `json:"model"`
	Prompt  string `json:"prompt"`
	Size    string `json:"size"`
	Quality string `json:"quality"`
}

// generationResponse is the subset of the Images API response the bridge needs.
type generationResponse struct {
	Data []struct {
		B64JSON string `json:"b64_json"`
		URL     string `json:"url"`
	} `json:"data"`
}

// requestImage posts the generation request and returns the decoded PNG bytes.
func (s *Service) requestImage(ctx context.Context, apiKey string, request Request) ([]byte, error) {
	body, err := json.Marshal(generationRequest{
		Model: request.Model, Prompt: request.Prompt, Size: request.Size, Quality: request.Quality,
	})
	if err != nil {
		return nil, &Error{Code: CodeInternal, Message: "构造生图请求失败", Err: err}
	}
	endpoint := s.baseURL + "/images/generations"
	httpRequest, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, &Error{Code: CodeInternal, Message: "构造生图请求失败", Err: err}
	}
	httpRequest.Header.Set("Content-Type", "application/json")
	httpRequest.Header.Set("Authorization", "Bearer "+apiKey)
	response, err := s.client.Do(httpRequest)
	if err != nil {
		return nil, upstreamRequestError(ctx, err)
	}
	defer response.Body.Close()
	rawBody, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return nil, &Error{Code: CodeNetwork, Message: "读取 OpenAI 响应失败", Err: err}
	}
	if len(rawBody) > maxResponseBytes {
		return nil, &Error{Code: CodeImageTooLarge, Message: "OpenAI 返回的图片数据过大"}
	}
	if response.StatusCode != http.StatusOK {
		return nil, upstreamStatusError(response.StatusCode, rawBody)
	}
	var decoded generationResponse
	if err := json.Unmarshal(rawBody, &decoded); err != nil {
		return nil, &Error{Code: CodeUpstreamResponse, Message: "OpenAI 返回了无法解析的结果", Err: err}
	}
	if len(decoded.Data) == 0 {
		return nil, &Error{Code: CodeUpstreamResponse, Message: "OpenAI 没有返回图片数据"}
	}
	encoded := strings.TrimSpace(decoded.Data[0].B64JSON)
	if encoded == "" {
		if decoded.Data[0].URL != "" {
			return nil, &Error{Code: CodeUpstreamResponse, Message: "OpenAI 只返回了图片 URL（预期 b64_json）"}
		}
		return nil, &Error{Code: CodeUpstreamResponse, Message: "OpenAI 没有返回图片数据"}
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		raw, err = base64.RawStdEncoding.DecodeString(encoded)
	}
	if err != nil || len(raw) == 0 {
		return nil, &Error{Code: CodeUpstreamResponse, Message: "OpenAI 返回的图片数据无法解码", Err: err}
	}
	return raw, nil
}

// upstreamRequestError converts transport failures into protocol errors without
// leaking the API key or prompt.
func upstreamRequestError(ctx context.Context, err error) error {
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return &Error{Code: CodeTimeout, Message: "生图超时，请稍后重试", Err: err}
	}
	if errors.Is(err, context.Canceled) {
		return &Error{Code: CodeNetwork, Message: "生图请求已取消", Err: err}
	}
	return &Error{Code: CodeNetwork, Message: "无法连接 OpenAI 图片服务（请检查网络或代理设置）", Err: err}
}

// upstreamStatusError maps an HTTP error to a protocol error. The upstream body
// is kept only on Err for debugging; it is never forwarded to the APK because it
// may echo request content.
func upstreamStatusError(status int, body []byte) error {
	message := fmt.Sprintf("OpenAI 请求失败（HTTP %d），请稍后重试", status)
	switch status {
	case http.StatusUnauthorized:
		message = "OpenAI 鉴权失败（HTTP 401），请检查 OPENAI_API_KEY"
	case http.StatusTooManyRequests:
		message = "OpenAI 限流或额度不足（HTTP 429），请稍后再试"
	case http.StatusBadRequest:
		message = "OpenAI 拒绝了请求（HTTP 400），请检查 prompt、尺寸与质量"
	}
	detail := strings.TrimSpace(string(body))
	if len(detail) > 200 {
		detail = detail[:200]
	}
	var cause error
	if detail != "" {
		cause = errors.New(detail)
	}
	return &Error{
		Code:       fmt.Sprintf("OPENAI_HTTP_%d", status),
		Message:    message,
		HTTPStatus: status,
		Err:        cause,
	}
}

// buildResult decodes the upstream PNG, converts it to JPEG when needed and
// archives the untouched original. Nothing is archived when the image is
// rejected, matching "失败不落盘".
func (s *Service) buildResult(raw []byte, request Request) (Result, error) {
	if len(raw) > MaxOriginalBytes {
		return Result{}, &Error{
			Code:    CodeImageTooLarge,
			Message: fmt.Sprintf("图片体积过大（%.1f MB），超过 %d MB 上限", float64(len(raw))/(1<<20), MaxOriginalBytes>>20),
		}
	}
	image, err := decodePNG(raw)
	if err != nil {
		return Result{}, err
	}
	id, err := newID()
	if err != nil {
		return Result{}, &Error{Code: CodeInternal, Message: "生成图片 id 失败", Err: err}
	}
	bounds := image.Bounds()
	result := Result{
		ID: id, Model: request.Model, Size: request.Size, Quality: request.Quality,
		Width: bounds.Dx(), Height: bounds.Dy(),
		CostEstimateUsd: EstimateCost(request.Quality, request.Size),
	}
	if len(raw) <= MaxInlinePNGBytes {
		result.MimeType = "image/png"
		result.Data = raw
	} else {
		data, width, height, err := encodeJPEG(image)
		if err != nil {
			return Result{}, err
		}
		result.MimeType = "image/jpeg"
		result.Data = data
		result.Width = width
		result.Height = height
	}
	// Archive failures are non-fatal: the phone still gets the image, it just
	// misses the debug-only savedPath.
	if path, err := s.archiveOriginal(id, raw); err == nil {
		result.SavedPath = path
	}
	return result, nil
}

// archiveOriginal writes the untouched upstream PNG to
// ~/.harness-remote/imagegen/<id>.png (directory 0700).
func (s *Service) archiveOriginal(id string, raw []byte) (string, error) {
	home, err := s.userHome()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(home, ".harness-remote", "imagegen")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	path := filepath.Join(dir, id+".png")
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

func (s *Service) userHome() (string, error) {
	if s.homeDir != "" {
		return s.homeDir, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", &Error{Code: CodeInternal, Message: "无法定位用户主目录", Err: err}
	}
	return home, nil
}

// EstimateCost returns the rough USD price shown to the user: the published
// 1024x1024 price for the quality, scaled by image area.
func EstimateCost(quality, size string) float64 {
	price, ok := qualityPriceUSD[quality]
	if !ok {
		return 0
	}
	dimensions, ok := sizeDimensions[size]
	if !ok {
		return 0
	}
	areaRatio := float64(dimensions[0]*dimensions[1]) / (1024.0 * 1024.0)
	return math.Round(price*areaRatio*10000) / 10000
}

// newHTTPClient builds a proxy-aware client. HARNESS_IMAGE_PROXY explicitly
// overrides the standard HTTPS_PROXY/HTTP_PROXY lookup.
func newHTTPClient(proxyOverride string, timeout time.Duration) *http.Client {
	proxyURL := strings.TrimSpace(proxyOverride)
	if proxyURL == "" {
		proxyURL = strings.TrimSpace(os.Getenv("HARNESS_IMAGE_PROXY"))
	}
	proxy := http.ProxyFromEnvironment
	if proxyURL != "" {
		if parsed, err := url.Parse(proxyURL); err == nil && parsed.Host != "" {
			proxy = http.ProxyURL(parsed)
		}
	}
	transport := &http.Transport{}
	if base, ok := http.DefaultTransport.(*http.Transport); ok {
		transport = base.Clone()
	}
	transport.Proxy = proxy
	return &http.Client{Transport: transport, Timeout: timeout}
}

// newID returns a 128-bit random id for one archived image.
func newID() (string, error) {
	raw := make([]byte, 18)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func containsString(values []string, value string) bool {
	for _, candidate := range values {
		if candidate == value {
			return true
		}
	}
	return false
}
