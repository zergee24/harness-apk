package observer

import (
	"context"
	"sort"
	"sync"
	"time"
)

// ThreadSnapshot 合并目录信息与运行状态，是副屏卡片的一帧。
type ThreadSnapshot struct {
	ThreadInfo
	Status        Status `json:"status"`
	Approx        bool   `json:"approx,omitempty"`
	Note          string `json:"note,omitempty"`
	LastEventAtMs int64  `json:"lastEventAtMs,omitempty"`
}

// Supervisor 维护活跃线程目录与 rollout tailer 集合。
// PollOnce 由外层按固定间隔驱动；Current 返回最近一帧。
type Supervisor struct {
	opts Options
	topN int

	mu     sync.Mutex
	tails  map[string]*tailer
	latest []ThreadSnapshot
}

func NewSupervisor(opts Options, topN int) *Supervisor {
	if topN <= 0 {
		topN = 8
	}
	return &Supervisor{opts: opts, topN: topN, tails: map[string]*tailer{}}
}

// PollOnce 刷新目录、驱动各 tailer，返回按最近活动排序的一帧快照。
func (s *Supervisor) PollOnce(ctx context.Context, now time.Time) []ThreadSnapshot {
	threads, err := Catalog(ctx, s.opts)
	if err != nil {
		// 目录暂时读不到（如 WAL 恢复窗口）时保留上一帧，不放大成故障。
		threads = nil
	}
	if len(threads) > s.topN {
		threads = threads[:s.topN]
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	next := make(map[string]*tailer, len(threads))
	merged := make([]ThreadSnapshot, 0, len(threads))
	for _, th := range threads {
		tl := s.tails[th.ID]
		if tl == nil {
			tl = newTailer(th.RolloutPath)
		}
		tl.poll(now)
		next[th.ID] = tl
		st := tl.Snapshot()
		merged = append(merged, ThreadSnapshot{
			ThreadInfo:    th,
			Status:        st.Status,
			Approx:        st.Approx,
			Note:          st.Note,
			LastEventAtMs: st.LastEventAtMs,
		})
	}
	s.tails = next
	sort.Slice(merged, func(i, j int) bool { return merged[i].UpdatedAtMs > merged[j].UpdatedAtMs })
	s.latest = merged
	return merged
}

// Current 返回最近一次 PollOnce 的快照帧。
func (s *Supervisor) Current() []ThreadSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.latest
}

// Seed 直接注入一帧快照（测试与外部预置用）。
func (s *Supervisor) Seed(snaps []ThreadSnapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.latest = snaps
}
