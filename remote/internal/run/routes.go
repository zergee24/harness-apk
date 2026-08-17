package run

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"

	"github.com/harnessapk/remote/internal/protocol"
)

type ApprovalStatus string

const (
	ApprovalPending  ApprovalStatus = "PENDING"
	ApprovalStale    ApprovalStatus = "STALE"
	ApprovalResolved ApprovalStatus = "RESOLVED"
)

type Route struct {
	RunID        string `json:"runId"`
	BindingID    string `json:"bindingId"`
	WorkspaceID  string `json:"workspaceId"`
	HostID       string `json:"hostId"`
	DeviceID     string `json:"deviceId"`
	ThreadID     string `json:"threadId"`
	TurnID       string `json:"turnId"`
	BackendID    string `json:"backendId,omitempty"`
	BaselineJSON string `json:"baselineJson,omitempty"`
}

type WorkspaceBaseline struct {
	CWD                   string   `json:"cwd"`
	RepositoryFingerprint string   `json:"repositoryFingerprint"`
	IsGit                 bool     `json:"isGit"`
	Head                  string   `json:"head,omitempty"`
	Branch                string   `json:"branch,omitempty"`
	PorcelainV2Z          []string `json:"porcelainV2Z,omitempty"`
	CapturedAt            int64    `json:"capturedAt"`
}

type Approval struct {
	ApprovalID      string          `json:"approvalId"`
	RunID           string          `json:"runId"`
	BackendID       string          `json:"backendId,omitempty"`
	ProcessEpoch    string          `json:"processEpoch"`
	ServerRequestID json.RawMessage `json:"serverRequestId"`
	Method          string          `json:"method,omitempty"`
	ItemID          string          `json:"itemId,omitempty"`
	ActionType      string          `json:"actionType,omitempty"`
	Target          string          `json:"target,omitempty"`
	CommandPreview  string          `json:"commandPreview,omitempty"`
	DetailsJSON     string          `json:"detailsJson,omitempty"`
	Risk            string          `json:"risk,omitempty"`
	RequestedAt     int64           `json:"requestedAt,omitempty"`
	Status          ApprovalStatus  `json:"status"`
}

type routesData struct {
	SchemaVersion int                  `json:"schemaVersion"`
	ProcessEpoch  string               `json:"processEpoch,omitempty"`
	ProcessEpochs map[string]string    `json:"processEpochs,omitempty"`
	Routes        map[string]*Route    `json:"routes"`
	Approvals     map[string]*Approval `json:"approvals"`
}

type RouteStore struct {
	mu   sync.Mutex
	path string
	data routesData
}

func OpenRoutes(path string) (*RouteStore, error) {
	store := &RouteStore{path: path, data: routesData{
		SchemaVersion: 2, ProcessEpochs: map[string]string{},
		Routes: map[string]*Route{}, Approvals: map[string]*Approval{},
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
		return nil, errors.New("unsupported route store schema")
	}
	if store.data.ProcessEpochs == nil {
		store.data.ProcessEpochs = map[string]string{}
	}
	if store.data.SchemaVersion == 1 {
		// Legacy: single host-level epoch and routes keyed by runID.
		if store.data.ProcessEpoch != "" {
			store.data.ProcessEpochs[protocol.DefaultBackendID] = store.data.ProcessEpoch
		}
	}
	store.data.SchemaVersion = 2
	normalizeRouteStoreData(&store.data)
	if store.data.Routes == nil {
		store.data.Routes = map[string]*Route{}
	}
	if store.data.Approvals == nil {
		store.data.Approvals = map[string]*Approval{}
	}
	return store, nil
}

// normalizeRouteStoreData rekeys legacy routes (keyed by runID with an empty
// BackendID) onto composite (backendID, runID) keys and canonicalizes empty
// backend ids to the default backend.
func normalizeRouteStoreData(data *routesData) {
	normalized := make(map[string]*Route, len(data.Routes))
	for _, route := range data.Routes {
		route.BackendID = normalizeBackend(route.BackendID)
		normalized[routeKey(route.BackendID, route.RunID)] = route
	}
	data.Routes = normalized
	for _, approval := range data.Approvals {
		approval.BackendID = normalizeBackend(approval.BackendID)
	}
}

// routeKey is the storage key for one run's route: (backendID, runID).
func routeKey(backendID, runID string) string {
	return backendID + "\x00" + runID
}

// normalizeBackend maps the legacy empty backend id to the default backend.
func normalizeBackend(id string) string {
	if id == "" {
		return protocol.DefaultBackendID
	}
	return id
}

// findRouteByRun scans the route map for the route whose RunID matches.
func findRouteByRun(routes map[string]*Route, runID string) (*Route, bool) {
	for _, route := range routes {
		if route.RunID == runID {
			return route, true
		}
	}
	return nil, false
}

// BeginProcessEpoch records the process epoch of one backend and marks that
// backend's pending approvals stale when the epoch changes. Other backends'
// approvals are untouched, so one backend restart cannot invalidate the other.
func (s *RouteStore) BeginProcessEpoch(backendID, epoch string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if epoch == "" {
		return errors.New("process epoch is required")
	}
	backendID = normalizeBackend(backendID)
	if s.data.ProcessEpochs[backendID] == epoch {
		return nil
	}
	for _, approval := range s.data.Approvals {
		if approval.Status == ApprovalPending && approval.BackendID == backendID && approval.ProcessEpoch != epoch {
			approval.Status = ApprovalStale
		}
	}
	s.data.ProcessEpochs[backendID] = epoch
	return s.saveLocked()
}

func (s *RouteStore) Put(route Route) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if route.RunID == "" || route.HostID == "" || route.DeviceID == "" {
		return errors.New("route stable identity is required")
	}
	route.BackendID = normalizeBackend(route.BackendID)
	copy := route
	s.data.Routes[routeKey(route.BackendID, route.RunID)] = &copy
	return s.saveLocked()
}

