// Package observer 只读观察 Codex 桌面 app 自管的线程（bridge 非拥有方），
// 为 Harness 副屏 dashboard 提供线程目录与运行状态。
//
// 线程目录主源是 ~/.codex/state_5.sqlite 的 threads 表。经审查决定不经 Go
// 驱动（modernc.org/sqlite 依赖树在本机离线环境拉取不到），而是执行 macOS
// 自带的 /usr/bin/sqlite3 -readonly -json：零新增依赖，只读打开，查询语句
// 全部由代码内常量拼接整数参数构成，无注入面。
package observer

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const SQLite3Bin = "/usr/bin/sqlite3"

const sqlite3Bin = SQLite3Bin

// ThreadInfo 是副屏卡片需要的最小线程快照。
type ThreadInfo struct {
	ID          string `json:"threadId"`
	Title       string `json:"title"`
	CWD         string `json:"cwd,omitempty"`
	GitBranch   string `json:"gitBranch,omitempty"`
	UpdatedAtMs int64  `json:"updatedAtMs"`
	RolloutPath string `json:"-"`
}

// Options 控制 Catalog 的数据源与过滤窗口，零值字段取 DefaultOptions 补齐。
type Options struct {
	Now         time.Time     // 目录新鲜度基准
	Window      time.Duration // 只显示 Now-Window 之后有活动的线程
	Limit       int           // 最多返回条数
	SQLitePath  string        // threads 目录库；打不开时降级 IndexPath
	IndexPath   string        // session_index.jsonl 降级源
	SessionsDir string        // 降级时按文件名内嵌 id 定位 rollout 的会话目录
}

func DefaultOptions() Options {
	home, _ := os.UserHomeDir()
	codex := filepath.Join(home, ".codex")
	return Options{
		Window:      24 * time.Hour,
		Limit:       32,
		SQLitePath:  filepath.Join(codex, "state_5.sqlite"),
		IndexPath:   filepath.Join(codex, "session_index.jsonl"),
		SessionsDir: filepath.Join(codex, "sessions"),
	}
}

func (o Options) normalized() Options {
	def := DefaultOptions()
	if o.Now.IsZero() {
		o.Now = time.Now()
	}
	if o.Window <= 0 {
		o.Window = def.Window
	}
	if o.Limit <= 0 {
		o.Limit = def.Limit
	}
	if o.SQLitePath == "" {
		o.SQLitePath = def.SQLitePath
	}
	if o.IndexPath == "" {
		o.IndexPath = def.IndexPath
	}
	if o.SessionsDir == "" {
		o.SessionsDir = def.SessionsDir
	}
	return o
}

// Catalog 返回副屏可见的线程列表：先读 sqlite 目录，失败降级 session_index。
// 两条路径都失败时才返回错误。
func Catalog(ctx context.Context, o Options) ([]ThreadInfo, error) {
	o = o.normalized()
	threads, err := catalogSQLite(ctx, o)
	if err == nil {
		return threads, nil
	}
	fallback, fbErr := catalogIndex(o)
	if fbErr != nil {
		return nil, fmt.Errorf("observer: sqlite 目录读取失败(%v)且降级失败(%w)", err, fbErr)
	}
	return fallback, nil
}

type sqliteRow struct {
	ID          string  `json:"id"`
	Title       string  `json:"title"`
	CWD         string  `json:"cwd"`
	GitBranch   *string `json:"git_branch"`
	UpdatedAtMs int64   `json:"updated_at_ms"`
	RolloutPath string  `json:"rollout_path"`
}

func catalogSQLite(ctx context.Context, o Options) ([]ThreadInfo, error) {
	// source 列为主线程存 'vscode' 等短标签，subagent 线程存 JSON 对象
	// （含 parent_thread_id），NOT LIKE '{%' 一并排除。
	query := fmt.Sprintf(`SELECT id, COALESCE(NULLIF(name,''),title) AS title, cwd, git_branch, updated_at_ms, rollout_path
		FROM threads
		WHERE archived=0 AND updated_at_ms > %d AND source NOT LIKE '{%%}'
		ORDER BY updated_at_ms DESC LIMIT %d`, o.Now.Add(-o.Window).UnixMilli(), o.Limit)

	cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(cctx, sqlite3Bin, "-readonly", "-json", o.SQLitePath, query)
	var stdout strings.Builder
	cmd.Stdout = &stdout
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("sqlite3 只读查询失败: %w", err)
	}
	var rows []sqliteRow
	if err := json.Unmarshal([]byte(stdout.String()), &rows); err != nil {
		return nil, fmt.Errorf("sqlite3 输出解析失败: %w", err)
	}
	threads := make([]ThreadInfo, 0, len(rows))
	for _, r := range rows {
		branch := ""
		if r.GitBranch != nil {
			branch = *r.GitBranch
		}
		threads = append(threads, ThreadInfo{
			ID:          r.ID,
			Title:       r.Title,
			CWD:         r.CWD,
			GitBranch:   branch,
			UpdatedAtMs: r.UpdatedAtMs,
			RolloutPath: r.RolloutPath,
		})
	}
	return threads, nil
}

type indexEntry struct {
	ID         string `json:"id"`
	ThreadName string `json:"thread_name"`
	UpdatedAt  string `json:"updated_at"`
}

