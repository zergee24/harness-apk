package imagegen

import (
	"os"
	"path/filepath"
	"strings"
)

// credentialsFileName is the DSH credential store consulted after the
// environment. Only the flat "refs:\n  KEY: value" subset is parsed so the
// bridge needs no YAML dependency.
const credentialsFileName = ".dsh/.credentials.yaml"

// ResolveAPIKey returns the OpenAI key for one generation request:
// OPENAI_API_KEY first, then refs.OPENAI_API_KEY from
// ~/.dsh/.credentials.yaml. homeDir may be empty to use the current home. The
// file is re-read on every call so a rotated key needs no restart.
func ResolveAPIKey(homeDir string) (string, error) {
	if key := strings.TrimSpace(os.Getenv("OPENAI_API_KEY")); key != "" {
		return key, nil
	}
	path, err := credentialsPath(homeDir)
	if err != nil {
		return "", missingAPIKeyError(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", missingAPIKeyError(err)
	}
	key := strings.TrimSpace(parseCredentialRefs(raw)["OPENAI_API_KEY"])
	if key == "" {
		return "", missingAPIKeyError(nil)
	}
	return key, nil
}

func missingAPIKeyError(cause error) error {
	return &Error{
		Code:    CodeMissingAPIKey,
		Message: "未配置 OPENAI_API_KEY：请设置环境变量，或在 ~/.dsh/.credentials.yaml 的 refs 中添加 OPENAI_API_KEY",
		Err:     cause,
	}
}

func credentialsPath(homeDir string) (string, error) {
	if homeDir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		homeDir = home
	}
	return filepath.Join(homeDir, filepath.FromSlash(credentialsFileName)), nil
}

// parseCredentialRefs extracts the refs section of the credentials file. Quoted
// values, single-quoted values and trailing " # comment" are understood;
// everything outside refs is ignored.
func parseCredentialRefs(raw []byte) map[string]string {
	refs := map[string]string{}
	section := ""
	for _, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimRight(line, "\r")
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		if !strings.HasPrefix(line, " ") && !strings.HasPrefix(line, "\t") {
			section = ""
			if name, ok := strings.CutSuffix(trimmed, ":"); ok {
				section = strings.TrimSpace(name)
			}
			continue
		}
		if section != "refs" {
			continue
		}
		key, value, ok := strings.Cut(trimmed, ":")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		if key == "" {
			continue
		}
		refs[key] = unquoteCredentialValue(strings.TrimSpace(value))
	}
	return refs
}

func unquoteCredentialValue(value string) string {
	if len(value) >= 2 {
		first, last := value[0], value[len(value)-1]
		if (first == '"' && last == '"') || (first == '\'' && last == '\'') {
			return value[1 : len(value)-1]
		}
	}
	if index := strings.Index(value, " #"); index >= 0 {
		value = strings.TrimSpace(value[:index])
	}
	return value
}
