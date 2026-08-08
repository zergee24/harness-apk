package state

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
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
	Hosts        map[string]*Host                  `json:"hosts"`
	Devices      map[string]*Device                `json:"devices"`
	Pairings     map[string]*Pairing               `json:"pairings"`
	Pending      map[string][]protocol.WireMessage `json:"pending,omitempty"`
	PendingHosts map[string][]protocol.WireMessage `json:"pendingHosts,omitempty"`
}

const BridgeSchemaVersion = 2

type BridgeData struct {
	SchemaVersion           int                          `json:"schemaVersion"`
	RelayURL                string                       `json:"relayUrl"`
	HostID                  string                       `json:"hostId"`
	HostName                string                       `json:"hostName"`
	HostToken               string                       `json:"hostToken"`
	Pending                 map[string]string            `json:"pendingPairingSecrets"`
	DeviceSecrets           map[string]string            `json:"deviceSecrets"`
	Sequences               map[string]uint64            `json:"sequences"`
	PendingOutbound         map[string]map[string]string `json:"pendingOutbound,omitempty"`
	NeedsInitialGapSnapshot bool                         `json:"needsInitialGapSnapshot,omitempty"`
	JournalKey              string                       `json:"journalKey"`
}

func LoadBridge(path string) (BridgeData, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return BridgeData{}, err
	}
	var data BridgeData
	if err := json.Unmarshal(raw, &data); err != nil {
		return BridgeData{}, err
	}
	migrated, err := normalizeBridge(&data, true)
	if err != nil {
		return BridgeData{}, err
	}
	if migrated {
		if err := SaveBridge(path, data); err != nil {
			return BridgeData{}, err
		}
	}
	return data, nil
}

func SaveBridge(path string, data BridgeData) error {
	if _, err := normalizeBridge(&data, false); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return err
	}
	return writeAtomic(path, raw, 0o600)
}

func normalizeBridge(data *BridgeData, loading bool) (bool, error) {
	dirty := false
	if data.Pending == nil {
		data.Pending = map[string]string{}
		dirty = true
	}
	if data.DeviceSecrets == nil {
		data.DeviceSecrets = map[string]string{}
		dirty = true
	}
	if data.Sequences == nil {
		data.Sequences = map[string]uint64{}
		dirty = true
	}
	if data.PendingOutbound == nil {
		data.PendingOutbound = map[string]map[string]string{}
		dirty = true
	}
	if data.SchemaVersion < BridgeSchemaVersion {
		if loading && len(data.PendingOutbound) > 0 {
			data.NeedsInitialGapSnapshot = true
		}
		data.PendingOutbound = map[string]map[string]string{}
		data.SchemaVersion = BridgeSchemaVersion
		dirty = true
	}
	if data.SchemaVersion != BridgeSchemaVersion {
		return false, fmt.Errorf("unsupported bridge state schema %d", data.SchemaVersion)
	}
	if data.JournalKey == "" {
		key := make([]byte, 32)
		if _, err := rand.Read(key); err != nil {
			return false, err
		}
		data.JournalKey = base64.RawURLEncoding.EncodeToString(key)
		dirty = true
	}
	return dirty, nil
}

type Store struct {
	mu   sync.Mutex
	path string
	data Data
}

func Open(path string) (*Store, error) {
	s := &Store{path: path, data: Data{
		Hosts: map[string]*Host{}, Devices: map[string]*Device{},
		Pairings: map[string]*Pairing{}, Pending: map[string][]protocol.WireMessage{},
		PendingHosts: map[string][]protocol.WireMessage{},
	}}
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
	if s.data.PendingHosts == nil {
		s.data.PendingHosts = map[string][]protocol.WireMessage{}
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
	delete(s.data.PendingHosts, id)
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

func (s *Store) UpdatePushTarget(id, token, pushTarget string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	device := s.data.Devices[id]
	if device == nil || device.RevokedAt != 0 || device.TokenHash != hash(token) {
		return errors.New("invalid device credentials")
	}
	device.PushTarget = pushTarget
	return s.saveLocked()
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

func (s *Store) EnqueueHost(hostID string, message protocol.WireMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.Hosts[hostID] == nil {
		return errors.New("host not found")
	}
	s.data.PendingHosts[hostID] = appendPending(s.data.PendingHosts[hostID], message)
	return s.saveLocked()
}

func (s *Store) DrainHost(hostID string) []protocol.WireMessage {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := liveMessages(s.data.PendingHosts[hostID])
	delete(s.data.PendingHosts, hostID)
	_ = s.saveLocked()
	return result
}

func appendPending(messages []protocol.WireMessage, message protocol.WireMessage) []protocol.WireMessage {
	pending := liveMessages(messages)
	pending = append(pending, message)
	if len(pending) > 100 {
		pending = pending[len(pending)-100:]
	}
	return pending
}

func liveMessages(messages []protocol.WireMessage) []protocol.WireMessage {
	now := time.Now().UnixMilli()
	result := make([]protocol.WireMessage, 0, len(messages))
	for _, message := range messages {
		if message.ExpiresAt > now {
			result = append(result, message)
		}
	}
	return result
}

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	return writeAtomic(s.path, raw, 0o600)
}

func writeAtomic(path string, raw []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.OpenFile(path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	if _, err = tmp.Write(raw); err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Rename(path+".tmp", path); err != nil {
		return err
	}
	if directory, err := os.Open(dir); err == nil {
		defer directory.Close()
		return directory.Sync()
	}
	return nil
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
