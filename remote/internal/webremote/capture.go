// 窗口截图 + 进程内解码取链路线：不碰 AX/Apple Events，只需一次「屏幕录制」授权。
// 找窗口用 Swift+CGWindowList（系统无此纯 Go 接口）；解二维码用纯 Go 的
// gozxing（ZXing 移植），编译进单二进制，无任何运行时依赖。
package webremote

import (
	"context"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	_ "image/jpeg"
	_ "image/png"
	"os"
	"path/filepath"
	"strings"

	gozxing "github.com/makiuchi-d/gozxing"
	"github.com/makiuchi-d/gozxing/qrcode"
)

// findWindows.swift：列出 ZCode 桌面端的候选窗口 id（layer 0 的屏幕可见窗口）。
// kCGWindowBundleID 未桥接进 Swift，只能用字符串字面量取键。
const swiftFindWindows = `import CoreGraphics
import Foundation
let opts: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
guard let list = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] else { exit(0) }
for w in list {
    let owner = (w[kCGWindowOwnerName as String] as? String) ?? ""
    let bundle = (w["kCGWindowBundleID"] as? String) ?? ""
    let layer = (w[kCGWindowLayer as String] as? Int) ?? 99
    let bounds = (w[kCGWindowBounds as String] as? [String: Any]) ?? [:]
    let width = (bounds["Width"] as? Int) ?? 0
    let height = (bounds["Height"] as? Int) ?? 0
    if layer == 0, width >= 200, height >= 200, owner == "ZCode" || bundle == "dev.zcode.app" {
        if let id = w[kCGWindowNumber as String] as? Int { print(id) }
    }
}
`

// swiftTool 编译并缓存辅助工具，返回可执行文件路径。
func (a *Automator) pngPath() string {
	if a.PNGPath != "" {
		return a.PNGPath
	}
	return filepath.Join(a.toolDir(), "window.png")
}

func (a *Automator) toolDir() string {
	if a.ToolDir != "" {
		return a.ToolDir
	}
	return filepath.Join(os.TempDir(), "harness-bridge-qr")
}

func (a *Automator) swiftTool(ctx context.Context, name, source string) (string, error) {
	dir := a.toolDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	bin := filepath.Join(dir, name)
	if _, err := os.Stat(bin); err == nil {
		return bin, nil
	}
	src := filepath.Join(dir, name+".swift")
	if err := os.WriteFile(src, []byte(source), 0o600); err != nil {
		return "", err
	}
	if out, err := a.Runner(ctx, "/usr/bin/swiftc", "-O", "-o", bin, src); err != nil {
		return "", fmt.Errorf("swiftc %s: %w: %s", name, err, strings.TrimSpace(out))
	}
	return bin, nil
}

// decodeQRFile 对截图做 QR 解码。ZCode 渲染的二维码紧贴卡片边缘（静区不足），
// 直解会 NotFoundException 越界——按白边填充/放大/反相多变体重试。
func decodeQRFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	img, _, err := image.Decode(f)
	if err != nil {
		return "", err
	}

	attempt := func(src image.Image, makeBin func(gozxing.LuminanceSource) gozxing.Binarizer, hints map[gozxing.DecodeHintType]any) (string, error) {
		bmp, err := gozxing.NewBinaryBitmap(makeBin(gozxing.NewLuminanceSourceFromImage(src)))
		if err != nil {
			return "", err
		}
		res, err := qrcode.NewQRCodeReader().Decode(bmp, hints)
		if err != nil {
			return "", err
		}
		return res.GetText(), nil
	}
	pad := func(src image.Image, frac int, invert bool) image.Image {
		w, h := src.Bounds().Dx(), src.Bounds().Dy()
		m := w / frac
		out := image.NewRGBA(image.Rect(0, 0, w+2*m, h+2*m))
		draw.Draw(out, out.Bounds(), image.White, image.Point{}, draw.Src)
		for y := 0; y < h; y++ {
			for x := 0; x < w; x++ {
				r, g, b, _ := src.At(src.Bounds().Min.X+x, src.Bounds().Min.Y+y).RGBA()
				if invert {
					r, g, b = 0xffff-r, 0xffff-g, 0xffff-b
				}
				out.SetRGBA(m+x, m+y, color.RGBA{R: uint8(r >> 8), G: uint8(g >> 8), B: uint8(b >> 8), A: 0xff})
			}
		}
		return out
	}
	hints := map[gozxing.DecodeHintType]any{gozxing.DecodeHintType_TRY_HARDER: true}
	hybrid := gozxing.NewHybridBinarizer
	global := gozxing.NewGlobalHistgramBinarizer

	type variant struct {
		name string
		img  image.Image
		bin  func(gozxing.LuminanceSource) gozxing.Binarizer
	}
	// 静区不足是主要失败模式：白边填充优先；反相覆盖暗色主题渲染。
	candidates := []variant{
		{"pad10-hybrid", pad(img, 10, false), hybrid},
		{"pad10-global", pad(img, 10, false), global},
		{"pad20-global", pad(img, 20, false), global},
		{"pad10-global-invert", pad(img, 10, true), global},
		{"raw-hybrid", img, hybrid},
	}
	var lastErr error
	for _, v := range candidates {
		text, err := attempt(v.img, v.bin, hints)
		if err == nil {
			return text, nil
		}
		lastErr = err
	}
	return "", lastErr
}

// CaptureLink 截取 ZCode 窗口 → 解析二维码 → 返回配对 URL。
// 桌面端「移动端远程控制」对话框必须处于打开状态（二维码仅在对话框内渲染）。
func (a *Automator) CaptureLink(ctx context.Context) Result {
	findBin, err := a.swiftTool(ctx, "find-windows", swiftFindWindows)
	if err != nil {
		return classifyErr(StageFailed, err)
	}
	idsOut, err := a.Runner(ctx, findBin)
	if err != nil {
		return classifyErr(StageFailed, err)
	}
	ids := strings.Fields(strings.TrimSpace(idsOut))
	if len(ids) == 0 {
		return Result{Stage: StageZcodeNotRunning, Err: fmt.Errorf("未找到 ZCode 窗口")}
	}
	png := a.pngPath()
	var lastErr error
	var lastPNG []byte
	for _, id := range ids {
		if _, err := a.Runner(ctx, "/usr/sbin/screencapture", "-x", "-o", "-l", id, png); err != nil {
			lastErr = err
			continue
		}
		lastPNG, _ = os.ReadFile(png)
		text, err := decodeQRFile(png)
		if err == nil {
			link, perr := ParseRemoteURL(text)
			if perr == nil {
				return Result{URL: link, Stage: StageOK}
			}
			lastErr = perr
		} else {
			lastErr = err
		}
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("窗口内未发现二维码")
	}
	out := Result{Stage: StageQRNotFound, Err: lastErr}
	if len(lastPNG) > 0 {
		out.ImageB64 = base64.StdEncoding.EncodeToString(lastPNG)
	}
	return out
}

