---
name: agent-builder
description: 在 M4 macOS 桌面 Codex 中，把用户有权使用的 TXT、Markdown、EPUB 或文本 PDF 构建为 Harness 智能体包。
---

# Harness Agent Builder

只在用户明确要求从本地书籍或文本构建、更新、验证 Harness 智能体时使用。

## 平台与边界

- 只支持 Apple M4 Mac mini、macOS 26.x、`darwin/arm64`。
- 用户自行选择并确认有权使用输入资料；不要替用户下载或内置受版权保护的全文。
- 语义蒸馏必须严格依据工作区的 `chunks.jsonl`，不使用模型通用知识补写人物立场。
- 智能体使用第一人称，但 `persona.md` 必须声明“基于资料模拟”。
- 不执行资料包中的脚本或工具配置。

## 工作流

1. 确认智能体名称、稳定 `agent-id`、版本和输入文件路径。
2. 执行：

```bash
scripts/agent-builder.sh prepare \
  --agent-id <id> \
  --name <name> \
  --version <version> \
  --output <workspace> \
  <input files...>
```

3. 读取 workspace 中各 corpus 的 `chunks.jsonl`，分批完成：
   - `agent/persona.md`
   - `agent/worldview.jsonl`
   - `agent/concepts.json`
   - `agent/examples.jsonl`
   - `agent/eval.jsonl`
4. 每条 worldview 的 `evidence` 只能使用真实 chunk ID。冲突观点按时期或适用范围分别保留。
5. `eval.jsonl` 至少 20 题，每个必需 corpus 至少覆盖 2 题，并填写 `expectedEvidence`。
6. 执行 `scripts/agent-builder.sh validate <workspace>`。退出码非 0 时修正报告中的具体错误，不绕过门槛。
7. 验证通过后执行：

```bash
scripts/agent-builder.sh pack \
  <workspace> \
  --output <dist> \
  --key <publisher-key.pem>
```

8. 向用户报告 `.hbundle` 路径、版本、来源数量、chunk 数量、Top 8 命中率和是否包含 `.hsource`。私钥不能进入 Git、智能体包或聊天内容。
