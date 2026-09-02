package observer

import (
	"encoding/json"
	"testing"
)

func TestParseQuotaFromAppServerResult(t *testing.T) {
	raw := json.RawMessage(`{"rateLimits":{"limitId":"codex","primary":{"usedPercent":66,"windowDurationMins":10080,"resetsAt":1788927739},"secondary":null,"credits":{"hasCredits":false,"unlimited":false,"balance":"0"},"planType":"pro"}}`)
	q, ok := ParseQuota(raw)
	if !ok {
		t.Fatal("应解析成功")
	}
	if q.UsedPercent != 66 || q.RemainingPercent != 34 {
		t.Fatalf("百分比错误: %+v", q)
	}
	if q.ResetsAtMs != 1788927739000 {
		t.Fatalf("resetsAtMs 应换算为毫秒: %+v", q)
	}
	if q.PlanType != "pro" {
		t.Fatalf("planType = %q", q.PlanType)
	}
}

func TestParseQuotaRejectsMissingPrimary(t *testing.T) {
	if _, ok := ParseQuota(json.RawMessage(`{"rateLimits":null}`)); ok {
		t.Fatal("rateLimits 为空应解析失败")
	}
	if _, ok := ParseQuota(json.RawMessage(`{}`)); ok {
		t.Fatal("空载荷应解析失败")
	}
	if _, ok := ParseQuota(nil); ok {
		t.Fatal("nil 应解析失败")
	}
}