func (s *RouteStore) AdvanceTurn(runID, threadID, expectedTurnID, turnID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if runID == "" || threadID == "" || expectedTurnID == "" || turnID == "" {
		return errors.New("run, thread, expected turn, and next turn identity are required")
	}
	route, ok := findRouteByRun(s.data.Routes, runID)
	if !ok {
		return errors.New("route not found")
	}
	if route.ThreadID != threadID {
		return errors.New("route thread identity changed")
	}
	if route.TurnID == turnID {
		return nil
	}
	if route.TurnID != expectedTurnID {
		return errors.New("route expected turn identity changed")
	}
	updated := *route
	updated.TurnID = turnID
	s.data.Routes[routeKey(updated.BackendID, runID)] = &updated
	if err := s.saveLocked(); err != nil {
		s.data.Routes[routeKey(route.BackendID, runID)] = route
		return err
	}
	return nil
}

func (s *RouteStore) UpdateTurn(runID, threadID, turnID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if runID == "" || threadID == "" || turnID == "" {
		return errors.New("run, thread, and turn identity are required")
	}
	route, ok := findRouteByRun(s.data.Routes, runID)
	if !ok {
		return errors.New("route not found")
	}
	if route.ThreadID != threadID {
		return errors.New("route thread identity changed")
	}
	if route.TurnID != "" && route.TurnID != turnID {
		return errors.New("route turn identity changed")
	}
	if route.TurnID == turnID {
		return nil
	}
	updated := *route
	updated.TurnID = turnID
	s.data.Routes[routeKey(updated.BackendID, runID)] = &updated
	if err := s.saveLocked(); err != nil {
		s.data.Routes[routeKey(route.BackendID, runID)] = route
		return err
	}
	return nil
}

func (s *RouteStore) ByRun(runID string) (Route, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	route, ok := findRouteByRun(s.data.Routes, runID)
	if !ok {
		return Route{}, false
	}
	return *route, true
}

func (s *RouteStore) ByThread(threadID string) (Route, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, route := range s.data.Routes {
		if route.ThreadID == threadID {
			return *route, true
		}
	}
	return Route{}, false
}

// ByThreadBackend resolves the route for one thread scoped to one backend.
// Events arriving from a backend are only routed to a run that backend owns.
func (s *RouteStore) ByThreadBackend(threadID, backendID string) (Route, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	backendID = normalizeBackend(backendID)
	for _, route := range s.data.Routes {
		if route.ThreadID == threadID && route.BackendID == backendID {
			return *route, true
		}
	}
	return Route{}, false
}

func (s *RouteStore) ByThreadTurn(threadID, turnID string) (Route, bool) {
	return s.ByThreadTurnBackend(threadID, turnID, "")
}

// ByThreadTurnBackend resolves the route for one (thread, turn) scoped to one
// backend; an empty backendID accepts any backend (legacy callers).
func (s *RouteStore) ByThreadTurnBackend(threadID, turnID, backendID string) (Route, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if threadID == "" || turnID == "" {
		return Route{}, false
	}
	backendID = normalizeBackend(backendID)
	var matched *Route
	for _, route := range s.data.Routes {
		if route.ThreadID != threadID || route.TurnID != turnID {
			continue
		}
		if backendID != "" && route.BackendID != backendID {
			continue
		}
		if matched != nil {
			return Route{}, false
		}
		copy := *route
		matched = &copy
	}
	if matched != nil {
		return *matched, true
	}
	return Route{}, false
}

