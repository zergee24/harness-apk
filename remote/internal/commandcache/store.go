package commandcache

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type Status string

const (
	StatusInFlight  Status = "IN_FLIGHT"
	StatusUnknown   Status = "UNKNOWN"
	StatusSucceeded Status = "SUCCEEDED"
	StatusFailed    Status = "FAILED"
)

type Record struct {
	CommandID     string          `json:"commandId"`
	Type          string          `json:"type"`
	PayloadSHA256 string          `json:"payloadSha256"`
	Status        Status          `json:"status"`
	ResultEventID string          `json:"resultEventId,omitempty"`
	ResultJSON    json.RawMessage `json:"resultJson,omitempty"`
	LastError     string          `json:"lastError,omitempty"`
	CreatedAt     int64           `json:"createdAt"`
	UpdatedAt     int64           `json:"updatedAt"`
}

type data struct {
	SchemaVersion int                `json:"schemaVersion"`
	Records       map[string]*Record `json:"records"`
}

type Store struct {
	mu   sync.Mutex
	path string
	data data
}

func Open(path string) (*Store, error) {
	s := &Store{path: path, data: data{SchemaVersion: 1, Records: map[string]*Record{}}}
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, &s.data); err != nil {
		return nil, fmt.Errorf("decode command cache: %w", err)
	}
	if s.data.Records == nil {
		s.data.Records = map[string]*Record{}
	}
	dirty := false
	for _, record := range s.data.Records {
		if record.Status == StatusInFlight {
			record.Status = StatusUnknown
			record.LastError = "bridge restarted before command result was persisted"
			record.UpdatedAt = time.Now().UnixMilli()
			dirty = true
		}
	}
	if dirty {
		if err := s.saveLocked(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *Store) Begin(commandID, commandType, payloadSHA256 string) (Record, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if commandID == "" || commandType == "" || payloadSHA256 == "" {
		return Record{}, false, errors.New("command id, type, and payload hash are required")
	}
	if existing := s.data.Records[commandID]; existing != nil {
		if existing.Type != commandType || existing.PayloadSHA256 != payloadSHA256 {
			return Record{}, false, errors.New("command id already belongs to another payload")
		}
		return clone(*existing), false, nil
	}
	now := time.Now().UnixMilli()
	record := &Record{
		CommandID: commandID, Type: commandType, PayloadSHA256: payloadSHA256,
		Status: StatusInFlight, CreatedAt: now, UpdatedAt: now,
	}
	s.data.Records[commandID] = record
	if err := s.saveLocked(); err != nil {
		delete(s.data.Records, commandID)
		return Record{}, false, err
	}
	return clone(*record), true, nil
}

func (s *Store) Complete(commandID, resultEventID string, result json.RawMessage) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	if record.Status != StatusInFlight {
		return clone(*record), nil
	}
	record.Status = StatusSucceeded
	record.ResultEventID = resultEventID
	record.ResultJSON = append(json.RawMessage(nil), result...)
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		return Record{}, err
	}
	return clone(*record), nil
}

func (s *Store) Fail(commandID string, cause error) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	record.Status = StatusFailed
	record.LastError = cause.Error()
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		return Record{}, err
	}
	return clone(*record), nil
}

func (s *Store) MarkUnknown(commandID string, cause error) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	record.Status = StatusUnknown
	record.LastError = cause.Error()
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		return Record{}, err
	}
	return clone(*record), nil
}

func (s *Store) Lookup(commandID string) (Record, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, false
	}
	return clone(*record), true
}

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	return atomicWrite(s.path, raw, 0o600)
}

func atomicWrite(path string, raw []byte, mode os.FileMode) error {
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

func clone(record Record) Record {
	record.ResultJSON = append(json.RawMessage(nil), record.ResultJSON...)
	return record
}
