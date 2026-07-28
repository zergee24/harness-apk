package push

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func TestNotifySendsSilentWakeMessage(t *testing.T) {
	var received url.Values
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		received = r.Form
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	client := &Aliyun{
		AccessKeyID:     "key",
		AccessKeySecret: "secret",
		AppKey:          42,
		Endpoint:        server.URL,
		Client:          server.Client(),
	}

	if err := client.Notify(context.Background(), "device", "wake"); err != nil {
		t.Fatal(err)
	}

	if received.Get("PushType") != "MESSAGE" {
		t.Fatalf("PushType = %q", received.Get("PushType"))
	}
	if received.Get("Body") != "wake" {
		t.Fatalf("Body = %q", received.Get("Body"))
	}
	if received.Get("AndroidOpenType") != "" {
		t.Fatalf("AndroidOpenType = %q", received.Get("AndroidOpenType"))
	}
}
