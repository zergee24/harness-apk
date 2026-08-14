package main

import (
	"context"
	"encoding/json"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/harnessapk/remote/internal/protocol"
	"github.com/harnessapk/remote/internal/push"
	"github.com/harnessapk/remote/internal/state"
)

const maxWireMessageBytes = 8 << 20

type client struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (c *client) write(ctx context.Context, value any) error {
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.Write(ctx, websocket.MessageText, raw)
}

type server struct {
	store     *state.Store
	bootstrap string
	notifier  push.Notifier
	mu        sync.RWMutex
	hosts     map[string]*client
	devices   map[string]*client
}

func main() {
	listen := flag.String("listen", envOr("HARNESS_RELAY_LISTEN", ":8080"), "HTTP listen address")
	statePath := flag.String("state", envOr("HARNESS_RELAY_STATE", "./data/relay.json"), "state file")
	flag.Parse()
	store, err := state.Open(*statePath)
	if err != nil {
		log.Fatal(err)
	}
	s := &server{store: store, bootstrap: os.Getenv("HARNESS_RELAY_BOOTSTRAP_TOKEN"), notifier: notifierFromEnv(), hosts: map[string]*client{}, devices: map[string]*client{}}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("POST /v1/hosts/register", s.registerHost)
	mux.HandleFunc("POST /v1/hosts/recover", s.recoverHost)
	mux.HandleFunc("POST /v1/pairings", s.createPairing)
	mux.HandleFunc("POST /v1/enroll", s.enroll)
	mux.HandleFunc("PATCH /v1/devices/{deviceID}", s.updateDevice)
	mux.HandleFunc("DELETE /v1/devices/{deviceID}", s.revokeDevice)
	mux.HandleFunc("GET /v1/ws", s.websocket)
	handler := rateLimit(limits(corsDenied(mux)))
	log.Printf("Harness Relay listening on %s", *listen)
	log.Fatal(http.ListenAndServe(*listen, handler))
}

func (s *server) recoverHost(w http.ResponseWriter, r *http.Request) {
	var request struct{ HostID, RecoveryCode string }
	if !decode(w, r, &request) || request.HostID == "" || request.RecoveryCode == "" {
		return
	}
	token, recovery, err := s.store.RecoverHost(request.HostID, request.RecoveryCode)
	if err != nil {
		http.Error(w, "invalid recovery code", http.StatusUnauthorized)
		return
	}
	s.mu.Lock()
	if active := s.hosts[request.HostID]; active != nil {
		_ = active.conn.Close(websocket.StatusPolicyViolation, "host recovered")
		delete(s.hosts, request.HostID)
	}
	for id, active := range s.devices {
		_ = active.conn.Close(websocket.StatusPolicyViolation, "host recovered")
		delete(s.devices, id)
	}
	s.mu.Unlock()
	respond(w, http.StatusOK, map[string]string{"hostId": request.HostID, "hostToken": token, "recoveryCode": recovery})
}

func (s *server) registerHost(w http.ResponseWriter, r *http.Request) {
	if s.bootstrap == "" || bearer(r) != s.bootstrap {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var request struct{ HostID, Name string }
	if !decode(w, r, &request) || request.HostID == "" {
		return
	}
	token, recovery, err := s.store.RegisterHost(request.HostID, request.Name)
	if err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	respond(w, http.StatusCreated, map[string]string{"hostId": request.HostID, "hostToken": token, "recoveryCode": recovery})
}

func (s *server) createPairing(w http.ResponseWriter, r *http.Request) {
	hostID := r.Header.Get("X-Harness-Host-ID")
	if _, ok := s.store.AuthenticateHost(hostID, bearer(r)); !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	pairing, err := s.store.CreatePairing(hostID, 5*time.Minute)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	respond(w, http.StatusCreated, pairing)
}

func (s *server) enroll(w http.ResponseWriter, r *http.Request) {
	var request struct{ Ticket, DeviceName, PushTarget string }
	if !decode(w, r, &request) {
		return
	}
	device, token, err := s.store.Enroll(request.Ticket, request.DeviceName, request.PushTarget)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}
	respond(w, http.StatusCreated, map[string]any{"deviceId": device.ID, "hostId": device.HostID, "deviceToken": token})
}

