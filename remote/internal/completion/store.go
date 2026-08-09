package completion

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

var ErrTerminalRunConflict = errors.New("terminal run is already frozen with different evidence")

type TerminalRunRecord struct {
	RunID            string           `json:"runId"`
	Status           string           `json:"status"`
	CompletionJSON   json.RawMessage  `json:"completion,omitempty"`
	CompletionSHA256 string           `json:"completionSha256,omitempty"`
	CompletedAt      int64            `json:"completedAt"`
	Workspace        WorkspaceLocator `json:"workspace,omitempty"`
}

type terminalRunData struct {
	SchemaVersion int                           `json:"schemaVersion"`
	Runs          map[string]*TerminalRunRecord `json:"runs"`
}

type TerminalRunStore struct {
	mu   sync.Mutex
	path string
	data terminalRunData
}

func OpenTerminalRunStore(path string) (*TerminalRunStore, error) {
	store := &TerminalRunStore{path: path, data: terminalRunData{
		SchemaVersion: 1,
		Runs:          map[string]*TerminalRunRecord{},
	}}
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, &store.data); err != nil {
		return nil, err
	}
	if store.data.SchemaVersion != 1 {
		return nil, errors.New("unsupported terminal run store schema")
	}
	if store.data.Runs == nil {
		store.data.Runs = map[string]*TerminalRunRecord{}
	}
	for runID, record := range store.data.Runs {
		if record == nil || record.RunID != runID {
			return nil, errors.New("terminal run ledger identity mismatch")
		}
		if err := validateFrozenRecord(*record); err != nil {
			return nil, err
		}
	}
	return store, nil
}

func validateFrozenRecord(record TerminalRunRecord) error {
	if record.RunID == "" || record.Status == "" || record.CompletedAt <= 0 {
		return errors.New("terminal run identity, status, and completion time are required")
	}
	if len(record.CompletionJSON) == 0 {
		if record.CompletionSHA256 != "" {
			return errors.New("terminal run completion hash has no completion")
		}
		return nil
	}
	if !json.Valid(record.CompletionJSON) {
		return errors.New("completion is not valid JSON")
	}
	digest := sha256.Sum256(record.CompletionJSON)
	if record.CompletionSHA256 != hex.EncodeToString(digest[:]) {
		return errors.New("terminal run completion hash mismatch")
	}
	return nil
}

func (s *TerminalRunStore) Freeze(record TerminalRunRecord) (TerminalRunRecord, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if record.RunID == "" || record.Status == "" || record.CompletedAt <= 0 {
		return TerminalRunRecord{}, false, errors.New("terminal run identity, status, and completion time are required")
	}
	if len(record.CompletionJSON) > 0 {
		if !json.Valid(record.CompletionJSON) {
			return TerminalRunRecord{}, false, errors.New("completion is not valid JSON")
		}
		var compact bytes.Buffer
		if err := json.Compact(&compact, record.CompletionJSON); err != nil {
			return TerminalRunRecord{}, false, err
		}
		record.CompletionJSON = append(json.RawMessage(nil), compact.Bytes()...)
		digest := sha256.Sum256(record.CompletionJSON)
		record.CompletionSHA256 = hex.EncodeToString(digest[:])
	}
	if existing := s.data.Runs[record.RunID]; existing != nil {
		if sameTerminalRecord(*existing, record) {
			return cloneTerminalRecord(*existing), false, nil
		}
		return cloneTerminalRecord(*existing), false, ErrTerminalRunConflict
	}
	copy := cloneTerminalRecord(record)
	s.data.Runs[record.RunID] = &copy
	if err := s.saveLocked(); err != nil {
		delete(s.data.Runs, record.RunID)
		return TerminalRunRecord{}, false, err
	}
	return cloneTerminalRecord(copy), true, nil
}

func (s *TerminalRunStore) Lookup(runID string) (TerminalRunRecord, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.data.Runs[runID]
	if record == nil {
		return TerminalRunRecord{}, false
	}
	return cloneTerminalRecord(*record), true
}

func (s *TerminalRunStore) saveLocked() error {
	raw, err := json.Marshal(s.data)
	if err != nil {
		return err
	}
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.OpenFile(s.path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
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
	if err := os.Rename(s.path+".tmp", s.path); err != nil {
		return err
	}
	if directory, err := os.Open(dir); err == nil {
		defer directory.Close()
		return directory.Sync()
	}
	return nil
}

func sameTerminalRecord(left, right TerminalRunRecord) bool {
	return left.RunID == right.RunID && left.Status == right.Status &&
		left.CompletedAt == right.CompletedAt && left.CompletionSHA256 == right.CompletionSHA256 &&
		left.Workspace == right.Workspace && bytes.Equal(left.CompletionJSON, right.CompletionJSON)
}

func cloneTerminalRecord(record TerminalRunRecord) TerminalRunRecord {
	record.CompletionJSON = append(json.RawMessage(nil), record.CompletionJSON...)
	return record
}
