package main

import (
	"encoding/json"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/harnessapk/remote/internal/commandcache"
	runstate "github.com/harnessapk/remote/internal/run"
)

func TestDuplicateRunStartReturnsCachedResultWithoutCallingAppServer(t *testing.T) {
	cache, err := commandcache.Open(filepath.Join(t.TempDir(), "commands.json"))
	if err != nil {
		t.Fatal(err)
	}
	appServerCalls := 0
	call := func() (string, json.RawMessage, error) {
		appServerCalls++
		return "event-result-1", json.RawMessage(`{"runId":"run-1"}`), nil
	}
	first, err := executeCachedCommand(cache, "command-1", "run.start", "payload-hash", call)
	if err != nil {
		t.Fatal(err)
	}
	second, err := executeCachedCommand(cache, "command-1", "run.start", "payload-hash", call)
	if err != nil {
		t.Fatal(err)
	}
	if appServerCalls != 1 || first.ResultEventID != "event-result-1" || second.ResultEventID != first.ResultEventID {
		t.Fatalf("calls=%d first=%#v second=%#v", appServerCalls, first, second)
	}
}

func TestEventTargetsOnlyThreadOwner(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{RunID: "run-a", HostID: "host-a", DeviceID: "phone-a", ThreadID: "thread-a"}); err != nil {
		t.Fatal(err)
	}
	bridge := &bridge{routes: routes}
	params := json.RawMessage(`{"threadId":"thread-a","turn":{"id":"turn-a"}}`)

	if got := bridge.eventTargets(params); !reflect.DeepEqual(got, []string{"phone-a"}) {
		t.Fatalf("targets = %#v", got)
	}
}

func TestUnownedEventIsNotBroadcast(t *testing.T) {
	routes, err := runstate.OpenRoutes(filepath.Join(t.TempDir(), "routes.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := routes.Put(runstate.Route{RunID: "run-a", HostID: "host-a", DeviceID: "phone-a", ThreadID: "thread-a"}); err != nil {
		t.Fatal(err)
	}
	bridge := &bridge{routes: routes}

	if got := bridge.eventTargets(json.RawMessage(`{"threadId":"thread-b"}`)); len(got) != 0 {
		t.Fatalf("targets = %#v", got)
	}
}

func TestSnapshotStatusMapsAuthoritativeTurnState(t *testing.T) {
	completed, line := snapshotStatus(json.RawMessage(`{"thread":{"turns":[{"id":"turn-1","status":{"type":"completed"}}]}}`), "turn-1")
	if completed != "COMPLETED" || line != "任务已完成" {
		t.Fatalf("completed=%q line=%q", completed, line)
	}
	running, _ := snapshotStatus(json.RawMessage(`{"thread":{"turns":[{"id":"turn-2","status":{"type":"inProgress"}}]}}`), "turn-2")
	if running != "RUNNING" {
		t.Fatalf("running=%q", running)
	}
}
