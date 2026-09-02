package observer

import (
	"bufio"
	"fmt"
	"os"
	"testing"
)

func TestActivityProbeRealRollout(t *testing.T) {
	path := os.Getenv("PROBE_ROLLOUT")
	if path == "" {
		t.Skip("PROBE_ROLLOUT 未设置")
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	m := NewMachine()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1024*1024), 1024*1024)
	for sc.Scan() {
		m.ObserveLine(sc.Text(), m.lastActive)
	}
	s := m.Snapshot()
	fmt.Printf("PROBE: status=%s context=%d lastActivity=%q\n", s.Status, s.ContextPercent, s.LastActivity)
}
