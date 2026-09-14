package webremote

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

const validURL = "https://zcode.z.ai/remote/v4?sid=d_ABC123&hash=AbCd%2F0%3D&t=1789404212550&mid=m1&name=mac&app_version=3.11.2"

type invocation struct {
	name string
	args []string
}

// fakeRunner 按 index 返回预设输出；index 越界时复用最后一个。
type fakeRunner struct {
	calls   []invocation
	outputs []string
	errs    []error
}

func (f *fakeRunner) run(_ context.Context, name string, args ...string) (string, error) {
	f.calls = append(f.calls, invocation{name, args})
	i := len(f.calls) - 1
	if i >= len(f.outputs) {
		i = len(f.outputs) - 1
	}
	if len(f.errs) > 0 {
		if e := f.errs[min(i, len(f.errs)-1)]; e != nil {
			return f.outputs[min(i, len(f.outputs)-1)], e
		}
	}
	return f.outputs[min(i, len(f.outputs)-1)], nil
}

func newAutomator(f *fakeRunner, pasteboard string) *Automator {
	return &Automator{
		Runner:     f.run,
		Pasteboard: func(context.Context) (string, error) { return pasteboard, nil },
		Sleep: func(context.Context, time.Duration) error { return nil },
	}
}

func argsOf(f *fakeRunner, index int) string {
	return strings.Join(f.calls[index].args, " ")
}

// Refresh 快乐路径：唤醒 → 开框 → 刷新 → 确认弹窗 → 复制 → pbpaste 得新 URL。
func TestRefreshHappyPath(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", "ready", "clicked", "yes", "clicked", "clicked"}}
	a := newAutomator(f, validURL)
	res := a.Refresh(context.Background())

	if res.Stage != StageOK || res.URL != validURL {
		t.Fatalf("Refresh = %+v, want ok + url", res)
	}
	joined := ""
	for _, c := range f.calls {
		joined += c.name + " " + strings.Join(c.args, " ") + "\n"
	}
	for _, want := range []string{"caffeinate -u -t 2", "open -a ZCode", "osascript -e"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("调用序列缺 %q:\n%s", want, joined)
		}
	}
	// 确认弹窗后应点击两次「刷新二维码」（第一次刷新 + 弹窗确认）。
	// pbpaste 未被 Automator 直接调用（Pasteboard 注入），osascript 调用 ≥4。
	osaCount := 0
	for _, c := range f.calls {
		if c.name == "/usr/bin/osascript" {
			osaCount++
		}
	}
	if osaCount < 4 {
		t.Fatalf("osascript 调用数 = %d, want >= 4\n%s", osaCount, joined)
	}
}

// Link 快乐路径：不点刷新按钮，直接复制链接。
func TestLinkHappyPath(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", "ready", "clicked"}}
	a := newAutomator(f, validURL)
	res := a.Link(context.Background())
	if res.Stage != StageOK || res.URL != validURL {
		t.Fatalf("Link = %+v, want ok + url", res)
	}
	for _, c := range f.calls {
		if c.name == "/usr/bin/osascript" && strings.Contains(c.args[1], btnRefresh) {
			t.Fatalf("Link 不应触碰刷新按钮:\n%s", c.args[1])
		}
	}
}

// TCC 未授权：osascript 退出码 -1719 → ax-denied。
func TestAXDeniedClassification(t *testing.T) {
	f := &fakeRunner{outputs: []string{""}}
	f.errs = []error{&exec.ExitError{Stderr: []byte("execution error: \u201cSystem Events\u201d遇到一个错误：\u201cosascript\u201d不允许辅助访问。 (-1719)")}}
	a := newAutomator(f, validURL)
	res := a.Refresh(context.Background())
	if res.Stage != StageAXDenied {
		t.Fatalf("stage = %q, want ax-denied (err=%v)", res.Stage, res.Err)
	}
}

// ZCode 未运行 → zcode-not-running。
func TestZcodeNotRunning(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", "no-app"}}
	a := newAutomator(f, validURL)
	res := a.Refresh(context.Background())
	if res.Stage != StageZcodeNotRunning {
		t.Fatalf("stage = %q, want zcode-not-running", res.Stage)
	}
}

