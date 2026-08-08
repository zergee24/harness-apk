package journal

import (
	"bufio"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"

	"github.com/harnessapk/remote/internal/protocol"
)

const journalAAD = "harness-remote-logical-journal-v1"

type streamKey struct {
	hostID   string
	deviceID string
}

type operation struct {
	Type     string                 `json:"type"`
	Event    *protocol.LogicalEvent `json:"event,omitempty"`
	HostID   string                 `json:"hostId,omitempty"`
	DeviceID string                 `json:"deviceId,omitempty"`
	Through  uint64                 `json:"through,omitempty"`
	GapFrom  uint64                 `json:"gapFrom,omitempty"`
}

type encryptedLine struct {
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

type Store struct {
	mu           sync.Mutex
	path         string
	key          []byte
	maxRecords   int
	events       map[string]protocol.LogicalEvent
	streams      map[streamKey][]string
	ackedThrough map[streamKey]uint64
	gaps         map[streamKey]uint64
}

func Open(path string, key []byte, maxRecords int) (*Store, error) {
	if len(key) != 32 {
		return nil, errors.New("journal key must be 32 bytes")
	}
	if maxRecords <= 0 {
		return nil, errors.New("journal max records must be positive")
	}
	s := &Store{
		path: path, key: append([]byte(nil), key...), maxRecords: maxRecords,
		events: map[string]protocol.LogicalEvent{}, streams: map[streamKey][]string{},
		ackedThrough: map[streamKey]uint64{}, gaps: map[streamKey]uint64{},
	}
	file, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64<<10), 4<<20)
	for scanner.Scan() {
		op, err := decryptOperation(s.key, scanner.Bytes())
		if err != nil {
			return nil, fmt.Errorf("decode journal: %w", err)
		}
		if err := s.apply(op); err != nil {
			return nil, fmt.Errorf("replay journal: %w", err)
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) Append(event protocol.LogicalEvent) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.appendLocked(event)
}

func (s *Store) AppendNext(event protocol.LogicalEvent) (protocol.LogicalEvent, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := streamKey{event.HostID, event.DeviceID}
	sequence := s.ackedThrough[key]
	for _, id := range s.streams[key] {
		if current := s.events[id].Sequence; current > sequence {
			sequence = current
		}
	}
	event.Sequence = sequence + 1
	if err := s.appendLocked(event); err != nil {
		return protocol.LogicalEvent{}, err
	}
	return cloneEvent(event), nil
}

func (s *Store) Has(eventID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, exists := s.events[eventID]
	return exists
}

func (s *Store) appendLocked(event protocol.LogicalEvent) error {
	if err := validateEvent(event); err != nil {
		return err
	}
	if _, exists := s.events[event.EventID]; exists {
		return errors.New("logical event id already exists")
	}
	key := streamKey{event.HostID, event.DeviceID}
	for _, id := range s.streams[key] {
		if s.events[id].Sequence == event.Sequence {
			return errors.New("logical event sequence already exists")
		}
	}
	op := operation{Type: "append", Event: &event}
	if err := s.appendOperation(op); err != nil {
		return err
	}
	return s.apply(op)
}

func (s *Store) Ack(hostID, deviceID string, through uint64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	op := operation{Type: "ack", HostID: hostID, DeviceID: deviceID, Through: through}
	if err := s.appendOperation(op); err != nil {
		return err
	}
	return s.apply(op)
}

func (s *Store) Pending(hostID, deviceID string) []protocol.LogicalEvent {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := streamKey{hostID, deviceID}
	result := make([]protocol.LogicalEvent, 0, len(s.streams[key]))
	for _, id := range s.streams[key] {
		event := s.events[id]
		if event.Sequence > s.ackedThrough[key] {
			result = append(result, cloneEvent(event))
		}
	}
	return result
}

func (s *Store) Head(hostID, deviceID string) uint64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := streamKey{hostID, deviceID}
	head := s.ackedThrough[key]
	for _, id := range s.streams[key] {
		if sequence := s.events[id].Sequence; sequence > head {
			head = sequence
		}
	}
	return head
}

func (s *Store) RequiresSnapshot(hostID, deviceID string, after uint64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := streamKey{hostID, deviceID}
	gapFrom, hasGap := s.gaps[key]
	if !hasGap || after >= gapFrom {
		return false
	}
	firstAvailable := uint64(0)
	for _, id := range s.streams[key] {
		sequence := s.events[id].Sequence
		if sequence > after && (firstAvailable == 0 || sequence < firstAvailable) {
			firstAvailable = sequence
		}
	}
	return firstAvailable == 0 || firstAvailable > after+1
}

func (s *Store) GapFrom(hostID, deviceID string) (uint64, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	gap, ok := s.gaps[streamKey{hostID, deviceID}]
	return gap, ok
}

func (s *Store) Replay(eventID string, secret []byte, transportSequence uint64) (protocol.WireMessage, error) {
	s.mu.Lock()
	event, ok := s.events[eventID]
	s.mu.Unlock()
	if !ok {
		return protocol.WireMessage{}, errors.New("logical event not found")
	}
	return protocol.Encrypt(secret, protocol.WireMessage{
		HostID: event.HostID, DeviceID: event.DeviceID, Sequence: transportSequence, PushKind: "wake",
	}, event)
}

