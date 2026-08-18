package commandcache

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
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
	ContextJSON   json.RawMessage `json:"contextJson,omitempty"`
	LastError     string          `json:"lastError,omitempty"`
	DispatchOrder uint64          `json:"dispatchOrder,omitempty"`
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
	needsDispatchOrderBackfill := false
	for _, record := range s.data.Records {
		if record.Status == StatusInFlight {
			record.Status = StatusUnknown
			record.LastError = "bridge restarted before command result was persisted"
			record.UpdatedAt = time.Now().UnixMilli()
			dirty = true
		}
		if record.DispatchOrder == 0 {
			needsDispatchOrderBackfill = true
		}
	}
	if needsDispatchOrderBackfill {
		records := make([]*Record, 0, len(s.data.Records))
		for _, record := range s.data.Records {
			records = append(records, record)
		}
		sort.Slice(records, func(i, j int) bool {
			if records[i].CreatedAt != records[j].CreatedAt {
				return records[i].CreatedAt < records[j].CreatedAt
			}
			return records[i].CommandID < records[j].CommandID
		})
		for index, record := range records {
			record.DispatchOrder = uint64(index + 1)
		}
		dirty = true
	}
	if dirty {
		if err := s.saveLocked(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *Store) Begin(commandID, commandType, payloadSHA256 string) (Record, bool, error) {
	return s.BeginWithContext(commandID, commandType, payloadSHA256, nil)
}

func (s *Store) BeginWithContext(commandID, commandType, payloadSHA256 string, contextJSON json.RawMessage) (Record, bool, error) {
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
	var dispatchOrder uint64 = 1
	for _, existing := range s.data.Records {
		if existing.DispatchOrder >= dispatchOrder {
			dispatchOrder = existing.DispatchOrder + 1
		}
	}
	record := &Record{
		CommandID: commandID, Type: commandType, PayloadSHA256: payloadSHA256,
		Status: StatusInFlight, ContextJSON: append(json.RawMessage(nil), contextJSON...), DispatchOrder: dispatchOrder,
		CreatedAt: now, UpdatedAt: now,
	}
	s.data.Records[commandID] = record
	if err := s.saveLocked(); err != nil {
		delete(s.data.Records, commandID)
		return Record{}, false, err
	}
	return clone(*record), true, nil
}

func (s *Store) AttachContext(commandID string, contextJSON json.RawMessage) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if commandID == "" || len(contextJSON) == 0 {
		return Record{}, errors.New("command id and reconciliation context are required")
	}
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	if len(record.ContextJSON) != 0 {
		return clone(*record), nil
	}
	previous := clone(*record)
	record.ContextJSON = append(json.RawMessage(nil), contextJSON...)
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		*record = previous
		return Record{}, err
	}
	return clone(*record), nil
}

func (s *Store) MovePayloadHash(commandID, expectedHash, payloadHash string) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if commandID == "" || expectedHash == "" || payloadHash == "" {
		return Record{}, errors.New("command id and payload hashes are required")
	}
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	if record.PayloadSHA256 == payloadHash {
		return clone(*record), nil
	}
	if record.PayloadSHA256 != expectedHash {
		return Record{}, errors.New("command payload changed before migration")
	}
	previousHash := record.PayloadSHA256
	previousUpdatedAt := record.UpdatedAt
	record.PayloadSHA256 = payloadHash
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		record.PayloadSHA256 = previousHash
		record.UpdatedAt = previousUpdatedAt
		return Record{}, err
	}
	return clone(*record), nil
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
	previous := clone(*record)
	record.Status = StatusSucceeded
	record.ResultEventID = resultEventID
	record.ResultJSON = append(json.RawMessage(nil), result...)
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		*record = previous
		return Record{}, err
	}
	return clone(*record), nil
}

func (s *Store) Succeed(commandID, resultEventID string, result json.RawMessage) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	if record.Status == StatusSucceeded {
		return clone(*record), nil
	}
	if record.Status != StatusInFlight && record.Status != StatusUnknown {
		return clone(*record), errors.New("command cannot be marked succeeded")
	}
	previous := clone(*record)
	record.Status = StatusSucceeded
	record.ResultEventID = resultEventID
	record.ResultJSON = append(json.RawMessage(nil), result...)
	record.LastError = ""
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		*record = previous
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
	previous := clone(*record)
	record.Status = StatusFailed
	record.LastError = cause.Error()
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		*record = previous
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
		// Keep the process-local record UNKNOWN: the remote side effect may have
		// happened, so replay must not treat a failed durability write as success.
		return clone(*record), err
	}
	return clone(*record), nil
}

func (s *Store) MarkUnknownWithResult(commandID string, cause error, result json.RawMessage) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	record.Status = StatusUnknown
	record.ResultJSON = append(json.RawMessage(nil), result...)
	record.LastError = cause.Error()
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		return clone(*record), err
	}
	return clone(*record), nil
}

func (s *Store) ResolveUnknown(commandID string, result json.RawMessage) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	if record.Status == StatusSucceeded {
		return clone(*record), nil
	}
	if record.Status != StatusUnknown {
		return clone(*record), errors.New("command is not awaiting reconciliation")
	}
	previous := clone(*record)
	record.Status = StatusSucceeded
	record.ResultJSON = append(json.RawMessage(nil), result...)
	record.LastError = ""
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		*record = previous
		return Record{}, err
	}
	return clone(*record), nil
}

func (s *Store) RecordsByTypeStatus(commandType string, status Status) []Record {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]Record, 0)
	for _, record := range s.data.Records {
		if record.Type == commandType && record.Status == status {
			result = append(result, clone(*record))
		}
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].DispatchOrder != result[j].DispatchOrder {
			return result[i].DispatchOrder < result[j].DispatchOrder
		}
		if result[i].CreatedAt != result[j].CreatedAt {
			return result[i].CreatedAt < result[j].CreatedAt
		}
		return result[i].CommandID < result[j].CommandID
	})
	return result
}

func (s *Store) AttachResult(commandID, resultEventID string, result json.RawMessage) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Records[commandID]
	if record == nil {
		return Record{}, errors.New("command not found")
	}
	if resultEventID == "" {
		return Record{}, errors.New("result event id is required")
	}
	if record.ResultEventID != "" {
		return clone(*record), nil
	}
	previous := clone(*record)
	record.ResultEventID = resultEventID
	record.ResultJSON = append(json.RawMessage(nil), result...)
	record.UpdatedAt = time.Now().UnixMilli()
	if err := s.saveLocked(); err != nil {
		*record = previous
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
	record.ContextJSON = append(json.RawMessage(nil), record.ContextJSON...)
	return record
}
