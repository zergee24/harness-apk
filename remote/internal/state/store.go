package state

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/harnessapk/remote/internal/protocol"
)

type Host struct {
	ID                 string `json:"id"`
	Name               string `json:"name"`
	TokenHash          string `json:"tokenHash"`
	RecoveryHash       string `json:"recoveryHash"`
	CreatedAt          int64  `json:"createdAt"`
	LastSeenAt         int64  `json:"lastSeenAt"`
	NextServerSequence uint64 `json:"nextServerSequence"`
}

type Device struct {
	ID         string `json:"id"`
	HostID     string `json:"hostId"`
	Name       string `json:"name"`
	TokenHash  string `json:"tokenHash"`
	PushTarget string `json:"pushTarget,omitempty"`
	CreatedAt  int64  `json:"createdAt"`
	LastSeenAt int64  `json:"lastSeenAt"`
	RevokedAt  int64  `json:"revokedAt,omitempty"`
}

type Pairing struct {
	Ticket    string `json:"ticket"`
	HostID    string `json:"hostId"`
	ExpiresAt int64  `json:"expiresAt"`
	UsedAt    int64  `json:"usedAt,omitempty"`
}

type Data struct {
	Hosts    map[string]*Host                  `json:"hosts"`
	Devices  map[string]*Device                `json:"devices"`
	Pairings map[string]*Pairing               `json:"pairings"`
	Pending  map[string][]protocol.WireMessage `json:"pending,omitempty"`
}

type Store struct {
	mu   sync.Mutex
	path string
	data Data
}

func Open(path string) (*Store, error) {
	s := &Store{path: path, data: Data{Hosts: map[string]*Host{}, Devices: map[string]*Device{}, Pairings: map[string]*Pairing{}, Pending: map[string][]protocol.WireMessage{}}}
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, &s.data); err != nil {
		return nil, err
	}
	if s.data.Hosts == nil {
		s.data.Hosts = map[string]*Host{}
	}
	if s.data.Devices == nil {
		s.data.Devices = map[string]*Device{}
	}
	if s.data.Pairings == nil {
		s.data.Pairings = map[string]*Pairing{}
	}
	if s.data.Pending == nil {
		s.data.Pending = map[string][]protocol.WireMessage{}
	}
	return s, nil
}

func (s *Store) RegisterHost(id, name string) (token, recovery string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.data.Hosts) != 0 {
		return "", "", errors.New("relay bootstrap has already been used")
	}
	if _, exists := s.data.Hosts[id]; exists {
		return "", "", errors.New("host already exists")
	}
	token, err = randomToken(32)
	if err != nil {
		return "", "", err
	}
	recovery, err = randomToken(32)
	if err != nil {
		return "", "", err
	}
	s.data.Hosts[id] = &Host{ID: id, Name: name, TokenHash: hash(token), RecoveryHash: hash(recovery), CreatedAt: time.Now().UnixMilli()}
	err = s.saveLocked()
	return
}

func (s *Store) RecoverHost(id, recovery string) (token, nextRecovery string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	host := s.data.Hosts[id]
	if host == nil || host.RecoveryHash != hash(recovery) {
		return "", "", errors.New("invalid recovery code")
	}
	token, err = randomToken(32)
	if err != nil {
		return "", "", err
	}
	nextRecovery, err = randomToken(32)
	if err != nil {
		return "", "", err
	}
	host.TokenHash = hash(token)
	host.RecoveryHash = hash(nextRecovery)
	for _, device := range s.data.Devices {
		if device.HostID == id && device.RevokedAt == 0 {
			device.RevokedAt = time.Now().UnixMilli()
			delete(s.data.Pending, device.ID)
		}
	}
	for ticket, pairing := range s.data.Pairings {
		if pairing.HostID == id {
			delete(s.data.Pairings, ticket)
		}
	}
	err = s.saveLocked()
	return
}

func (s *Store) AuthenticateHost(id, token string) (*Host, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	host := s.data.Hosts[id]
	if host == nil || host.TokenHash != hash(token) {
		return nil, false
	}
	host.LastSeenAt = time.Now().UnixMilli()
	_ = s.saveLocked()
	copy := *host
	return &copy, true
}

func (s *Store) CreatePairing(hostID string, ttl time.Duration) (*Pairing, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	ticket, err := randomToken(24)
	if err != nil {
		return nil, err
	}
	p := &Pairing{Ticket: ticket, HostID: hostID, ExpiresAt: time.Now().Add(ttl).UnixMilli()}
	s.data.Pairings[ticket] = p
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	copy := *p
	return &copy, nil
}

func (s *Store) Enroll(ticket, name, pushTarget string) (*Device, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.data.Pairings[ticket]
	if p == nil || p.UsedAt != 0 || p.ExpiresAt < time.Now().UnixMilli() {
		return nil, "", errors.New("pairing ticket is invalid or expired")
	}
	id, err := randomToken(16)
	if err != nil {
		return nil, "", err
	}
	token, err := randomToken(32)
	if err != nil {
		return nil, "", err
	}
	p.UsedAt = time.Now().UnixMilli()
	d := &Device{ID: id, HostID: p.HostID, Name: name, TokenHash: hash(token), PushTarget: pushTarget, CreatedAt: time.Now().UnixMilli()}
	s.data.Devices[id] = d
	if err := s.saveLocked(); err != nil {
		return nil, "", err
	}
	copy := *d
	return &copy, token, nil
}

func (s *Store) AuthenticateDevice(id, token string) (*Device, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.data.Devices[id]
	if d == nil || d.RevokedAt != 0 || d.TokenHash != hash(token) {
		return nil, false
	}
	d.LastSeenAt = time.Now().UnixMilli()
	_ = s.saveLocked()
	copy := *d
	return &copy, true
}

func (s *Store) Device(id string) (*Device, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.data.Devices[id]
	if d == nil || d.RevokedAt != 0 {
		return nil, false
	}
	copy := *d
	return &copy, true
}

func (s *Store) RevokeDevice(hostID, deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.data.Devices[deviceID]
	if d == nil || d.HostID != hostID {
		return errors.New("device not found")
	}
	d.RevokedAt = time.Now().UnixMilli()
	delete(s.data.Pending, deviceID)
	return s.saveLocked()
}

func (s *Store) Enqueue(deviceID string, message protocol.WireMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.data.Devices[deviceID]
	if d == nil || d.RevokedAt != 0 {
		return errors.New("device not found")
	}
	now := time.Now().UnixMilli()
	pending := s.data.Pending[deviceID][:0]
	for _, candidate := range s.data.Pending[deviceID] {
		if candidate.ExpiresAt > now {
			pending = append(pending, candidate)
		}
	}
	pending = append(pending, message)
	if len(pending) > 100 {
		pending = pending[len(pending)-100:]
	}
	s.data.Pending[deviceID] = pending
	return s.saveLocked()
}

func (s *Store) Drain(deviceID string) []protocol.WireMessage {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UnixMilli()
	result := make([]protocol.WireMessage, 0, len(s.data.Pending[deviceID]))
	for _, message := range s.data.Pending[deviceID] {
		if message.ExpiresAt > now {
			result = append(result, message)
		}
	}
	delete(s.data.Pending, deviceID)
	_ = s.saveLocked()
	return result
}

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func randomToken(size int) (string, error) {
	raw := make([]byte, size)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func hash(value string) string {
	digest := sha256.Sum256([]byte(value))
	return hex.EncodeToString(digest[:])
}
