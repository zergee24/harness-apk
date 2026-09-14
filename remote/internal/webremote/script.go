package webremote

import "strings"

// AppleScript 模板。所有插值均来自本包常量（按钮文案前缀），无外部输入拼入。
// 每次调用都是独立 osascript 进程，公共 handler 需随脚本全文携带；
// tell 块内的脚本级 handler 调用必须带 my 前缀，否则会被派发给应用。

const scriptHandlers = `on axReady()
	tell application "System Events"
		if not (exists application process "ZCode") then return "no-app"
		try
			set value of attribute "AXManualAccessibility" of application process "ZCode" to true
		end try
		try
			set value of attribute "AXEnhancedUserInterface" of application process "ZCode" to true
		end try
	end tell
	delay 0.6
	return ""
end axReady

on findBtn(prefix)
	tell application "System Events" to tell application process "ZCode"
		if (count of windows) = 0 then return missing value
		set els to entire contents of window 1
		repeat with e in els
			try
				if class of e is button then
					set n to name of e
					if n is not missing value and n begins with prefix then return e
				end if
			end try
		end repeat
	end tell
	return missing value
end findBtn

on findText(marker)
	tell application "System Events" to tell application process "ZCode"
		if (count of windows) = 0 then return false
		set els to entire contents of window 1
		repeat with e in els
			try
				if class of e is static text then
					set n to name of e
					set v to value of e
					if (n is not missing value and n contains marker) or (v is not missing value and v contains marker) then return true
				end if
			end try
		end repeat
	end tell
	return false
end findText
`

func buildScript(body string) string {
	return scriptHandlers + body
}

// ensureDialogScript：已开对话框 → ready；否则点侧边栏入口 → opened/missing。
func ensureDialogScript() string {
	body := `
tell application "System Events"
	set pre to my axReady()
	if pre is "no-app" then return "no-app"
	if my findBtn("` + btnCopyLink + `") is not missing value then return "ready"
	set b to my findBtn("` + btnEntry + `")
	if b is missing value then return "missing"
	click b
	delay 1.2
	if my findBtn("` + btnCopyLink + `") is not missing value then return "opened"
	return "missing"
end tell
`
	return buildScript(body)
}

// clickScript：点第一个 name 前缀匹配的按钮。
func clickScript(prefix string) string {
	body := `
tell application "System Events"
	set pre to my axReady()
	if pre is "no-app" then return "no-app"
	set b to my findBtn("` + prefix + `")
	if b is missing value then return "missing"
	click b
	return "clicked"
end tell
`
	return buildScript(body)
}

// hasTextScript：窗口内是否有包含 marker 的静态文本（确认弹窗探测）。
func hasTextScript(marker string) string {
	body := `
tell application "System Events"
	set pre to my axReady()
	if pre is "no-app" then return "no-app"
	if my findText("` + marker + `") then return "yes"
	return "no"
end tell
`
	return buildScript(body)
}

// scriptFor 汇总各脚本构造器，便于测试断言内容完整性。
func scriptFor(kind, arg string) string {
	switch kind {
	case "ensure":
		return ensureDialogScript()
	case "click":
		return clickScript(arg)
	case "text":
		return hasTextScript(arg)
	}
	return strings.Repeat("", 0)
}
