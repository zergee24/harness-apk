# History Evaluation v1

## 人工边界

- `create-eval-template` 只生成类别槽位和候选证据，始终写 `reviewed=false`。
- 只有人实际打开保存的原文和 locator 后，才能修正 query、gold 与说明并设置 `reviewed=true`。
- Agent 不得用模型信心、摘要、常识或当前检索结果代替人工复核，也不得删除失败 case 来提高指标。
- `evaluation/` 是本地构建资料，不进入 `.hwiki`，不得上传或提交真实来源的 gold 引语。

## 单库集合

每个 Wiki 至少 160 条，以下 8 类各不少于 20 条：

- `original-keyword`
- `modern-paraphrase`
- `alias-title-place`
- `time-expression`
- `homonym-disambiguation`
- `multi-volume-synthesis`
- `known-evidence-gap`
- `no-result`

前 6 类的 `expectedResult` 为 `evidence`，必须有真实 `goldEvidence`。后 2 类为 `no-result`，不得写 gold，必须在 `reviewerNotes` 说明已检查的范围和为什么属于证据缺口或明确无结果。

## 双库集合

至少 60 条：

- `mutual-corroboration` 20 条，`expectedResult=both`。
- `differing-account` 20 条，`expectedResult=both`。
- `one-sided-gap` 10 条，`expectedResult=one-sided`。
- `no-result` 10 条，`expectedResult=no-result`。

`both` 必须分别引用两个 Wiki 的 gold；`one-sided` 必须且只能引用一个 Wiki，并写明另一侧检查范围；`no-result` 不含 gold。

## Case 协议

`cases.jsonl` 按 `caseId` 升序，每行是 canonical JSON：

```json
{
  "caseId": "mutual-corroboration-001",
  "scope": "pair",
  "category": "mutual-corroboration",
  "query": "人工核验的问题",
  "expectedResult": "both",
  "goldEvidence": [
    {
      "wikiId": "cn.history.twenty-four-histories",
      "wikiVersion": 1,
      "documentId": "真实 document ID",
      "sectionId": "真实 section ID",
      "chunkId": "真实 chunk ID",
      "quote": "原文中的连续片段",
      "locator": {"与 content.sqlite 完全一致": true}
    }
  ],
  "reviewed": true,
  "reviewerNotes": "人工检查说明"
}
```

查询规范化后不得重复。每条 gold 的 Wiki 身份、版本、document、section、chunk、逐字引语和完整 locator 必须与当前只读数据库一致。

## 固定门槛

- 单库整体 `Recall@20 >= 0.90`。
- 单库每类 `Recall@20 >= 0.85`。
- 双库最终 12 条的 gold 覆盖和双方 case 覆盖均 `>= 0.90`。
- 保存引语与原文匹配率 `1.00`。
- document、section、版本和 locator 有效率 `1.00`。

无效 citation token 的可见通过率 `0.00` 是 Android `WikiCitationVerifier` 的运行时门槛，由 Android 测试执行；桌面构建器不伪造该指标。
