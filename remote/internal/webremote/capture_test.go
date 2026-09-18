package webremote

import (
	"context"
	"errors"
	"image"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"

	gozxing "github.com/makiuchi-d/gozxing"
	"github.com/makiuchi-d/gozxing/qrcode"
)

// writeSyntheticQR 用 gozxing 编码器生成含 validURL 的标准二维码 PNG。
func writeSyntheticQR(t *testing.T, path, contents string) {
	t.Helper()
	matrix, err := (&qrcode.QRCodeWriter{}).EncodeWithoutHint(contents, gozxing.BarcodeFormat_QR_CODE, 300, 300)
	if err != nil {
		t.Fatal(err)
	}
	img := image.NewRGBA(image.Rect(0, 0, matrix.GetWidth(), matrix.GetHeight()))
	for y := 0; y < matrix.GetHeight(); y++ {
		for x := 0; x < matrix.GetWidth(); x++ {
			if matrix.Get(x, y) {
				img.Set(x, y, image.Black)
			} else {
				img.Set(x, y, image.White)
			}
		}
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		t.Fatal(err)
	}
}

// Link 快乐路径：截图 + 进程内解码，全程不碰 osascript。
func TestLinkHappyPath(t *testing.T) {
	dir := t.TempDir()
	png := filepath.Join(dir, "window.png")
	writeSyntheticQR(t, png, validURL)
	f := &fakeRunner{outputs: []string{"", "", "", "6145", ""}}
	a := newAutomator(f, validURL)
	a.PNGPath = png
	a.ToolDir = t.TempDir()
	res := a.Link(context.Background())
	if res.Stage != StageOK || res.URL != validURL {
		t.Fatalf("Link = %+v, want ok + url", res)
	}
	joined := ""
	for _, c := range f.calls {
		joined += c.name + " " + strings.Join(c.args, " ") + "\n"
		if c.name == "/usr/bin/osascript" {
			t.Fatalf("Link 不应调用 osascript（AX 免授权路线）:\n%s", joined)
		}
	}
	for _, want := range []string{"swiftc", "screencapture -x -o -l 6145"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("调用序列缺 %q:\n%s", want, joined)
		}
	}
}

// 没找到 ZCode 窗口（未运行/全被最小化）→ zcode-not-running。
func TestLinkNoWindow(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", ""}}
	a := newAutomator(f, validURL)
	a.PNGPath = filepath.Join(t.TempDir(), "w.png")
	a.ToolDir = t.TempDir()
	res := a.Link(context.Background())
	if res.Stage != StageZcodeNotRunning {
		t.Fatalf("stage = %q, want zcode-not-running", res.Stage)
	}
}

// 解码不出配对 URL → qr-not-found，并携带原图 base64 给手机端兜底。
func TestLinkQRNotFound(t *testing.T) {
	dir := t.TempDir()
	png := filepath.Join(dir, "w.png")
	if err := os.WriteFile(png, []byte("not a png"), 0o600); err != nil {
		t.Fatal(err)
	}
	f := &fakeRunner{outputs: []string{"", "", "", "6145", ""}}
	a := newAutomator(f, validURL)
	a.PNGPath = png
	a.ToolDir = t.TempDir()
	res := a.Link(context.Background())
	if res.Stage != StageQRNotFound {
		t.Fatalf("stage = %q, want qr-not-found", res.Stage)
	}
	if res.ImageB64 == "" {
		t.Fatalf("qr-not-found 应携带原图 base64 兜底")
	}
}

// screencapture 失败（屏幕录制未授权等）→ qr-not-found。
func TestLinkCaptureFails(t *testing.T) {
	// 调用序：caffeinate, open, swiftc-find, find, screencapture
	f := &fakeRunner{outputs: []string{"", "", "", "6145", ""}}
	f.errs = []error{nil, nil, nil, nil, errors.New("screencapture boom")}
	a := newAutomator(f, validURL)
	a.PNGPath = filepath.Join(t.TempDir(), "w.png")
	a.ToolDir = t.TempDir()
	res := a.Link(context.Background())
	if res.Stage != StageQRNotFound {
		t.Fatalf("stage = %q, want qr-not-found", res.Stage)
	}
}
