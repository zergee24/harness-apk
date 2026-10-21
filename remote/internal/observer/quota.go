package observer

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
)

// QuotaSnapshot 是 Codex 账户限额/余额的副屏快照。
// 数据源：codex app-server 的 account/rateLimits/read（bridge 自有通道，
// 不依赖任何外部配额软件）。CreditsRemaining/creditsTodayUsed 在主额度
// （百分比）归零时由副屏今日卡额外展示——promax 类计划额度耗尽后按
// credits 计费，余额即剩余可用量。
type QuotaSnapshot struct {
	UsedPercent      int     `json:"usedPercent"`
	RemainingPercent int     `json:"remainingPercent"`
	ResetsAtMs       int64   `json:"resetsAtMs,omitempty"`
	PlanType         string  `json:"planType,omitempty"`
	CreditsRemaining *float64 `json:"creditsRemaining,omitempty"`
	CreditsTodayUsed *float64 `json:"creditsTodayUsed,omitempty"`
}

// ParseQuota 解析 account/rateLimits/read 的 result 载荷：
// {"rateLimits":{"primary":{"usedPercent":66,...},"resetsAt":1788927739,"planType":"pro",...}}
// 解析不出主额度时 ok=false，调用方保留上次值。
func ParseQuota(raw json.RawMessage) (QuotaSnapshot, bool) {
	if len(raw) == 0 {
		return QuotaSnapshot{}, false
	}
	var root struct {
		RateLimits *struct {
			Primary *struct {
				UsedPercent *int   `json:"usedPercent"`
				ResetsAt    int64  `json:"resetsAt"`
			} `json:"primary"`
			PlanType  *string `json:"planType"`
			Credits *struct {
				HasCredits *bool   `json:"hasCredits"`
				Unlimited  *bool   `json:"unlimited"`
				Balance    *string `json:"balance"`
			} `json:"credits"`
		} `json:"rateLimits"`
	}
	if err := json.Unmarshal(raw, &root); err != nil || root.RateLimits == nil || root.RateLimits.Primary == nil || root.RateLimits.Primary.UsedPercent == nil {
		return QuotaSnapshot{}, false
	}
	used := *root.RateLimits.Primary.UsedPercent
	q := QuotaSnapshot{
		UsedPercent:      used,
		RemainingPercent: 100 - used,
		PlanType:         stringOrEmpty(root.RateLimits.PlanType),
	}
	if resets := root.RateLimits.Primary.ResetsAt; resets > 0 {
		q.ResetsAtMs = resets * 1000
	}
	// credits 余额：限额耗尽后按量计费的余量（字符串十进制，如 "73231.7522"）。
	// 仅在计划明确带 credits 且非无限量时下发；今日消耗由调用方按日基线差值回填。
	if creds := root.RateLimits.Credits; creds != nil && creds.Balance != nil {
		has := creds.HasCredits == nil || *creds.HasCredits
		unlimited := creds.Unlimited != nil && *creds.Unlimited
		if has && !unlimited {
			if balance, err := strconv.ParseFloat(strings.TrimSpace(*creds.Balance), 64); err == nil {
				q.CreditsRemaining = &balance
			}
		}
	}
	return q, true
}

func stringOrEmpty(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

var ErrQuotaUnavailable = errors.New("observer: quota unavailable")

// ParseQuotaSnake 解析 token_count 事件内嵌的 snake_case rate_limits：
// {"primary":{"used_percent":52.0,"resets_at":1788748055},"plan_type":"pro"}
func ParseQuotaSnake(raw json.RawMessage) (QuotaSnapshot, bool) {
	if len(raw) == 0 {
		return QuotaSnapshot{}, false
	}
	var root struct {
		Primary *struct {
			UsedPercent *float64 `json:"used_percent"`
			ResetsAt    int64    `json:"resets_at"`
		} `json:"primary"`
		PlanType *string `json:"plan_type"`
	}
	if err := json.Unmarshal(raw, &root); err != nil || root.Primary == nil || root.Primary.UsedPercent == nil {
		return QuotaSnapshot{}, false
	}
	used := int(*root.Primary.UsedPercent + 0.5)
	q := QuotaSnapshot{
		UsedPercent:      used,
		RemainingPercent: 100 - used,
		PlanType:         stringOrEmpty(root.PlanType),
	}
	if root.Primary.ResetsAt > 0 {
		q.ResetsAtMs = root.Primary.ResetsAt * 1000
	}
	return q, true
}