func (s *server) revokeDevice(w http.ResponseWriter, r *http.Request) {
	hostID := r.Header.Get("X-Harness-Host-ID")
	if _, ok := s.store.AuthenticateHost(hostID, bearer(r)); !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	deviceID := r.PathValue("deviceID")
	if err := s.store.RevokeDevice(hostID, deviceID); err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	s.mu.Lock()
	if active := s.devices[deviceID]; active != nil {
		_ = active.conn.Close(websocket.StatusPolicyViolation, "device revoked")
		delete(s.devices, deviceID)
	}
	s.mu.Unlock()
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) updateDevice(w http.ResponseWriter, r *http.Request) {
	var request struct{ PushTarget string }
	if !decode(w, r, &request) {
		return
	}
	if err := s.store.UpdatePushTarget(r.PathValue("deviceID"), bearer(r), request.PushTarget); err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) websocket(w http.ResponseWriter, r *http.Request) {
	role, id := r.URL.Query().Get("role"), r.URL.Query().Get("id")
	var hostID string
	switch role {
	case "host":
		if _, ok := s.store.AuthenticateHost(id, bearer(r)); !ok {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		hostID = id
	case "device":
		device, ok := s.store.AuthenticateDevice(id, bearer(r))
		if !ok {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		hostID = device.HostID
	default:
		http.Error(w, "invalid role", http.StatusBadRequest)
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{OriginPatterns: []string{"*"}})
	if err != nil {
		return
	}
	conn.SetReadLimit(maxWireMessageBytes)
	c := &client{conn: conn}
	s.setClient(role, id, c)
	if role == "host" {
		pendingMessages := s.store.DrainHost(id)
		for index, pending := range pendingMessages {
			if err := c.write(r.Context(), pending); err != nil {
				for _, remaining := range pendingMessages[index:] {
					_ = s.store.EnqueueHost(id, remaining)
				}
				break
			}
		}
	} else {
		pendingMessages := s.store.Drain(id)
		for index, pending := range pendingMessages {
			if err := c.write(r.Context(), pending); err != nil {
				for _, remaining := range pendingMessages[index:] {
					_ = s.store.Enqueue(id, remaining)
				}
				break
			}
		}
	}
	defer func() { s.removeClient(role, id, c); _ = conn.Close(websocket.StatusNormalClosure, "closed") }()
	ctx := r.Context()
	for {
		kind, raw, err := conn.Read(ctx)
		if err != nil {
			return
		}
		if kind != websocket.MessageText || len(raw) > maxWireMessageBytes {
			continue
		}
		var message protocol.WireMessage
		if json.Unmarshal(raw, &message) != nil || message.Version != protocol.Version || message.HostID != hostID || message.ExpiresAt < time.Now().UnixMilli() {
			continue
		}
		if role == "host" {
			if message.DeviceID == "" {
				continue
			}
			s.forwardDevice(ctx, message.DeviceID, message)
		} else {
			message.DeviceID = id
			s.forwardHost(ctx, hostID, message)
		}
	}
}

func (s *server) forwardHost(ctx context.Context, hostID string, message protocol.WireMessage) {
	s.mu.RLock()
	target := s.hosts[hostID]
	s.mu.RUnlock()
	if target != nil {
		if target.write(ctx, message) == nil {
			return
		}
	}
	_ = s.store.EnqueueHost(hostID, message)
}

func (s *server) forwardDevice(ctx context.Context, deviceID string, message protocol.WireMessage) {
	s.mu.RLock()
	target := s.devices[deviceID]
	s.mu.RUnlock()
	if target != nil {
		if target.write(ctx, message) == nil {
			return
		}
	}
	_ = s.store.Enqueue(deviceID, message)
	if message.PushKind != "" {
		if device, ok := s.store.Device(deviceID); ok {
			go func() { _ = s.notifier.Notify(context.Background(), device.PushTarget, message.PushKind) }()
		}
	}
}

func (s *server) setClient(role, id string, c *client) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if role == "host" {
		s.hosts[id] = c
	} else {
		s.devices[id] = c
	}
}
func (s *server) removeClient(role, id string, c *client) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if role == "host" && s.hosts[id] == c {
		delete(s.hosts, id)
	}
	if role == "device" && s.devices[id] == c {
		delete(s.devices, id)
	}
}

func notifierFromEnv() push.Notifier {
	appKey, _ := strconv.ParseInt(os.Getenv("ALIYUN_PUSH_APP_KEY"), 10, 64)
	n, err := push.NewAliyunFromEnv(os.Getenv("ALIYUN_ACCESS_KEY_ID"), os.Getenv("ALIYUN_ACCESS_KEY_SECRET"), appKey)
	if err != nil {
		log.Printf("Aliyun push disabled: %v", err)
		return push.Noop{}
	}
	return n
}

func bearer(r *http.Request) string {
	return strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
}
func decode(w http.ResponseWriter, r *http.Request, target any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(target); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return false
	}
	return true
}
func respond(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
func limits(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/ws" {
			next.ServeHTTP(w, r)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
func corsDenied(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Origin") != "" && !strings.HasSuffix(r.URL.Path, "/ws") {
			http.Error(w, "browser origins are not accepted", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}
func rateLimit(next http.Handler) http.Handler {
	type bucket struct {
		started time.Time
		count   int
	}
	var mu sync.Mutex
	buckets := map[string]bucket{}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		mu.Lock()
		current := buckets[host]
		if current.started.IsZero() || time.Since(current.started) >= time.Minute {
			current = bucket{started: time.Now()}
		}
		current.count++
		buckets[host] = current
		if len(buckets) > 1024 {
			for key, value := range buckets {
				if time.Since(value.started) > 2*time.Minute {
					delete(buckets, key)
				}
			}
		}
		allowed := current.count <= 120
		mu.Unlock()
		if !allowed {
			http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}
