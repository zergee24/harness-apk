package webremote

import "testing"

func TestDecodeGeneratedQR(t *testing.T) {
	got, err := decodeQRFile("/tmp/harness-bridge-qr/test-qr.png")
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	t.Logf("decoded: %s", got)
}