// catalogIndex 是热路径降级源：session_index.jsonl 只提供 id/名称/更新时间，
// rollout 路径按会话目录「文件名内嵌 uuid」的约定定位；找不到就留空，
// 由 tailer 跳过。index 不含 subagent 与归档线程，无需再过滤。
func catalogIndex(o Options) ([]ThreadInfo, error) {
	f, err := os.Open(o.IndexPath)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	cutoffMs := o.Now.Add(-o.Window).UnixMilli()
	var threads []ThreadInfo
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var e indexEntry
		if jsonErr := json.Unmarshal([]byte(line), &e); jsonErr != nil || e.ID == "" || e.UpdatedAt == "" {
			continue
		}
		updated, parseErr := time.Parse(time.RFC3339Nano, e.UpdatedAt)
		if parseErr != nil || updated.UnixMilli() <= cutoffMs {
			continue
		}
		threads = append(threads, ThreadInfo{
			ID:          e.ID,
			Title:       e.ThreadName,
			UpdatedAtMs: updated.UnixMilli(),
		})
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	sort.Slice(threads, func(i, j int) bool { return threads[i].UpdatedAtMs > threads[j].UpdatedAtMs })
	if len(threads) > o.Limit {
		threads = threads[:o.Limit]
	}
	for i := range threads {
		if path, ok := findRollout(o.SessionsDir, threads[i].ID); ok {
			threads[i].RolloutPath = path
		}
	}
	return threads, nil
}

func findRollout(sessionsDir, threadID string) (string, bool) {
	if threadID == "" {
		return "", false
	}
	var found string
	suffix := "-" + threadID + ".jsonl"
	_ = filepath.WalkDir(sessionsDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || found != "" {
			return filepath.SkipAll
		}
		if d != nil && !d.IsDir() && strings.HasSuffix(d.Name(), suffix) {
			found = path
			return filepath.SkipAll
		}
		return nil
	})
	return found, found != ""
}

var ErrNoThreads = errors.New("observer: 目录为空")

// TodayTurnCount 统计 thread_history_1.sqlite 里本地零点之后的 turn 数
// （started_at 为秒级时间戳）。读不到时 ok=false，调用方保持上次值。
func TodayTurnCount(ctx context.Context, dbPath string, midnightSec int64) (int, bool) {
	cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	query := fmt.Sprintf("SELECT COUNT(*) FROM thread_turns WHERE started_at >= %d", midnightSec)
	cmd := exec.CommandContext(cctx, sqlite3Bin, "-readonly", "-json", dbPath, query)
	var stdout strings.Builder
	cmd.Stdout = &stdout
	if err := cmd.Run(); err != nil {
		return 0, false
	}
	var rows []struct {
		Count int `json:"COUNT(*)"`
	}
	if err := json.Unmarshal([]byte(stdout.String()), &rows); err != nil || len(rows) == 0 {
		return 0, false
	}
	return rows[0].Count, true
}

// ThreadHistoryDBPath 返回今日 turn 计数所在的 thread_history 库路径。
func ThreadHistoryDBPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".codex", "thread_history_1.sqlite")
}

// LocalMidnightSec 返回 today（YYYY-MM-DD，本地时区）零点的秒级时间戳；
// today 解析失败时回退到当前时刻，使计数窗口退化为「现在起算」。
func LocalMidnightSec(today string) int64 {
	parsed, err := time.ParseInLocation("2006-01-02", today, time.Local)
	if err != nil {
		return time.Now().Unix()
	}
	return parsed.Unix()
}

// DetailItem 是线程详情的单条 item（thread_items 原样透传，超长截断）。
type DetailItem struct {
	ItemType  string          `json:"itemType"`
	Item      json.RawMessage `json:"item"`
	Truncated bool            `json:"truncated,omitempty"`
}

// DetailItems 按 rollout_ordinal 倒序取最近 limit 条 items（返回时反转为
// 时间正序）。单条 item_json 超过 itemJSONLimit 视为超长：截断并置
// Truncated，App 端展示截断提示。
func DetailItems(ctx context.Context, dbPath, threadID string, limit int) ([]DetailItem, bool) {
	const itemJSONLimit = 8192
	if limit <= 0 {
		limit = 50
	}
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if !ValidFocusThreadID(threadID) {
		return nil, false
	}
	query := fmt.Sprintf("SELECT item_type, item_json FROM thread_items WHERE thread_id = '%s' ORDER BY rollout_ordinal DESC LIMIT %d", threadID, limit)
	cmd := exec.CommandContext(cctx, sqlite3Bin, "-readonly", "-json", dbPath, query)
	var stdout strings.Builder
	cmd.Stdout = &stdout
	if err := cmd.Run(); err != nil {
		return nil, false
	}
	var rows []struct {
		ItemType string `json:"item_type"`
		ItemJSON string `json:"item_json"`
	}
	out := strings.TrimSpace(stdout.String())
	if out == "" {
		return []DetailItem{}, true
	}
	if err := json.Unmarshal([]byte(out), &rows); err != nil {
		return nil, false
	}
	items := make([]DetailItem, 0, len(rows))
	for i := len(rows) - 1; i >= 0; i-- {
		r := rows[i]
		item := r.ItemJSON
		truncated := false
		if len(item) > itemJSONLimit {
			item = item[:itemJSONLimit]
			truncated = true
		}
		items = append(items, DetailItem{
			ItemType:  r.ItemType,
			Item:      json.RawMessage(item),
			Truncated: truncated,
		})
	}
	return items, true
}