// 对话框入口找不到（AX 树未激活等）→ dialog-not-found。
func TestDialogNotFound(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", "missing"}}
	a := newAutomator(f, validURL)
	res := a.Refresh(context.Background())
	if res.Stage != StageDialogNotFound {
		t.Fatalf("stage = %q, want dialog-not-found", res.Stage)
	}
}

// 复制链接按钮缺失 → ax-empty。
func TestCopyButtonMissing(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", "ready", "missing"}}
	a := newAutomator(f, validURL)
	res := a.Link(context.Background())
	if res.Stage != StageAXEmpty {
		t.Fatalf("stage = %q, want ax-empty", res.Stage)
	}
}

// 剪贴板内容不是配对 URL → clipboard-invalid。
func TestClipboardInvalid(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", "ready", "clicked"}}
	a := newAutomator(f, "hello world\nsecond line")
	res := a.Link(context.Background())
	if res.Stage != StageClipboardInvalid {
		t.Fatalf("stage = %q, want clipboard-invalid", res.Stage)
	}
}

// Pasteboard 注入失败 → failed。
func TestPasteboardError(t *testing.T) {
	f := &fakeRunner{outputs: []string{"", "", "ready", "clicked"}}
	a := newAutomator(f, "")
	a.Pasteboard = func(context.Context) (string, error) { return "", errors.New("pbpaste boom") }
	res := a.Link(context.Background())
	if res.Stage != StageFailed {
		t.Fatalf("stage = %q, want failed", res.Stage)
	}
}

func TestParseRemoteURL(t *testing.T) {
	cases := []struct {
		name    string
		raw     string
		want    string
		wantErr bool
	}{
		{"标准链接", validURL, validURL, false},
		{"多行噪声中提取", "一些文字\n" + validURL + "\n尾随", validURL, false},
		{"http 拒绝", "http://zcode.z.ai/remote/v4?sid=a&hash=b", "", true},
		{"非 remote 路径拒绝", "https://zcode.z.ai/other?sid=a&hash=b", "", true},
		{"缺 hash 拒绝", "https://zcode.z.ai/remote/v4?sid=a", "", true},
		{"纯文本拒绝", "no link here", "", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := ParseRemoteURL(tc.raw)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("ParseRemoteURL(%q) = %q, want error", tc.raw, got)
				}
				return
			}
			if err != nil {
				t.Fatalf("ParseRemoteURL(%q) err = %v", tc.raw, err)
			}
			if got != tc.want {
				t.Fatalf("ParseRemoteURL = %q, want %q", got, tc.want)
			}
		})
	}
}

// 生成的 AppleScript 必须能被 osacompile 编译（语法守门，Mac 上运行）。
func TestAppleScriptCompiles(t *testing.T) {
	if _, err := exec.LookPath("/usr/bin/osacompile"); err != nil {
		t.Skip("osacompile 不可用，跳过")
	}
	for name, script := range map[string]string{
		"ensure": scriptFor("ensure", ""),
		"click":  scriptFor("click", btnRefresh),
		"text":   scriptFor("text", txtModalMark),
	} {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			src := dir + "/s.applescript"
			out := dir + "/s.scpt"
			if err := os.WriteFile(src, []byte(script), 0o600); err != nil {
				t.Fatal(err)
			}
			if outb, err := exec.Command("/usr/bin/osacompile", "-o", out, src).CombinedOutput(); err != nil {
				t.Fatalf("osacompile 失败: %v\n%s\n脚本:\n%s", err, outb, script)
			}
		})
	}
}

// 脚本模板包含全部按钮常量与 AX 激活调用，防止后续改动漏拼。
func TestScriptTemplates(t *testing.T) {	ensure := scriptFor("ensure", "")
	for _, want := range []string{btnEntry, btnCopyLink, "AXManualAccessibility", "entire contents"} {
		if !strings.Contains(ensure, want) {
			t.Fatalf("ensure 脚本缺 %q", want)
		}
	}
	click := scriptFor("click", btnRefresh)
	if !strings.Contains(click, btnRefresh) || !strings.Contains(click, `return "clicked"`) {
		t.Fatalf("click 脚本内容异常")
	}
	text := scriptFor("text", txtModalMark)
	if !strings.Contains(text, txtModalMark) {
		t.Fatalf("text 脚本缺标记 %q", txtModalMark)
	}
}
