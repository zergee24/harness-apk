package completion

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
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

type TerminalObservation struct {
	RunID      string          `json:"runId"`
	Params     json.RawMessage `json:"params,omitempty"`
	ObservedAt int64           `json:"observedAt"`
	Attempts   int             `json:"attempts,omitempty"`
	LastError  string          `json:"lastError,omitempty"`
}

type terminalRunData struct {
	SchemaVersion int                             `json:"schemaVersion"`
	Runs          map[string]*TerminalRunRecord   `json:"runs"`
	Observations  map[string]*TerminalObservation `json:"observations,omitempty"`
	Journaled     map[string]string               `json:"journaled,omitempty"`
}

type TerminalRunStore struct {
	mu            sync.Mutex
	path          string
	data          terminalRunData
	syncDirectory func(string) error
	writeFault    error
}

func OpenTerminalRunStore(path string) (*TerminalRunStore, error) {
	return openTerminalRunStore(path, syncTerminalDirectory)
}

func openTerminalRunStore(path string, syncDirectory func(string) error) (*TerminalRunStore, error) {
	store := &TerminalRunStore{path: path, syncDirectory: syncDirectory, data: terminalRunData{
		SchemaVersion: 2,
		Runs:          map[string]*TerminalRunRecord{},
		Observations:  map[string]*TerminalObservation{},
		Journaled:     map[string]string{},
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
	if store.data.SchemaVersion != 1 && store.data.SchemaVersion != 2 {
		return nil, errors.New("unsupported terminal run store schema")
	}
	store.data.SchemaVersion = 2
	if store.data.Runs == nil {
		store.data.Runs = map[string]*TerminalRunRecord{}
	}
	if store.data.Observations == nil {
		store.data.Observations = map[string]*TerminalObservation{}
	}
	if store.data.Journaled == nil {
		store.data.Journaled = map[string]string{}
	}
	for runID, record := range store.data.Runs {
		if record == nil || record.RunID != runID {
			return nil, errors.New("terminal run ledger identity mismatch")
		}
		if err := validateFrozenRecord(*record); err != nil {
			return nil, err
		}
	}
	for runID, observation := range store.data.Observations {
		if observation == nil || observation.RunID != runID || observation.ObservedAt <= 0 ||
			(len(observation.Params) > 0 && !json.Valid(observation.Params)) {
			return nil, errors.New("terminal observation is invalid")
		}
	}
	return store, nil
}

func validateFrozenRecord(record TerminalRunRecord) error {
	if record.RunID == "" || !isTerminalStatus(record.Status) || record.CompletedAt <= 0 {
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
	if record.RunID == "" || !isTerminalStatus(record.Status) || record.CompletedAt <= 0 {
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
	if s.writeFault != nil {
		return TerminalRunRecord{}, false, errors.Join(errors.New("terminal run store is read-only after uncertain persistence"), s.writeFault)
	}
	copy := cloneTerminalRecord(record)
	previousObservation := s.data.Observations[record.RunID]
	s.data.Runs[record.RunID] = &copy
	delete(s.data.Observations, record.RunID)
	committed, err := s.saveLocked()
	if err != nil {
		if committed {
			s.writeFault = err
			return cloneTerminalRecord(copy), false, err
		}
		delete(s.data.Runs, record.RunID)
		if previousObservation != nil {
			s.data.Observations[record.RunID] = previousObservation
		}
		return TerminalRunRecord{}, false, err
	}
	return cloneTerminalRecord(copy), true, nil
}

func isTerminalStatus(status string) bool {
	return status == "COMPLETED" || status == "FAILED" || status == "CANCELLED"
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

func (s *TerminalRunStore) Observe(observation TerminalObservation) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if observation.RunID == "" || observation.ObservedAt <= 0 ||
		(len(observation.Params) > 0 && !json.Valid(observation.Params)) {
		return errors.New("terminal observation identity, time, and params are required")
	}
	if s.data.Runs[observation.RunID] != nil {
		return nil
	}
	if s.writeFault != nil {
		return errors.Join(errors.New("terminal run store is read-only after uncertain persistence"), s.writeFault)
	}
	previous := s.data.Observations[observation.RunID]
	copy := cloneTerminalObservation(observation)
	if previous != nil {
		copy.ObservedAt = previous.ObservedAt
		copy.Attempts = previous.Attempts
		copy.LastError = previous.LastError
		if len(previous.Params) > 0 {
			copy.Params = append(json.RawMessage(nil), previous.Params...)
		}
	}
	s.data.Observations[observation.RunID] = &copy
	committed, err := s.saveLocked()
	if err != nil {
		if committed {
			s.writeFault = err
			return err
		}
		if previous == nil {
			delete(s.data.Observations, observation.RunID)
		} else {
			s.data.Observations[observation.RunID] = previous
		}
	}
	return err
}

func (s *TerminalRunStore) RecordObservationFailure(runID, message string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	observation := s.data.Observations[runID]
	if observation == nil {
		return nil
	}
	if s.writeFault != nil {
		return errors.Join(errors.New("terminal run store is read-only after uncertain persistence"), s.writeFault)
	}
	previous := cloneTerminalObservation(*observation)
	observation.Attempts++
	observation.LastError = message
	committed, err := s.saveLocked()
	if err != nil && !committed {
		*observation = previous
	}
	if err != nil && committed {
		s.writeFault = err
	}
	return err
}

func (s *TerminalRunStore) PendingObservations() []TerminalObservation {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]TerminalObservation, 0, len(s.data.Observations))
	for _, observation := range s.data.Observations {
		result = append(result, cloneTerminalObservation(*observation))
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].ObservedAt == result[j].ObservedAt {
			return result[i].RunID < result[j].RunID
		}
		return result[i].ObservedAt < result[j].ObservedAt
	})
	return result
}

