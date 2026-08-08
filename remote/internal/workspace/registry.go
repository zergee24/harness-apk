package workspace

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

type registryData struct {
	SchemaVersion int                             `json:"schemaVersion"`
	Devices       map[string]map[string]Candidate `json:"devices"`
}

type Registry struct {
	mu   sync.Mutex
	path string
	data registryData
}

func OpenRegistry(path string) (*Registry, error) {
	registry := &Registry{path: path, data: registryData{
		SchemaVersion: 1,
		Devices:       map[string]map[string]Candidate{},
	}}
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return registry, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, &registry.data); err != nil {
		return nil, err
	}
	if registry.data.SchemaVersion != 1 {
		return nil, errors.New("unsupported workspace registry schema")
	}
	if registry.data.Devices == nil {
		registry.data.Devices = map[string]map[string]Candidate{}
	}
	return registry, nil
}

func (r *Registry) PutCandidates(deviceID string, candidates []Candidate) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if deviceID == "" {
		return errors.New("device id is required")
	}
	next := make(map[string]Candidate, len(candidates))
	for _, candidate := range candidates {
		if candidate.WorkspaceID == "" || candidate.CWD == "" || candidate.RepositoryFingerprint == "" {
			return errors.New("workspace candidate stable identity is required")
		}
		next[candidate.WorkspaceID] = candidate
	}
	r.data.Devices[deviceID] = next
	return r.saveLocked()
}

func (r *Registry) Resolve(deviceID, workspaceID string) (Candidate, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	candidate, ok := r.data.Devices[deviceID][workspaceID]
	return candidate, ok
}

func (r *Registry) saveLocked() error {
	raw, err := json.MarshalIndent(r.data, "", "  ")
	if err != nil {
		return err
	}
	directory := filepath.Dir(r.path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary := r.path + ".tmp"
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	if _, err = file.Write(raw); err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Rename(temporary, r.path); err != nil {
		return err
	}
	if dir, err := os.Open(directory); err == nil {
		defer dir.Close()
		return dir.Sync()
	}
	return nil
}
