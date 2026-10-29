package imagegen

import (
	"errors"
	"strings"
	"testing"
)

func TestResolveAPIKeyPrefersEnvironmentOverCredentialsFile(t *testing.T) {
	home := t.TempDir()
	writeCredentials(t, home, "sk-from-file")
	t.Setenv("OPENAI_API_KEY", "")

	key, err := ResolveAPIKey(home)
	if err != nil {
		t.Fatalf("ResolveAPIKey: %v", err)
	}
	if key != "sk-from-file" {
		t.Fatalf("key = %q", key)
	}

	t.Setenv("OPENAI_API_KEY", "sk-from-env")
	key, err = ResolveAPIKey(home)
	if err != nil {
		t.Fatalf("ResolveAPIKey with env: %v", err)
	}
	if key != "sk-from-env" {
		t.Fatalf("environment did not win: %q", key)
	}
}

func TestResolveAPIKeyMissingEverywhere(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "")
	home := t.TempDir()
	_, err := ResolveAPIKey(home)
	var generationError *Error
	if !errors.As(err, &generationError) || generationError.Code != CodeMissingAPIKey {
		t.Fatalf("err = %v", err)
	}
	if !strings.Contains(generationError.Message, "OPENAI_API_KEY") {
		t.Fatalf("message = %q", generationError.Message)
	}
}

func TestResolveAPIKeyIgnoresEmptyRef(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "")
	home := t.TempDir()
	writeCredentials(t, home, "''")
	if _, err := ResolveAPIKey(home); err == nil {
		t.Fatal("empty ref must be treated as a missing key")
	}
}

func TestParseCredentialRefsHandlesQuotesCommentsAndOtherSections(t *testing.T) {
	raw := []byte(strings.Join([]string{
		"version: 1",
		"refs:",
		"  OPENAI_API_KEY: \"sk-double\"",
		"  OTHER_KEY: 'sk-single'",
		"  COMMENTED: value # a comment",
		"  EMPTY:",
		"other:",
		"  IGNORED: nope",
		"",
	}, "\n"))
	refs := parseCredentialRefs(raw)
	if refs["OPENAI_API_KEY"] != "sk-double" {
		t.Fatalf("double-quoted key = %q", refs["OPENAI_API_KEY"])
	}
	if refs["OTHER_KEY"] != "sk-single" {
		t.Fatalf("single-quoted key = %q", refs["OTHER_KEY"])
	}
	if refs["COMMENTED"] != "value" {
		t.Fatalf("commented value = %q", refs["COMMENTED"])
	}
	if _, exists := refs["IGNORED"]; exists {
		t.Fatal("keys outside refs must be ignored")
	}
}
