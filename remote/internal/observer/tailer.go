package observer

import (
	"io"
	"os"
	"strings"
	"time"
)

// seedWindow 是打开已有 rollout 时回放的历史上限：状态机只需要最近的
// turn 边界，几十 KB 足够，避免为 MB 级老文件全量回放。
const seedWindow = 64 << 10

// tailer 增量跟踪一个 rollout 文件，把行喂给状态机。
// 它不做 IO 阻塞：poll 由外层按固定间隔驱动（测试可同步调用）。
type tailer struct {
	path    string
	m       *Machine
	offset  int64
	midLine bool // offset 是否悬在半行中间（仅种子点会造成）
}

func newTailer(path string) *tailer {
	return &tailer{path: path, m: NewMachine()}
}

func (t *tailer) close() {}

// poll 检查文件增长、消费新行并推进状态机；now 传当前时刻。
func (t *tailer) poll(now time.Time) {
	st, err := os.Stat(t.path)
	if err != nil {
		t.m.Tick(now)
		return
	}
	size := st.Size()
	if size < t.offset {
		// 文件被截断/轮转：按新文件对待，状态机重置。
		t.m = NewMachine()
		t.offset = 0
		t.midLine = false
	}
	if size == t.offset {
		t.m.Tick(now)
		return
	}

	from := t.offset
	if from == 0 && size > seedWindow {
		from = size - seedWindow
		t.midLine = true
	}
	f, err := os.Open(t.path)
	if err != nil {
		t.m.Tick(now)
		return
	}
	data, readErr := io.ReadAll(io.NewSectionReader(f, from, size-from))
	f.Close()

	text := string(data)
	if t.midLine {
		// 种子点落在某行中间：丢弃这不完整的首行（增量追加路径不会进这里，
		// 因为 offset 总是停在上一个换行之后）。
		if i := strings.IndexByte(text, '\n'); i >= 0 {
			text = text[i+1:]
			t.midLine = false
		} else {
			text = ""
		}
	}
	if complete := strings.LastIndexByte(text, '\n'); complete >= 0 {
		for _, l := range strings.Split(text[:complete], "\n") {
			t.m.ObserveLine(l, now)
		}
		t.offset = from + int64(len(data)) - int64(len(text)-complete-1)
	} else {
		// 还没有完整行：不推进 offset，等下次 poll。
		t.offset = from
		t.midLine = true
	}
	_ = readErr
	t.m.Tick(now)
}

// Snapshot 返回当前状态；threadID 由上层（supervisor）回填。
func (t *tailer) Snapshot() ThreadStatus {
	return t.m.Snapshot()
}