// ByThreadAllBackend returns all non-terminal-candidate routes for one thread
// on one backend (an empty backendID accepts any backend).
func (s *RouteStore) ByThreadAllBackend(threadID, backendID string) []Route {
	s.mu.Lock()
	defer s.mu.Unlock()
	backendID = normalizeBackend(backendID)
	result := make([]Route, 0)
	for _, route := range s.data.Routes {
		if route.ThreadID != threadID {
			continue
		}
		if backendID != "" && route.BackendID != backendID {
			continue
		}
		result = append(result, *route)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].RunID < result[j].RunID })
	return result
}

func (s *RouteStore) ByThreadAll(threadID string) []Route {
	return s.ByThreadAllBackend(threadID, "")
}

func (s *RouteStore) ByRuns(runIDs []string) []Route {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]Route, 0, len(runIDs))
	for _, runID := range runIDs {
		if route, ok := findRouteByRun(s.data.Routes, runID); ok {
			result = append(result, *route)
		}
	}
	return result
}

func (s *RouteStore) ApprovalsForRuns(runIDs []string) []Approval {
	s.mu.Lock()
	defer s.mu.Unlock()
	wanted := make(map[string]struct{}, len(runIDs))
	for _, runID := range runIDs {
		wanted[runID] = struct{}{}
	}
	result := make([]Approval, 0)
	for _, approval := range s.data.Approvals {
		if _, ok := wanted[approval.RunID]; ok {
			result = append(result, cloneApproval(*approval))
		}
	}
	return result
}

func (s *RouteStore) PutApproval(approval Approval) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if approval.ApprovalID == "" || approval.RunID == "" || approval.ProcessEpoch == "" || len(approval.ServerRequestID) == 0 {
		return errors.New("approval stable identity is required")
	}
	approval.BackendID = normalizeBackend(approval.BackendID)
	copy := cloneApproval(approval)
	s.data.Approvals[approval.ApprovalID] = &copy
	return s.saveLocked()
}

func (s *RouteStore) Approval(approvalID string) (Approval, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	approval := s.data.Approvals[approvalID]
	if approval == nil {
		return Approval{}, false
	}
	return cloneApproval(*approval), true
}

// MarkServerRequestResolved marks the pending approval matching one backend's
// epoch and server request id stale (resolved elsewhere).
func (s *RouteStore) MarkServerRequestResolved(backendID, epoch string, requestID json.RawMessage) (Approval, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	backendID = normalizeBackend(backendID)
	for _, approval := range s.data.Approvals {
		if approval.Status == ApprovalPending && approval.BackendID == backendID &&
			approval.ProcessEpoch == epoch && sameJSON(approval.ServerRequestID, requestID) {
			approval.Status = ApprovalStale
			if err := s.saveLocked(); err != nil {
				return Approval{}, false, err
			}
			return cloneApproval(*approval), true, nil
		}
	}
	return Approval{}, false, nil
}

func (s *RouteStore) ValidateResponse(approvalID, epoch string, requestID json.RawMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	approval := s.data.Approvals[approvalID]
	if approval == nil {
		return errors.New("approval not found")
	}
	current := s.data.ProcessEpochs[approval.BackendID]
	if current != epoch || approval.ProcessEpoch != epoch {
		return errors.New("approval belongs to an expired process epoch")
	}
	if approval.Status != ApprovalPending {
		return errors.New("approval is no longer pending")
	}
	if !sameJSON(approval.ServerRequestID, requestID) {
		return errors.New("server request id does not match approval")
	}
	return nil
}

func (s *RouteStore) MarkApprovalResolved(approvalID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	approval := s.data.Approvals[approvalID]
	if approval == nil {
		return errors.New("approval not found")
	}
	approval.Status = ApprovalResolved
	return s.saveLocked()
}

func (s *RouteStore) saveLocked() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
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

func cloneApproval(approval Approval) Approval {
	approval.ServerRequestID = append(json.RawMessage(nil), approval.ServerRequestID...)
	return approval
}

func sameJSON(left, right json.RawMessage) bool {
	var leftValue, rightValue any
	if json.Unmarshal(left, &leftValue) != nil || json.Unmarshal(right, &rightValue) != nil {
		return bytes.Equal(bytes.TrimSpace(left), bytes.TrimSpace(right))
	}
	leftCanonical, _ := json.Marshal(leftValue)
	rightCanonical, _ := json.Marshal(rightValue)
	return bytes.Equal(leftCanonical, rightCanonical)
}
