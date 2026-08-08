package completion

import (
	"encoding/json"
	"testing"

	"github.com/harnessapk/remote/internal/workspace"
)

func TestAgentClaimDoesNotBecomePassedTestEvidence(t *testing.T) {
	result := Build(Input{
		Items:            []json.RawMessage{json.RawMessage(`{"type":"agentMessage","text":"All tests passed: 42 passed"}`)},
		LastAgentMessage: "All tests passed: 42 passed",
	})

	if len(result.Tests) != 0 {
		t.Fatalf("agent prose became test evidence: %#v", result.Tests)
	}
}

func TestKnownTestCommandRequiresZeroExitCode(t *testing.T) {
	result := Build(Input{Items: []json.RawMessage{
		json.RawMessage(`{"type":"commandExecution","command":"./gradlew test","status":"completed","exitCode":0}`),
		json.RawMessage(`{"type":"commandExecution","command":"go test ./...","status":"completed","exitCode":1}`),
		json.RawMessage(`{"type":"commandExecution","command":"pytest","status":"completed"}`),
	}})

	if len(result.Tests) != 3 || result.Tests[0].Status != TestPassed ||
		result.Tests[1].Status != TestFailed || result.Tests[2].Status != TestUnverified {
		t.Fatalf("tests=%#v", result.Tests)
	}
}

func TestCommittedChangesRemainVisibleWhenWorkingTreeIsClean(t *testing.T) {
	result := Build(Input{
		Before:         workspace.Baseline{IsGit: true, Head: "old", Branch: "test"},
		After:          workspace.Baseline{IsGit: true, Head: "new", Branch: "test"},
		CommittedFiles: []string{"app/src/main/App.kt"},
	})

	if len(result.ChangedFiles) != 1 || result.ChangedFiles[0].Path != "app/src/main/App.kt" {
		t.Fatalf("files=%#v", result.ChangedFiles)
	}
	if result.Git == nil || result.Git.State != GitCommitted || result.Git.AfterHead != "new" {
		t.Fatalf("git=%#v", result.Git)
	}
}
