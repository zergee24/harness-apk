package completion

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/harnessapk/remote/internal/workspace"
)

type TestStatus string

const (
	TestPassed     TestStatus = "PASSED"
	TestFailed     TestStatus = "FAILED"
	TestUnverified TestStatus = "UNVERIFIED"
)

type GitState string

const (
	GitCommitted   GitState = "COMMITTED"
	GitUncommitted GitState = "UNCOMMITTED"
	GitClean       GitState = "CLEAN"
)

type ChangedFileEvidence struct {
	EvidenceID     string `json:"evidenceId,omitempty"`
	EvidenceSHA256 string `json:"evidenceSha256,omitempty"`
	Path           string `json:"path"`
	Source         string `json:"source"`
}

type TestEvidence struct {
	EvidenceID     string     `json:"evidenceId,omitempty"`
	EvidenceSHA256 string     `json:"evidenceSha256,omitempty"`
	Command        string     `json:"command"`
	Status         TestStatus `json:"status"`
	ExitCode       *int       `json:"exitCode,omitempty"`
}

type GitEvidence struct {
	State      GitState `json:"state"`
	Branch     string   `json:"branch,omitempty"`
	BeforeHead string   `json:"beforeHead,omitempty"`
	AfterHead  string   `json:"afterHead,omitempty"`
}

type WorkspaceLocator struct {
	WorkspaceID           string `json:"workspaceId,omitempty"`
	RepositoryFingerprint string `json:"repositoryFingerprint,omitempty"`
	CWD                   string `json:"cwd,omitempty"`
}

type RunCompletion struct {
	SchemaVersion int                   `json:"schemaVersion,omitempty"`
	CompletionID  string                `json:"completionId,omitempty"`
	Summary       string                `json:"summary"`
	ChangedFiles  []ChangedFileEvidence `json:"changedFiles"`
	Tests         []TestEvidence        `json:"tests"`
	Git           *GitEvidence          `json:"git,omitempty"`
	Unresolved    []string              `json:"unresolved"`
	CompletedAt   int64                 `json:"completedAt"`
	Workspace     WorkspaceLocator      `json:"workspace,omitempty"`
}

type Input struct {
	RunID            string
	Workspace        WorkspaceLocator
	Before           workspace.Baseline
	After            workspace.Baseline
	CommittedFiles   []string
	ObservedFiles    []string
	Items            []json.RawMessage
	StructuredOutput json.RawMessage
	LastAgentMessage string
	CompletedAt      int64
}

func Decode(raw json.RawMessage) (RunCompletion, bool, error) {
	var result RunCompletion
	if err := json.Unmarshal(raw, &result); err != nil {
		return RunCompletion{}, false, err
	}
	switch result.SchemaVersion {
	case 0, 1:
		return result, true, nil
	case 2:
		return result, false, nil
	default:
		return RunCompletion{}, false, fmt.Errorf("unsupported completion schema version %d", result.SchemaVersion)
	}
}

