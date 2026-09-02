package observer

// FocusPlan 返回把指定线程聚焦到 Mac 主屏的命令序列。
// 2026-09-02 探针定稿：先 caffeinate -u 唤屏，再 open 深链激活并置前；
// open 不带 -g——`-g` 是「不置前」，与「点击卡片在主屏聚焦定位」相悖。
func FocusPlan(threadID string) [][]string {
	return [][]string{
		{"/usr/bin/caffeinate", "-u", "-t", "2"},
		{"/usr/bin/open", "codex://threads/" + threadID},
	}
}

// ValidFocusThreadID 限制深链参数为 codex 线程 uuid 形态，
// 防止外部输入拼进任意 URL 或命令参数。
func ValidFocusThreadID(id string) bool {
	if len(id) < 8 || len(id) > 64 {
		return false
	}
	for _, r := range id {
		switch {
		case r >= '0' && r <= '9', r >= 'a' && r <= 'f', r == '-':
		default:
			return false
		}
	}
	return true
}
