package observer

import (
	"context"
	"encoding/json"
	"os/exec"
	"testing"

)

func TestParseLoad1TakesFirstField(t *testing.T) {
	load, ok := ParseLoad1("1.52 1.30 1.10 {1, 2, 3}")
	if !ok || load != "1.52" {
		t.Fatalf("load = %q ok=%v", load, ok)
	}
	if _, ok := ParseLoad1(""); ok {
		t.Fatal("空串应解析失败")
	}
}

func TestParseQuotaSnakeFromTokenCount(t *testing.T) {
	raw := json.RawMessage(`{"primary":{"used_percent":52.0,"window_minutes":10080,"resets_at":1788748055},"plan_type":"pro"}`)
	q, ok := ParseQuotaSnake(raw)
	if !ok {
		t.Fatal("应解析成功")
	}
	if q.UsedPercent != 52 || q.RemainingPercent != 48 || q.ResetsAtMs != 1788748055000 || q.PlanType != "pro" {
		t.Fatalf("quota = %+v", q)
	}
}

func TestParseUsageStreakAndBucketsTruncation(t *testing.T) {
	raw := json.RawMessage(`{"summary":{"lifetimeTokens":1,"peakDailyTokens":2,"longestRunningTurnSec":3,"currentStreakDays":22,"longestStreakDays":60},"dailyUsageBuckets":[
		{"startDate":"2026-08-20","tokens":100},
		{"startDate":"2026-08-25","tokens":200},
		{"startDate":"2026-09-01","tokens":300},
		{"startDate":"2026-09-02","tokens":400}]}`)
	u, ok := ParseUsage(raw, "2026-09-02")
	if !ok {
		t.Fatal("应解析成功")
	}
	if u.CurrentStreakDays != 22 || u.WeekTokens != 1000 {
		t.Fatalf("usage = %+v", u)
	}
	if len(u.DailyBuckets) != 4 {
		t.Fatalf("14 天内应全部保留: %d", len(u.DailyBuckets))
	}
	if u.DailyBuckets[0].StartDate != "2026-09-02" {
		t.Fatalf("日桶应按日期倒序: %+v", u.DailyBuckets[0])
	}
}

func TestTodayTurnCountQueriesSqlite(t *testing.T) {
	dir := t.TempDir()
	db := dir + "/thread_history_1.sqlite"
	midnight := int64(1788297600) // 2026-09-02 本地零点假设
	statements := []string{
		"CREATE TABLE thread_turns (thread_id TEXT, turn_id TEXT, started_at INTEGER)",
		"INSERT INTO thread_turns VALUES ('t','u1'," + itoa(midnight+60) + ")",
		"INSERT INTO thread_turns VALUES ('t','u2'," + itoa(midnight+120) + ")",
		"INSERT INTO thread_turns VALUES ('t','u3'," + itoa(midnight-60) + ")",
	}
	for _, stmt := range statements {
		cmd := exec.Command(sqlite3Bin, db, stmt)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("sqlite3 失败: %v\n%s", err, out)
		}
	}
	got, ok := TodayTurnCount(context.Background(), db, midnight)
	if !ok {
		t.Fatal("查询失败")
	}
	if got != 2 {
		t.Fatalf("今日 turn 数应为 2，得到 %d", got)
	}
}
