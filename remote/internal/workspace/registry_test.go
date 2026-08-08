package workspace

import (
	"path/filepath"
	"testing"
)

func TestWorkspaceRegistryIsDeviceScopedAndSurvivesRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "workspaces.json")
	registry, err := OpenRegistry(path)
	if err != nil {
		t.Fatal(err)
	}
	candidate := Candidate{
		WorkspaceID: "workspace-1", CWD: "/workspace",
		RepositoryFingerprint: "fingerprint-1",
	}
	if err := registry.PutCandidates("device-1", []Candidate{candidate}); err != nil {
		t.Fatal(err)
	}
	reopened, err := OpenRegistry(path)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := reopened.Resolve("device-1", "workspace-1"); !ok || got.RepositoryFingerprint != "fingerprint-1" {
		t.Fatalf("resolved=%#v ok=%v", got, ok)
	}
	if _, ok := reopened.Resolve("device-2", "workspace-1"); ok {
		t.Fatal("workspace registration leaked across devices")
	}
}
