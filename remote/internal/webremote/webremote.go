// Package webremote 以 AX 自动化驱动 ZCode 桌面端「移动端远程控制」对话框：
// 手机端经 bridge 触发刷新二维码并取回新配对 URL（2026-09-15 spike 定稿）。
// 全链路依赖对话框按钮文案（非官方集成面），ZCode 升级后需跟随修复。
package webremote

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os/exec"
	"strings"
	"time"
)

// Stage 标注自动化失败的具体环节，App 端据此给出引导文案。
type Stage string

const (
	StageOK               Stage = ""
	StageZcodeNotRunning  Stage = "zcode-not-running" // ZCode 桌面端未运行
	StageAXDenied         Stage = "ax-denied"         // 宿主缺 TCC「辅助功能」授权
	StageAXEmpty          Stage = "ax-empty"          // Electron AX 树未激活/按钮未找到
	StageDialogNotFound   Stage = "dialog-not-found"  // 侧边栏入口按钮未找到
	StageClipboardInvalid Stage = "clipboard-invalid" // 剪贴板内容不是配对 URL
	StageFailed           Stage = "failed"
)

// 按钮文案前缀（spike 实测的对话框结构，勿随 UI 改动漂移）。
const (
	btnEntry     = "移动端远程控制" // 侧边栏入口按钮
	btnRefresh   = "刷新二维码"   // 对话框内刷新按钮；确认弹窗内按钮同名（带快捷键后缀）
	btnCopyLink  = "复制链接"
	txtModalMark = "将失效" // 刷新确认弹窗的独有文案
)

// Runner 执行单条命令并返回 stdout（osascript 结果经由 stdout 传回）。
type Runner func(ctx context.Context, name string, args ...string) (string, error)

// Automator 编排一次 ZCode WebRemoteControl 自动化。
type Automator struct {
	Runner     Runner
	Pasteboard func(ctx context.Context) (string, error)
	Sleep      func(context.Context, time.Duration) error
}

// Result 是一次自动化动作的 outcome。
type Result struct {
	URL   string
	Stage Stage
	Err   error
}

// Refresh 唤屏后走完整链：开对话框 → 刷新二维码（含确认弹窗）→ 复制链接。
func (a *Automator) Refresh(ctx context.Context) Result {
	if err := a.wake(ctx); err != nil {
		return classifyErr(StageFailed, err)
	}
	if res := a.ensureDialog(ctx); res.Stage != StageOK {
		return res
	}
	if _, stage, err := a.click(ctx, btnRefresh); err != nil || stage != StageOK {
		return Result{Stage: stage, Err: err}
	}
	// 确认弹窗通常必现（spike 实测）；弹窗内确认按钮与刷新按钮同名前缀。
	confirmed := false
	for i := 0; i < 6; i++ {
		if err := a.sleep(ctx, 400*time.Millisecond); err != nil {
			return classifyErr(StageFailed, err)
		}
		has, err := a.hasModalText(ctx)
		if err != nil {
			return classifyErr(StageFailed, err)
		}
		if has {
			if _, stage, err := a.click(ctx, btnRefresh); err != nil || stage != StageOK {
				return Result{Stage: stage, Err: err}
			}
			confirmed = true
			break
		}
	}
	if confirmed {
		// 刷新后 relay 重建配对，等状态回「已就绪」再复制。
		if err := a.sleep(ctx, 1500*time.Millisecond); err != nil {
			return classifyErr(StageFailed, err)
		}
	}
	return a.copyLink(ctx)
}

// Link 只取当前配对 URL，不刷新（刷新会作废旧链接）。
func (a *Automator) Link(ctx context.Context) Result {
	if err := a.wake(ctx); err != nil {
		return classifyErr(StageFailed, err)
	}
	if res := a.ensureDialog(ctx); res.Stage != StageOK {
		return res
	}
	return a.copyLink(ctx)
}

func (a *Automator) sleep(ctx context.Context, d time.Duration) error {
	if a.Sleep != nil {
		return a.Sleep(ctx, d)
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(d):
		return nil
	}
}

func (a *Automator) wake(ctx context.Context) error {
	if _, err := a.Runner(ctx, "/usr/bin/caffeinate", "-u", "-t", "2"); err != nil {
		return err
	}
	if _, err := a.Runner(ctx, "/usr/bin/open", "-a", "ZCode"); err != nil {
		return err
	}
	return a.sleep(ctx, 800*time.Millisecond)
}

