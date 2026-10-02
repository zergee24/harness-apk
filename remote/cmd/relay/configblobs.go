package main

import (
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

// 配置包中转：一次性加密保管箱。中继只见导出方已加密的 envelope 字节，
// 领取码既是下载地址也是解密口令（KDF 在两端），因此中继保持端到端盲。
// 三道时效闸门：领取即焚（GET 成功即删）、TTL 到期清除、导出方可凭
// adminToken 主动作废。

const (
	configBlobMaxBytes  = 64 << 10
	// 纯大写+数字、去易混（I/O/0/1）：领取码大小写不敏感，normalize 会统一转大写。
	configCodeAlphabet  = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	configCodeLength    = 8
	configBlobSweepEvery = 5 * time.Minute
)

var configCodePattern = regexp.MustCompile(`^[A-HJ-NP-Z2-9]{8}$`)

var configBlobTTLs = map[int]time.Duration{
	10:   10 * time.Minute,
	60:   time.Hour,
	1440: 24 * time.Hour,
}

type configBlob struct {
	cipher     []byte
	expiresAt  time.Time
	adminToken string
}

type configBlobStore struct {
	mu    sync.Mutex
	now   func() time.Time
	blobs map[string]configBlob
}

func newConfigBlobStore() *configBlobStore {
	return &configBlobStore{now: time.Now, blobs: map[string]configBlob{}}
}

func (s *configBlobStore) sweepLocked() {
	now := s.now()
	for code, blob := range s.blobs {
		if now.After(blob.expiresAt) {
			delete(s.blobs, code)
		}
	}
}

func (s *configBlobStore) put(code string, cipher []byte, ttl time.Duration) (expiresAt time.Time, adminToken string, err error) {
	admin := make([]byte, 24)
	if _, err = rand.Read(admin); err != nil {
		return
	}
	adminToken = base64.RawURLEncoding.EncodeToString(admin)

	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked()
	s.blobs[code] = configBlob{
		cipher:     cipher,
		expiresAt:  s.now().Add(ttl),
		adminToken: adminToken,
	}
	return s.blobs[code].expiresAt, adminToken, nil
}

func (s *configBlobStore) claim(code string) ([]byte, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked()
	blob, ok := s.blobs[code]
	if !ok || s.now().After(blob.expiresAt) {
		return nil, false
	}
	delete(s.blobs, code)
	return blob.cipher, true
}

func (s *configBlobStore) revoke(code, adminToken string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked()
	blob, ok := s.blobs[code]
	if !ok || blob.adminToken != adminToken {
		return false
	}
	delete(s.blobs, code)
	return true
}

func normalizeConfigCode(raw string) string {
	return strings.ToUpper(strings.ReplaceAll(strings.ReplaceAll(strings.TrimSpace(raw), "-", ""), " ", ""))
}

func formatConfigCode(code string) string {
	if len(code) != configCodeLength {
		return code
	}
	return code[:4] + "-" + code[4:]
}

// 码由上传方生成（34 表大写、8 位、含字母数字——领取码即解密口令，熵必须来自
// 上传端），中继只查重。冲突 409 让上传端换码重试。
func (s *server) createConfigBlob(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Code        string `json:"code"`
		EnvelopeB64 string `json:"envelopeB64"`
		TTLMinutes  int    `json:"ttlMinutes"`
	}
	if !decode(w, r, &request) {
		return
	}
	code := normalizeConfigCode(request.Code)
	if !configCodePattern.MatchString(code) {
		http.Error(w, "invalid code", http.StatusBadRequest)
		return
	}
	ttl, ok := configBlobTTLs[request.TTLMinutes]
	if !ok {
		http.Error(w, "unsupported ttlMinutes", http.StatusBadRequest)
		return
	}
	envelope, err := base64.StdEncoding.DecodeString(request.EnvelopeB64)
	if err != nil || len(envelope) == 0 || len(envelope) > configBlobMaxBytes {
		http.Error(w, "invalid envelope", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	_, exists := s.blobs.blobs[code]
	s.mu.Unlock()
	if exists {
		http.Error(w, "code already in use", http.StatusConflict)
		return
	}
	expiresAt, adminToken, err := s.blobs.put(code, envelope, ttl)
	if err != nil {
		http.Error(w, "unavailable", http.StatusInternalServerError)
		return
	}
	respond(w, http.StatusOK, map[string]any{
		"code":       formatConfigCode(code),
		"expiresAt":  expiresAt.UTC().Format(time.RFC3339),
		"adminToken": adminToken,
	})
}

func (s *server) getConfigBlob(w http.ResponseWriter, r *http.Request) {
	code := normalizeConfigCode(r.PathValue("code"))
	if len(code) != configCodeLength {
		http.Error(w, "invalid code", http.StatusBadRequest)
		return
	}
	envelope, ok := s.blobs.claim(code)
	if !ok {
		http.Error(w, "not found or expired", http.StatusNotFound)
		return
	}
	respond(w, http.StatusOK, map[string]string{"envelopeB64": base64.StdEncoding.EncodeToString(envelope)})
}

func (s *server) revokeConfigBlob(w http.ResponseWriter, r *http.Request) {
	code := normalizeConfigCode(r.PathValue("code"))
	var request struct {
		AdminToken string `json:"adminToken"`
	}
	if !decode(w, r, &request) {
		return
	}
	if !s.blobs.revoke(code, request.AdminToken) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) sweepConfigBlobs() {
	ticker := time.NewTicker(configBlobSweepEvery)
	go func() {
		for range ticker.C {
			s.blobs.mu.Lock()
			s.blobs.sweepLocked()
			s.blobs.mu.Unlock()
		}
	}()
}
