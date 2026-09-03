package appserver

import (
	"os"
	"reflect"
	"testing"
)

func TestThreadReadFixtureKeepsStableIdentityAndIgnoresUnknownFields(t *testing.T) {
	raw := readFixture(t, "testdata/thread-read.json")

	response, err := DecodeThreadRead(raw)
	if err != nil {
		t.Fatalf("DecodeThreadRead() error = %v", err)
	}
	if response.Thread.ID != "thread-1" || response.Thread.CWD != "/workspace/harness-apk" {
		t.Fatalf("thread = %#v", response.Thread)
	}
	if len(response.Thread.Turns) != 1 || response.Thread.Turns[0].ID != "turn-1" {
		t.Fatalf("turns = %#v", response.Thread.Turns)
	}
	if len(response.Thread.Turns[0].Items) != 1 {
		t.Fatalf("items = %#v", response.Thread.Turns[0].Items)
	}
}

func TestCommandApprovalFixtureUsesOnlyMobileSafeDecisions(t *testing.T) {
	request, err := DecodeServerRequest(readFixture(t, "testdata/command-approval.json"))
	if err != nil {
		t.Fatalf("DecodeServerRequest() error = %v", err)
	}
	if request.Kind != InteractionApproval {
		t.Fatalf("kind = %q, want %q", request.Kind, InteractionApproval)
	}
	want := []string{"accept", "decline"}
	if got := MobileApprovalDecisions(); !reflect.DeepEqual(got, want) {
		t.Fatalf("MobileApprovalDecisions() = %#v, want %#v", got, want)
	}
}

func TestRequestUserInputFixtureIsNotApproval(t *testing.T) {
	request, err := DecodeServerRequest(readFixture(t, "testdata/request-user-input.json"))
	if err != nil {
		t.Fatalf("DecodeServerRequest() error = %v", err)
	}
	if request.Kind != InteractionUserInput {
		t.Fatalf("kind = %q, want %q", request.Kind, InteractionUserInput)
	}
	if request.IsApproval() {
		t.Fatal("requestUserInput must not be decoded as approval")
	}
}

func TestContractsRejectMissingStableIdentity(t *testing.T) {
	if _, err := DecodeThreadRead([]byte(`{"result":{"thread":{"cwd":"/tmp","turns":[]}}}`)); err == nil {
		t.Fatal("DecodeThreadRead() accepted a thread without id")
	}
	if _, err := DecodeServerRequest([]byte(`{"id":1,"params":{}}`)); err == nil {
		t.Fatal("DecodeServerRequest() accepted a request without method")
	}
}

func TestApprovalRejectsMissingTurnAndItemIdentity(t *testing.T) {
	if _, err := DecodeServerRequest([]byte(`{"id":1,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1"}}`)); err == nil {
		t.Fatal("DecodeServerRequest() accepted an approval without turnId/itemId")
	}
}

func TestUserInputRejectsMissingItemIdentity(t *testing.T) {
	if _, err := DecodeServerRequest([]byte(`{"id":1,"method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1"}}`)); err == nil {
		t.Fatal("DecodeServerRequest() accepted user input without itemId")
	}
}

func readFixture(t *testing.T, path string) []byte {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile(%q) error = %v", path, err)
	}
	return raw
}