func (s *Store) Compact() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for key, ids := range s.streams {
		kept := make([]string, 0, len(ids))
		for _, id := range ids {
			if s.events[id].Sequence <= s.ackedThrough[key] {
				delete(s.events, id)
				continue
			}
			kept = append(kept, id)
		}
		if len(kept) > s.maxRecords {
			drop := len(kept) - s.maxRecords
			gapFrom := s.events[kept[0]].Sequence
			op := operation{Type: "gap", HostID: key.hostID, DeviceID: key.deviceID, GapFrom: gapFrom}
			if err := s.appendOperation(op); err != nil {
				return err
			}
			if err := s.apply(op); err != nil {
				return err
			}
			for _, id := range kept[:drop] {
				delete(s.events, id)
			}
			kept = kept[drop:]
		}
		s.streams[key] = kept
	}
	return s.rewriteLocked()
}

func (s *Store) apply(op operation) error {
	switch op.Type {
	case "append":
		if op.Event == nil {
			return errors.New("append operation missing event")
		}
		event := cloneEvent(*op.Event)
		s.events[event.EventID] = event
		key := streamKey{event.HostID, event.DeviceID}
		s.streams[key] = append(s.streams[key], event.EventID)
		sort.SliceStable(s.streams[key], func(i, j int) bool {
			return s.events[s.streams[key][i]].Sequence < s.events[s.streams[key][j]].Sequence
		})
	case "ack":
		key := streamKey{op.HostID, op.DeviceID}
		if op.Through > s.ackedThrough[key] {
			s.ackedThrough[key] = op.Through
		}
	case "gap":
		key := streamKey{op.HostID, op.DeviceID}
		if current, exists := s.gaps[key]; !exists || op.GapFrom < current {
			s.gaps[key] = op.GapFrom
		}
	default:
		return fmt.Errorf("unknown journal operation %q", op.Type)
	}
	return nil
}

func (s *Store) appendOperation(op operation) error {
	line, err := encryptOperation(s.key, op)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	file, err := os.OpenFile(s.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	if _, err = file.Write(append(line, '\n')); err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	return closeErr
}

func (s *Store) rewriteLocked() error {
	operations := make([]operation, 0, len(s.events)+len(s.gaps)+len(s.ackedThrough))
	keys := make([]streamKey, 0, len(s.streams))
	for key := range s.streams {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i].hostID == keys[j].hostID {
			return keys[i].deviceID < keys[j].deviceID
		}
		return keys[i].hostID < keys[j].hostID
	})
	for _, key := range keys {
		if gap, ok := s.gaps[key]; ok {
			operations = append(operations, operation{Type: "gap", HostID: key.hostID, DeviceID: key.deviceID, GapFrom: gap})
		}
		if through := s.ackedThrough[key]; through > 0 {
			operations = append(operations, operation{Type: "ack", HostID: key.hostID, DeviceID: key.deviceID, Through: through})
		}
		for _, id := range s.streams[key] {
			event := s.events[id]
			operations = append(operations, operation{Type: "append", Event: &event})
		}
	}
	tmpPath := s.path + ".tmp"
	file, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	writer := bufio.NewWriter(file)
	for _, op := range operations {
		line, lineErr := encryptOperation(s.key, op)
		if lineErr != nil {
			_ = file.Close()
			return lineErr
		}
		if _, err = writer.Write(append(line, '\n')); err != nil {
			_ = file.Close()
			return err
		}
	}
	if err = writer.Flush(); err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(tmpPath, s.path)
}

func encryptOperation(key []byte, op operation) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	plain, err := json.Marshal(op)
	if err != nil {
		return nil, err
	}
	line := encryptedLine{
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce),
		Ciphertext: base64.RawURLEncoding.EncodeToString(gcm.Seal(nil, nonce, plain, []byte(journalAAD))),
	}
	return json.Marshal(line)
}

func decryptOperation(key, line []byte) (operation, error) {
	var encrypted encryptedLine
	if err := json.Unmarshal(line, &encrypted); err != nil {
		return operation{}, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return operation{}, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return operation{}, err
	}
	nonce, err := base64.RawURLEncoding.DecodeString(encrypted.Nonce)
	if err != nil || len(nonce) != gcm.NonceSize() {
		return operation{}, errors.New("invalid journal nonce")
	}
	ciphertext, err := base64.RawURLEncoding.DecodeString(encrypted.Ciphertext)
	if err != nil {
		return operation{}, errors.New("invalid journal ciphertext")
	}
	plain, err := gcm.Open(nil, nonce, ciphertext, []byte(journalAAD))
	if err != nil {
		return operation{}, errors.New("journal authentication failed")
	}
	var op operation
	if err := json.Unmarshal(plain, &op); err != nil {
		return operation{}, err
	}
	return op, nil
}

func validateEvent(event protocol.LogicalEvent) error {
	if event.SchemaVersion != 1 || event.EventID == "" || event.HostID == "" || event.DeviceID == "" || event.RunID == "" || event.Sequence == 0 || event.Type == "" || event.CreatedAt == 0 {
		return errors.New("logical event stable identity is incomplete")
	}
	return nil
}

func cloneEvent(event protocol.LogicalEvent) protocol.LogicalEvent {
	event.Payload = append(json.RawMessage(nil), event.Payload...)
	return event
}
