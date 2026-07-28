package main

import (
	"encoding/json"
	"reflect"
	"testing"
)

func TestEventTargetsOnlyThreadOwner(t *testing.T) {
	bridge := &bridge{threadOwners: map[string]string{"thread-a": "phone-a"}}
	params := json.RawMessage(`{"threadId":"thread-a","turn":{"id":"turn-a"}}`)

	if got := bridge.eventTargets(params); !reflect.DeepEqual(got, []string{"phone-a"}) {
		t.Fatalf("targets = %#v", got)
	}
}

func TestUnownedEventIsNotBroadcast(t *testing.T) {
	bridge := &bridge{threadOwners: map[string]string{"thread-a": "phone-a"}}

	if got := bridge.eventTargets(json.RawMessage(`{"threadId":"thread-b"}`)); len(got) != 0 {
		t.Fatalf("targets = %#v", got)
	}
}
