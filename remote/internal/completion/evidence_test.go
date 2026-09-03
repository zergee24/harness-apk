package completion

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
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

func TestLegacyV1CompletionFixtureStillDecodes(t *testing.T) {
	raw, err := os.ReadFile("testdata/completion-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	legacy, isLegacy, err := Decode(raw)
	if err != nil {
		t.Fatal(err)
	}
	if !isLegacy || legacy.SchemaVersion != 0 || legacy.Summary != "M2 legacy completion" || len(legacy.ChangedFiles) != 1 {
		t.Fatalf("legacy completion = %#v", legacy)
	}
	if legacy.ChangedFiles[0].EvidenceID != "" || legacy.Workspace.WorkspaceID != "" {
		t.Fatalf("v2 additive fields unexpectedly required for v1: %#v", legacy)
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

func TestAndroidGradleTasksAreRecognizedAsTestEvidence(t *testing.T) {
	result := Build(Input{Items: []json.RawMessage{
		json.RawMessage(`{"type":"commandExecution","command":"./gradlew :app:testDebugUnitTest","exitCode":0}`),
		json.RawMessage(`{"type":"commandExecution","command":"ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest","exitCode":1}`),
		json.RawMessage(`{"type":"commandExecution","command":"./gradlew :app:assembleDebug","exitCode":0}`),
	}})

	if len(result.Tests) != 2 || result.Tests[0].Status != TestPassed || result.Tests[1].Status != TestFailed {
		t.Fatalf("android gradle tests=%#v", result.Tests)
	}
}

func TestGitInspectionFailureIsUnverifiedInsteadOfClean(t *testing.T) {
	result := Build(Input{
		Before:             workspace.Baseline{IsGit: true, Head: "before", Branch: "main"},
		GitInspectionError: "git status failed: permission denied",
	})

	if result.Git == nil || result.Git.State != GitUnverified || result.Git.Reason == "" {
		t.Fatalf("git evidence=%#v", result.Git)
	}
}

func TestMissingPostRunGitBaselineIsUnverifiedInsteadOfClean(t *testing.T) {
	result := Build(Input{
		Before: workspace.Baseline{IsGit: true, Head: "before", Branch: "main"},
		After:  workspace.Baseline{},
	})

	if result.Git == nil || result.Git.State != GitUnverified || result.Git.Reason == "" {
		t.Fatalf("git evidence=%#v", result.Git)
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

func TestCompletionV2AssignsStableEvidenceIdentityAndWorkspaceLocator(t *testing.T) {
	result := Build(Input{
		RunID: "run-1",
		Workspace: WorkspaceLocator{
			WorkspaceID:           "workspace-1",
			RepositoryFingerprint: "fingerprint-1",
			CWD:                   "/workspace/harness-apk",
		},
		ObservedFiles: []string{"docs/result.md"},
		Items: []json.RawMessage{
			json.RawMessage(`{"type":"commandExecution","command":"go test ./...","exitCode":0}`),
		},
		CompletedAt: 1234,
	})

	if result.SchemaVersion != 2 || result.CompletionID == "" {
		t.Fatalf("completion identity = %#v", result)
	}
	if result.Workspace.WorkspaceID != "workspace-1" || result.Workspace.CWD != "/workspace/harness-apk" {
		t.Fatalf("workspace locator = %#v", result.Workspace)
	}
	if len(result.ChangedFiles) != 1 || result.ChangedFiles[0].EvidenceID == "" || result.ChangedFiles[0].EvidenceSHA256 == "" {
		t.Fatalf("changed file evidence = %#v", result.ChangedFiles)
	}
	fileContent, _ := json.Marshal(struct {
		Path   string `json:"path"`
		Source string `json:"source"`
	}{Path: "docs/result.md", Source: "git-status"})
	fileDigest := sha256.Sum256(fileContent)
	if result.ChangedFiles[0].EvidenceSHA256 != hex.EncodeToString(fileDigest[:]) {
		t.Fatalf("file evidence hash does not verify content: %#v", result.ChangedFiles[0])
	}
	if len(result.Tests) != 1 || result.Tests[0].EvidenceID == "" || result.Tests[0].EvidenceSHA256 == "" {
		t.Fatalf("test evidence = %#v", result.Tests)
	}

	rebuilt := Build(Input{
		RunID:         "run-1",
		Workspace:     result.Workspace,
		ObservedFiles: []string{"docs/result.md"},
		Items: []json.RawMessage{
			json.RawMessage(`{"type":"commandExecution","command":"go test ./...","exitCode":0}`),
		},
		CompletedAt: 1234,
	})
	if rebuilt.CompletionID != result.CompletionID || rebuilt.ChangedFiles[0].EvidenceID != result.ChangedFiles[0].EvidenceID || rebuilt.Tests[0].EvidenceID != result.Tests[0].EvidenceID {
		t.Fatalf("completion evidence identity drifted: first=%#v rebuilt=%#v", result, rebuilt)
	}
}
