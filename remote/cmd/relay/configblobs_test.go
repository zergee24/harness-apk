package main

import (
	"bytes"
	"net/http"
	"encoding/base64"
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"
)

// handler 直调时 r.PathValue 为空（路由信息在 ServeMux 上），必须经 mux 分发。
func configBlobMux(s *server) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/config-blobs", s.createConfigBlob)
	mux.HandleFunc("GET /v1/config-blobs/{code}", s.getConfigBlob)
	mux.HandleFunc("DELETE /v1/config-blobs/{code}", s.revokeConfigBlob)
	return mux
}

func TestConfigBlobRoundTripClaimsOnceAndExpires(t *testing.T) {
	s := &server{blobs: newConfigBlobStore()}
	now := time.Now()
	s.blobs.now = func() time.Time { return now }

	envelope := bytes.Repeat([]byte{0x5A}, 256)
	body, _ := json.Marshal(map[string]any{
		"code":        "ABCD2345",
		"envelopeB64": base64.StdEncoding.EncodeToString(envelope),
		"ttlMinutes":  60,
	})
	rec := httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(body)))
	if rec.Code != 200 {
		t.Fatalf("create failed: %d %s", rec.Code, rec.Body.String())
	}
	var created struct{ Code, ExpiresAt, AdminToken string }
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	if len(created.Code) != 9 || created.Code[4] != '-' {
		t.Fatalf("code not formatted as XXXX-XXXX: %q", created.Code)
	}

	rec = httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("GET", "/v1/config-blobs/"+created.Code, nil))
	if rec.Code != 200 {
		t.Fatalf("claim failed: %d", rec.Code)
	}
	var claimed struct{ EnvelopeB64 string }
	if err := json.Unmarshal(rec.Body.Bytes(), &claimed); err != nil {
		t.Fatal(err)
	}
	decoded, err := base64.StdEncoding.DecodeString(claimed.EnvelopeB64)
	if err != nil || !bytes.Equal(decoded, envelope) {
		t.Fatalf("claimed envelope mismatch: err=%v len=%d", err, len(decoded))
	}

	// 领取即焚：第二次必须 404
	rec = httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("GET", "/v1/config-blobs/"+created.Code, nil))
	if rec.Code != 404 {
		t.Fatalf("blob must be one-time, got %d", rec.Code)
	}

	// TTL 过期：新 blob 推进时钟后必须 404
	body, _ = json.Marshal(map[string]any{
		"code":        "XYZA6789",
		"envelopeB64": base64.StdEncoding.EncodeToString(envelope),
		"ttlMinutes":  10,
	})
	rec = httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(body)))
	if rec.Code != 200 {
		t.Fatalf("second create failed: %d", rec.Code)
	}
	now = now.Add(11 * time.Minute)
	rec = httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("GET", "/v1/config-blobs/"+created.Code, nil))
	if rec.Code != 404 {
		t.Fatalf("expired blob must 404, got %d", rec.Code)
	}
	_ = created.ExpiresAt
}

func TestConfigBlobRevokeRequiresAdminToken(t *testing.T) {
	s := &server{blobs: newConfigBlobStore()}
	s.blobs.now = func() time.Time { return time.Now() }
	body, _ := json.Marshal(map[string]any{
		"code":        "ABCD2345",
		"envelopeB64": base64.StdEncoding.EncodeToString([]byte("secret")),
		"ttlMinutes":  60,
	})
	rec := httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(body)))
	var created struct{ Code, AdminToken string }
	_ = json.Unmarshal(rec.Body.Bytes(), &created)

	rec = httptest.NewRecorder()
	revokeBody, _ := json.Marshal(map[string]any{"adminToken": "wrong"})
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("DELETE", "/v1/config-blobs/"+created.Code, bytes.NewReader(revokeBody)))
	if rec.Code != 404 {
		t.Fatalf("wrong admin token must not revoke, got %d", rec.Code)
	}

	revokeBody, _ = json.Marshal(map[string]any{"adminToken": created.AdminToken})
	rec = httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("DELETE", "/v1/config-blobs/"+created.Code, bytes.NewReader(revokeBody)))
	if rec.Code != 204 {
		t.Fatalf("revoke failed: %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("GET", "/v1/config-blobs/"+created.Code, nil))
	if rec.Code != 404 {
		t.Fatalf("revoked blob must 404, got %d", rec.Code)
	}
}

func TestConfigBlobRejectsBadCodeAndDuplicate(t *testing.T) {
	s := &server{blobs: newConfigBlobStore()}
	mux := configBlobMux(s)

	// 小写输入被 normalize 归一为大写（父母不知道哪个字母大写，这是特性）
	lower, _ := json.Marshal(map[string]any{
		"code":        "wxyz6789",
		"envelopeB64": base64.StdEncoding.EncodeToString([]byte("lower")),
		"ttlMinutes":  60,
	})
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(lower)))
	if rec.Code != 200 {
		t.Fatalf("lowercase input must normalize to 200, got %d", rec.Code)
	}

	good, _ := json.Marshal(map[string]any{
		"code":        "ABCD2345",
		"envelopeB64": base64.StdEncoding.EncodeToString([]byte("x")),
		"ttlMinutes":  60,
	})
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(good)))
	if rec.Code != 200 {
		t.Fatalf("first upload failed: %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(good)))
	if rec.Code != 409 {
		t.Fatalf("duplicate code must 409, got %d", rec.Code)
	}
}

func TestConfigBlobRejectsUnsupportedTTLAndOversizedEnvelope(t *testing.T) {
	s := &server{blobs: newConfigBlobStore()}
	big := bytes.Repeat([]byte{1}, configBlobMaxBytes+1)
	body, _ := json.Marshal(map[string]any{
		"envelopeB64": base64.StdEncoding.EncodeToString(big),
		"ttlMinutes":  60,
	})
	rec := httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(body)))
	if rec.Code != 400 {
		t.Fatalf("oversized envelope must 400, got %d", rec.Code)
	}

	body, _ = json.Marshal(map[string]any{
		"envelopeB64": base64.StdEncoding.EncodeToString([]byte("x")),
		"ttlMinutes":  7,
	})
	rec = httptest.NewRecorder()
	configBlobMux(s).ServeHTTP(rec, httptest.NewRequest("POST", "/v1/config-blobs", bytes.NewReader(body)))
	if rec.Code != 400 {
		t.Fatalf("unsupported ttl must 400, got %d", rec.Code)
	}
}
