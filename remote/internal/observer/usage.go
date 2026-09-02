package observer

import (
	"encoding/json"
	"sort"
)

// UsageSummary 是 account/usage/read 的副屏裁剪版：summary + 最近 14 天日桶。
type UsageSummary struct {
	CurrentStreakDays int           `json:"currentStreakDays"`
	WeekTokens        int64         `json:"weekTokens"`
	DailyBuckets      []UsageBucket `json:"dailyBuckets,omitempty"`
}

type UsageBucket struct {
	StartDate string `json:"startDate"`
	Tokens    int64  `json:"tokens"`
}

// ParseUsage 解析 account/usage/read 的 result 载荷；today 传本地日期
// （YYYY-MM-DD）用于挑出今日 tokens。日桶只保留最近 14 天（按日期倒序取前 14）。
func ParseUsage(raw json.RawMessage, today string) (UsageSummary, bool) {
	if len(raw) == 0 {
		return UsageSummary{}, false
	}
	var root struct {
		Summary *struct {
			CurrentStreakDays int `json:"currentStreakDays"`
		} `json:"summary"`
		DailyUsageBuckets []struct {
			StartDate string `json:"startDate"`
			Tokens    int64  `json:"tokens"`
		} `json:"dailyUsageBuckets"`
	}
	if err := json.Unmarshal(raw, &root); err != nil || root.Summary == nil {
		return UsageSummary{}, false
	}
	u := UsageSummary{CurrentStreakDays: root.Summary.CurrentStreakDays}
	buckets := make([]UsageBucket, 0, len(root.DailyUsageBuckets))
	for _, b := range root.DailyUsageBuckets {
		buckets = append(buckets, UsageBucket{StartDate: b.StartDate, Tokens: b.Tokens})
	}
	sort.Slice(buckets, func(i, j int) bool { return buckets[i].StartDate > buckets[j].StartDate })
	if len(buckets) > 14 {
		buckets = buckets[:14]
	}
	// 近 7 天 tokens：日桶只含完整日，不含运行中的当天，因此不提供「今日」口径。
	for i, b := range buckets {
		if i < 7 {
			u.WeekTokens += b.Tokens
		}
	}
	u.DailyBuckets = buckets
	return u, true
}