// ensureDialog 打开对话框（若未开）：先找「复制链接」判定已在，
// 否则点侧边栏入口按钮后再验。
func (a *Automator) ensureDialog(ctx context.Context) Result {
	out, err := a.osascript(ctx, ensureDialogScript())
	if err != nil {
		return classifyErr(StageFailed, err)
	}
	switch strings.TrimSpace(out) {
	case "ready", "opened":
		return Result{Stage: StageOK}
	case "no-app":
		return Result{Stage: StageZcodeNotRunning, Err: errors.New("ZCode 未运行")}
	case "missing", "no-window":
		return Result{Stage: StageDialogNotFound, Err: errors.New("未找到远程控制入口")}
	default:
		return Result{Stage: StageFailed, Err: fmt.Errorf("ensureDialog 意外输出 %q", out)}
	}
}

func (a *Automator) click(ctx context.Context, prefix string) (string, Stage, error) {
	out, err := a.osascript(ctx, clickScript(prefix))
	if err != nil {
		return "", StageFailed, err
	}
	switch strings.TrimSpace(out) {
	case "clicked":
		return out, StageOK, nil
	case "no-app":
		return out, StageZcodeNotRunning, errors.New("ZCode 未运行")
	default: // missing / no-window
		return out, StageAXEmpty, fmt.Errorf("按钮 %q 未找到（AX 树未激活或对话框已变）", prefix)
	}
}

func (a *Automator) hasModalText(ctx context.Context) (bool, error) {
	out, err := a.osascript(ctx, hasTextScript(txtModalMark))
	if err != nil {
		return false, err
	}
	return strings.TrimSpace(out) == "yes", nil
}

func (a *Automator) copyLink(ctx context.Context) Result {
	if _, stage, err := a.click(ctx, btnCopyLink); err != nil || stage != StageOK {
		return Result{Stage: stage, Err: err}
	}
	if err := a.sleep(ctx, 300*time.Millisecond); err != nil {
		return classifyErr(StageFailed, err)
	}
	raw, err := a.Pasteboard(ctx)
	if err != nil {
		return classifyErr(StageFailed, err)
	}
	link, perr := ParseRemoteURL(raw)
	if perr != nil {
		return Result{Stage: StageClipboardInvalid, Err: perr}
	}
	return Result{URL: link, Stage: StageOK}
}

func (a *Automator) osascript(ctx context.Context, script string) (string, error) {
	return a.Runner(ctx, "/usr/bin/osascript", "-e", script)
}

// classifyErr 把执行器错误映射为 stage：TCC 拒绝单独识别，便于 App 引导授权。
func classifyErr(defaultStage Stage, err error) Result {
	if err == nil {
		return Result{Stage: defaultStage}
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return Result{Stage: StageFailed, Err: err}
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		stderr := strings.ToLower(string(exitErr.Stderr))
		if strings.Contains(stderr, "-1719") || strings.Contains(stderr, "-25211") ||
			strings.Contains(stderr, "assistive") || strings.Contains(stderr, "辅助访问") ||
			strings.Contains(stderr, "辅助功能") {
			return Result{Stage: StageAXDenied, Err: err}
		}
	}
	return Result{Stage: defaultStage, Err: err}
}

// ParseRemoteURL 从剪贴板文本中提取并校验配对 URL：
// https + /remote/ 路径 + sid/hash 参数（结构校验，不锁域名以兼容 relay 换址）。
func ParseRemoteURL(raw string) (string, error) {
	token := ""
	for _, field := range strings.FieldsFunc(raw, func(r rune) bool {
		return r == '\n' || r == '\r' || r == ' ' || r == '\t'
	}) {
		if strings.HasPrefix(field, "https://") {
			token = field
			break
		}
	}
	if token == "" {
		return "", errors.New("剪贴板无 https 链接")
	}
	u, err := url.Parse(token)
	if err != nil {
		return "", fmt.Errorf("链接解析失败: %w", err)
	}
	if u.Scheme != "https" || !strings.HasPrefix(u.Path, "/remote/") {
		return "", errors.New("非远程控制配对链接")
	}
	q := u.Query()
	if q.Get("sid") == "" || q.Get("hash") == "" {
		return "", errors.New("链接缺 sid/hash 参数")
	}
	return token, nil
}
