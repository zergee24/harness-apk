package backend

import (
	"context"
	"os/exec"
	"testing"
	"time"
)

// TestDSHBackendSmoke drives the real `dsh --profile appserver` process
// through the canonical Backend surface when dsh is installed. It verifies
// initialize and thread/start only — no model turn, so it is fast and costs
// nothing. Skipped when dsh is not on PATH (CI machines without dsh).
func TestDSHBackendSmoke(t *testing.T) {
	if _, err := exec.LookPath("dsh"); err != nil {
		t.Skip("dsh CLI not found on PATH")
	}
	c, err := StartCodex(Spec{
		ID: "dsh", Name: "DeepSeek Harness", Capabilities: DSHCapabilities(),
		Exec: "dsh",
		Args: []string{"--profile", "appserver", "--listen", "stdio://"},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	c.Start(ctx)

	result, err := c.Call(ctx, "initialize", map[string]any{"clientInfo": map[string]string{"name": "test"}})
	if err != nil {
		t.Fatal(err)
	}
	if !containsString(string(result), "dsh-appserver") {
		t.Fatalf("initialize result = %s", result)
	}
	thread, err := c.Call(ctx, "thread/start", map[string]any{"cwd": t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if !containsString(string(thread), "session-") {
		t.Fatalf("thread/start result = %s", thread)
	}
	if c.ID() != "dsh" || c.Name() != "DeepSeek Harness" {
		t.Fatalf("identity = %s/%s", c.ID(), c.Name())
	}
	for _, capability := range c.Capabilities() {
		if capability == "approvals.v1" {
			t.Fatal("dsh must not advertise approvals.v1")
		}
	}
}

func containsString(haystack, needle string) bool {
	return len(haystack) >= len(needle) && (func() bool {
		for index := 0; index+len(needle) <= len(haystack); index++ {
			if haystack[index:index+len(needle)] == needle {
				return true
			}
		}
		return false
	})()
}
