package workspace

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestInspectCandidatesCanonicalizesSymlinkAndRedactsHTTPSRemote(t *testing.T) {
	repo := filepath.Join(t.TempDir(), "Harness Mobile")
	mustGit(t, "init", repo)
	mustGit(t, "-C", repo, "remote", "add", "origin", "https://token-user:secret-token@github.com/acme/harness.git?access_token=leak#fragment")
	link := filepath.Join(t.TempDir(), "linked-workspace")
	if err := os.Symlink(repo, link); err != nil {
		t.Fatal(err)
	}

	candidates, err := InspectCandidates([]byte("pairing-secret"), []Source{
		{CWD: link, LastUsedAt: 20},
		{CWD: repo, LastUsedAt: 10},
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 1 {
		t.Fatalf("got %d candidates, want one canonical candidate", len(candidates))
	}
	candidate := candidates[0]
	canonicalRepo, err := filepath.EvalSymlinks(repo)
	if err != nil {
		t.Fatal(err)
	}
	if candidate.CWD != canonicalRepo {
		t.Fatalf("cwd = %q, want %q", candidate.CWD, canonicalRepo)
	}
	if candidate.RepositoryLabel != "github.com/acme/harness" {
		t.Fatalf("repository label = %q", candidate.RepositoryLabel)
	}
	encoded := candidate.RepositoryLabel + candidate.RepositoryFingerprint + candidate.DisplayName
	for _, secret := range []string{"token-user", "secret-token", "access_token", "leak"} {
		if strings.Contains(encoded, secret) {
			t.Fatalf("candidate leaked %q: %s", secret, encoded)
		}
	}
}

func TestInspectCandidatesSanitizesSCPRemoteAndShowsDetachedHead(t *testing.T) {
	repo := filepath.Join(t.TempDir(), "repo")
	mustGit(t, "init", repo)
	mustGit(t, "-C", repo, "config", "user.email", "m2@example.invalid")
	mustGit(t, "-C", repo, "config", "user.name", "M2 Test")
	if err := os.WriteFile(filepath.Join(repo, "README.md"), []byte("m2\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	mustGit(t, "-C", repo, "add", "README.md")
	mustGit(t, "-C", repo, "commit", "-m", "fixture")
	mustGit(t, "-C", repo, "remote", "add", "origin", "git@github.com:acme/private-repo.git")
	head := strings.TrimSpace(mustGit(t, "-C", repo, "rev-parse", "--short=8", "HEAD"))
	mustGit(t, "-C", repo, "checkout", "--detach")

	candidate, err := Inspect([]byte("pairing-secret"), repo, 1)
	if err != nil {
		t.Fatal(err)
	}
	if candidate.RepositoryLabel != "github.com/acme/private-repo" {
		t.Fatalf("repository label = %q", candidate.RepositoryLabel)
	}
	if candidate.Branch != head {
		t.Fatalf("detached branch = %q, want %q", candidate.Branch, head)
	}
}

func TestInspectNonGitDirectoryUsesStableIdentity(t *testing.T) {
	directory := t.TempDir()
	first, err := Inspect([]byte("pairing-secret"), directory, 1)
	if err != nil {
		t.Fatal(err)
	}
	second, err := Inspect([]byte("pairing-secret"), directory, 2)
	if err != nil {
		t.Fatal(err)
	}
	if first.RepositoryFingerprint != second.RepositoryFingerprint || first.WorkspaceID != second.WorkspaceID {
		t.Fatal("same non-git directory did not keep stable identity")
	}
	if first.RepositoryLabel != "" || first.Branch != "" {
		t.Fatalf("non-git directory exposed repository metadata: %#v", first)
	}
}

func TestInspectCandidatesDropsMissingPathsAndSortsNewestFirst(t *testing.T) {
	older := filepath.Join(t.TempDir(), "older")
	newer := filepath.Join(t.TempDir(), "newer")
	if err := os.MkdirAll(older, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(newer, 0o700); err != nil {
		t.Fatal(err)
	}
	candidates, err := InspectCandidates([]byte("pairing-secret"), []Source{
		{CWD: older, LastUsedAt: 10},
		{CWD: filepath.Join(t.TempDir(), "missing"), LastUsedAt: 30},
		{CWD: newer, LastUsedAt: 20},
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	canonicalNewer, _ := filepath.EvalSymlinks(newer)
	canonicalOlder, _ := filepath.EvalSymlinks(older)
	if len(candidates) != 2 || candidates[0].CWD != canonicalNewer || candidates[1].CWD != canonicalOlder {
		t.Fatalf("unexpected candidates: %#v", candidates)
	}
}

func TestChangedFilesFromStatusOnlyReturnsNewChanges(t *testing.T) {
	before := []string{"1 .M N... 100644 100644 100644 abc abc existing file.kt"}
	after := []string{
		"1 .M N... 100644 100644 100644 abc abc existing file.kt",
		"? new file.kt",
	}
	files := ChangedFilesFromStatus(before, after)
	if len(files) != 1 || files[0] != "new file.kt" {
		t.Fatalf("files=%#v", files)
	}
}

func mustGit(t *testing.T, args ...string) string {
	t.Helper()
	command := exec.Command("git", args...)
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v: %v\n%s", args, err, output)
	}
	return string(output)
}