func Build(input Input) RunCompletion {
	files := map[string]string{}
	for _, path := range input.CommittedFiles {
		if path = strings.TrimSpace(path); path != "" {
			files[path] = "git"
		}
	}
	for _, path := range input.ObservedFiles {
		if path = strings.TrimSpace(path); path != "" {
			if _, committed := files[path]; !committed {
				files[path] = "git-status"
			}
		}
	}
	tests := make([]TestEvidence, 0)
	for _, raw := range input.Items {
		var item map[string]any
		if json.Unmarshal(raw, &item) != nil {
			continue
		}
		switch item["type"] {
		case "fileChange":
			collectChangedFiles(files, item["changes"])
		case "commandExecution":
			command := commandText(item["command"])
			if !isKnownTestCommand(command) {
				continue
			}
			exitCode := integerPointer(item["exitCode"])
			status := TestUnverified
			if exitCode != nil {
				if *exitCode == 0 {
					status = TestPassed
				} else {
					status = TestFailed
				}
			}
			test := TestEvidence{Command: command, Status: status, ExitCode: exitCode}
			test.EvidenceID, test.EvidenceSHA256 = evidenceIdentity(input.RunID, "test", test)
			tests = append(tests, test)
		}
	}
	paths := make([]string, 0, len(files))
	for path := range files {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	changed := make([]ChangedFileEvidence, 0, len(paths))
	for _, path := range paths {
		file := ChangedFileEvidence{Path: path, Source: files[path]}
		file.EvidenceID, file.EvidenceSHA256 = evidenceIdentity(input.RunID, "file", file)
		changed = append(changed, file)
	}

	summary, unresolved := structuredSummary(input.StructuredOutput)
	if summary == "" {
		summary = strings.TrimSpace(input.LastAgentMessage)
	}
	if summary == "" {
		summary = "任务已完成"
	}
	if unresolved == nil {
		unresolved = []string{"未提供结构化遗留项"}
	}
	result := RunCompletion{
		SchemaVersion: 2,
		Summary:       summary, ChangedFiles: changed, Tests: tests,
		Git: gitEvidence(input.Before, input.After), Unresolved: unresolved,
		CompletedAt: input.CompletedAt, Workspace: input.Workspace,
	}
	result.CompletionID, _ = evidenceIdentity(input.RunID, "completion", result)
	return result
}

func evidenceIdentity(runID, kind string, value any) (string, string) {
	raw, _ := json.Marshal(value)
	contentDigest := sha256.Sum256(raw)
	contentHash := hex.EncodeToString(contentDigest[:])
	digest := sha256.New()
	_, _ = digest.Write([]byte(runID))
	_, _ = digest.Write([]byte{0})
	_, _ = digest.Write([]byte(kind))
	_, _ = digest.Write([]byte{0})
	_, _ = digest.Write(raw)
	identityHash := hex.EncodeToString(digest.Sum(nil))
	return kind + "-" + identityHash[:24], contentHash
}

func collectChangedFiles(files map[string]string, changes any) {
	switch current := changes.(type) {
	case []any:
		for _, change := range current {
			collectChangedFiles(files, change)
		}
	case map[string]any:
		for _, key := range []string{"path", "file", "filePath"} {
			if path, ok := current[key].(string); ok && strings.TrimSpace(path) != "" {
				files[path] = "fileChange"
				return
			}
		}
		for path := range current {
			if strings.TrimSpace(path) != "" {
				files[path] = "fileChange"
			}
		}
	case string:
		if strings.TrimSpace(current) != "" {
			files[current] = "fileChange"
		}
	}
}

func commandText(value any) string {
	switch command := value.(type) {
	case string:
		return strings.TrimSpace(command)
	case []any:
		parts := make([]string, 0, len(command))
		for _, part := range command {
			parts = append(parts, strings.TrimSpace(commandText(part)))
		}
		return strings.TrimSpace(strings.Join(parts, " "))
	default:
		raw, _ := json.Marshal(value)
		return strings.TrimSpace(string(raw))
	}
}

func isKnownTestCommand(command string) bool {
	normalized := strings.ToLower(strings.TrimSpace(command))
	return strings.Contains(normalized, "gradlew test") ||
		strings.Contains(normalized, "gradlew connected") ||
		strings.HasPrefix(normalized, "go test") ||
		strings.Contains(normalized, " go test") ||
		strings.HasPrefix(normalized, "pytest") ||
		strings.Contains(normalized, " pytest") ||
		strings.Contains(normalized, "jest") || strings.Contains(normalized, "vitest") ||
		strings.Contains(normalized, "cargo test") || strings.Contains(normalized, "swift test") ||
		(strings.Contains(normalized, "xcodebuild") && strings.Contains(normalized, " test"))
}

func integerPointer(value any) *int {
	number, ok := value.(float64)
	if !ok {
		return nil
	}
	integer := int(number)
	return &integer
}

func structuredSummary(raw json.RawMessage) (string, []string) {
	if len(raw) == 0 {
		return "", nil
	}
	var output struct {
		Summary    string   `json:"summary"`
		Unresolved []string `json:"unresolved"`
	}
	if json.Unmarshal(raw, &output) != nil || strings.TrimSpace(output.Summary) == "" {
		return "", nil
	}
	if output.Unresolved == nil {
		output.Unresolved = []string{}
	}
	return strings.TrimSpace(output.Summary), output.Unresolved
}

func gitEvidence(before, after workspace.Baseline) *GitEvidence {
	if !before.IsGit && !after.IsGit {
		return nil
	}
	state := GitClean
	if before.Head != "" && after.Head != "" && before.Head != after.Head {
		state = GitCommitted
	} else if len(after.PorcelainV2Z) > 0 {
		state = GitUncommitted
	}
	return &GitEvidence{
		State: state, Branch: after.Branch,
		BeforeHead: before.Head, AfterHead: after.Head,
	}
}