func (s *TerminalRunStore) PendingJournalRecords() []TerminalRunRecord {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]TerminalRunRecord, 0, len(s.data.Runs))
	for runID, record := range s.data.Runs {
		if s.data.Journaled[runID] == "" {
			result = append(result, cloneTerminalRecord(*record))
		}
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].CompletedAt == result[j].CompletedAt {
			return result[i].RunID < result[j].RunID
		}
		return result[i].CompletedAt < result[j].CompletedAt
	})
	return result
}

func (s *TerminalRunStore) MarkJournaled(runID, eventID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data.Runs[runID] == nil || eventID == "" {
		return errors.New("frozen terminal run and event id are required")
	}
	if existing := s.data.Journaled[runID]; existing != "" {
		if existing != eventID {
			return errors.New("terminal run was journaled with a different event id")
		}
		return nil
	}
	if s.writeFault != nil {
		return errors.Join(errors.New("terminal run store is read-only after uncertain persistence"), s.writeFault)
	}
	s.data.Journaled[runID] = eventID
	committed, err := s.saveLocked()
	if err != nil && !committed {
		delete(s.data.Journaled, runID)
	}
	if err != nil && committed {
		s.writeFault = err
	}
	return err
}

func (s *TerminalRunStore) saveLocked() (bool, error) {
	raw, err := json.Marshal(s.data)
	if err != nil {
		return false, err
	}
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return false, err
	}
	tmp, err := os.OpenFile(s.path+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return false, err
	}
	if _, err = tmp.Write(raw); err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err != nil {
		return false, err
	}
	if closeErr != nil {
		return false, closeErr
	}
	if err := os.Rename(s.path+".tmp", s.path); err != nil {
		return false, err
	}
	if err := s.syncDirectory(dir); err != nil {
		return true, err
	}
	return true, nil
}

func syncTerminalDirectory(dir string) error {
	directory, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
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

func cloneTerminalObservation(observation TerminalObservation) TerminalObservation {
	observation.Params = append(json.RawMessage(nil), observation.Params...)
	return observation
}
