package workspace

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
)

type Source struct {
	CWD        string
	LastUsedAt int64
}

type Candidate struct {
	WorkspaceID           string `json:"workspaceId"`
	DisplayName           string `json:"displayName"`
	CWD                   string `json:"cwd"`
	RepositoryLabel       string `json:"repositoryLabel,omitempty"`
	Branch                string `json:"branch,omitempty"`
	RepositoryFingerprint string `json:"repositoryFingerprint"`
	LastUsedAt            int64  `json:"lastUsedAt"`
}

func InspectCandidates(pairingSecret []byte, recent []Source, registered []string) ([]Candidate, error) {
	if len(pairingSecret) == 0 {
		return nil, errors.New("pairing secret is required")
	}
	sources := append([]Source(nil), recent...)
	for _, cwd := range registered {
		sources = append(sources, Source{CWD: cwd})
	}
	byPath := make(map[string]Source)
	for _, source := range sources {
		canonical, err := canonicalDirectory(source.CWD)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) || errors.Is(err, errNotDirectory) {
				continue
			}
			return nil, err
		}
		source.CWD = canonical
		if prior, ok := byPath[canonical]; !ok || source.LastUsedAt > prior.LastUsedAt {
			byPath[canonical] = source
		}
	}
	candidates := make([]Candidate, 0, len(byPath))
	for _, source := range byPath {
		candidate, err := inspectCanonical(pairingSecret, source.CWD, source.LastUsedAt)
		if err != nil {
			return nil, err
		}
		candidates = append(candidates, candidate)
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].LastUsedAt != candidates[j].LastUsedAt {
			return candidates[i].LastUsedAt > candidates[j].LastUsedAt
		}
		return candidates[i].CWD < candidates[j].CWD
	})
	return candidates, nil
}

func Inspect(pairingSecret []byte, cwd string, lastUsedAt int64) (Candidate, error) {
	if len(pairingSecret) == 0 {
		return Candidate{}, errors.New("pairing secret is required")
	}
	canonical, err := canonicalDirectory(cwd)
	if err != nil {
		return Candidate{}, err
	}
	return inspectCanonical(pairingSecret, canonical, lastUsedAt)
}

var errNotDirectory = errors.New("workspace path is not a directory")

func canonicalDirectory(cwd string) (string, error) {
	if strings.TrimSpace(cwd) == "" {
		return "", os.ErrNotExist
	}
	absolute, err := filepath.Abs(cwd)
	if err != nil {
		return "", fmt.Errorf("absolute workspace path: %w", err)
	}
	canonical, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return "", fmt.Errorf("canonical workspace path: %w", err)
	}
	info, err := os.Stat(canonical)
	if err != nil {
		return "", fmt.Errorf("stat workspace path: %w", err)
	}
	if !info.IsDir() {
		return "", errNotDirectory
	}
	return filepath.Clean(canonical), nil
}

func inspectCanonical(pairingSecret []byte, cwd string, lastUsedAt int64) (Candidate, error) {
	workspaceID := keyedID(pairingSecret, "workspace-v1\x00"+cwd)
	candidate := Candidate{
		WorkspaceID: workspaceID,
		DisplayName: filepath.Base(cwd),
		CWD:         cwd,
		LastUsedAt:  lastUsedAt,
	}
	root, gitErr := gitOutput(cwd, "rev-parse", "--show-toplevel")
	if gitErr == nil {
		canonicalRoot, err := canonicalDirectory(root)
		if err != nil {
			return Candidate{}, fmt.Errorf("canonical repository root: %w", err)
		}
		origin, _ := gitOutput(cwd, "config", "--get", "remote.origin.url")
		label := sanitizeRemote(origin)
		candidate.RepositoryLabel = label
		candidate.DisplayName = filepath.Base(canonicalRoot)
		candidate.RepositoryFingerprint = digest("git-v1\x00" + canonicalRoot + "\x00" + label)
		if branch, err := gitOutput(cwd, "symbolic-ref", "--quiet", "--short", "HEAD"); err == nil {
			candidate.Branch = branch
		} else if head, err := gitOutput(cwd, "rev-parse", "--short=8", "HEAD"); err == nil {
			candidate.Branch = head
		}
		return candidate, nil
	}
	info, err := os.Stat(cwd)
	if err != nil {
		return Candidate{}, err
	}
	candidate.RepositoryFingerprint = digest("dir-v1\x00" + cwd + "\x00" + fileIdentity(info))
	return candidate, nil
}

func gitOutput(cwd string, args ...string) (string, error) {
	commandArgs := append([]string{"-C", cwd}, args...)
	output, err := exec.Command("git", commandArgs...).Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(output)), nil
}

func sanitizeRemote(remote string) string {
	remote = strings.TrimSpace(remote)
	if remote == "" {
		return ""
	}
	if !strings.Contains(remote, "://") {
		if at := strings.LastIndex(remote, "@"); at >= 0 {
			remote = remote[at+1:]
		}
		if colon := strings.Index(remote, ":"); colon > 0 {
			return trimRepositorySuffix(remote[:colon] + "/" + strings.TrimPrefix(remote[colon+1:], "/"))
		}
	}
	parsed, err := url.Parse(remote)
	if err != nil || parsed.Hostname() == "" {
		return trimRepositorySuffix(filepath.Base(remote))
	}
	return trimRepositorySuffix(parsed.Hostname() + "/" + strings.TrimPrefix(parsed.Path, "/"))
}

func trimRepositorySuffix(label string) string {
	label = strings.TrimSuffix(strings.TrimSuffix(label, "/"), ".git")
	return strings.Trim(label, "/")
}

func keyedID(secret []byte, value string) string {
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(value))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func digest(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func fileIdentity(info os.FileInfo) string {
	value := reflect.ValueOf(info.Sys())
	if value.IsValid() && value.Kind() == reflect.Pointer {
		value = value.Elem()
	}
	if value.IsValid() && value.Kind() == reflect.Struct {
		dev := value.FieldByName("Dev")
		ino := value.FieldByName("Ino")
		if dev.IsValid() && ino.IsValid() && dev.CanUint() && ino.CanUint() {
			return fmt.Sprintf("%d:%d", dev.Uint(), ino.Uint())
		}
	}
	return fmt.Sprintf("%s:%d:%d:%d", info.Name(), info.Size(), info.Mode(), info.ModTime().UnixNano())
}
