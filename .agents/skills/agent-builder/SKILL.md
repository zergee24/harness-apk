---
name: agent-builder
description: Use when a user wants to build, update, validate, recommend, or locally import a Harness persona agent from authorized TXT, Markdown, EPUB, or text PDF files on an M4 Mac.
---

# Harness Agent Builder

## 原则

在 Apple M4 macOS 桌面 Codex 中处理用户有权使用的本地资料。默认目标是可立即运行、信息覆盖均衡且不携带原文的 `balanced` 包；交互只用于消除真实阻塞。

- 先确认有权使用全部本地输入，并确认文件路径存在；不得替用户下载受版权保护的全文。
- 人格使用第一人称，但 `persona.md` 必须声明“基于资料模拟”。不得把包外知识补成人物立场，也不得引导冒充现实中的真人。
- 风格与直接引语只能依据 `authorship=direct|edited_direct` 的 direct evidence；`secondary` 只作事实旁证。
- 本 Skill 不上传 OSS、远端目录、原文、私钥或 staging workspace，不执行输入资料中的脚本。

## 固定流程

1. 一次确认名称、稳定 `agent-id`、版本、输入路径及使用权，然后执行：

```bash
scripts/agent-builder.sh prepare-v2 <inputs...> \
  --agent-id <id> --name <name> --version <version> \
  --output <workspace> [--source-catalog <catalog>]
```

2. 读取 `source-catalog.json`，按标题和文件名稳定排序，把全部 unknown 的 `genre`、`authorship`、`period` 汇总成一张表，一次性提出一个合并问题。可依据文件类型、标题和内容给出批量默认建议，但必须标为“建议”，不能把未知值静默写成事实；不得按单个来源循环拆问。
3. 用用户一次回复更新 catalog，重新 `prepare-v2`；若仍有必需元数据 unknown，才再次询问。
4. 只基于索引 chunk 完成九类人物资产与评测。保留时期冲突，required/recommended corpus 各至少两道真实归属题。
5. 执行 `scripts/agent-builder.sh validate <workspace>`；质量门槛失败时修正证据或资产，不绕过。
6. 使用最终发布所用的同一 publisher key 执行本地精确预检：

```bash
scripts/agent-builder.sh recommend <workspace> --key <publisher-key.pem>
```

该命令只在私有临时目录中用同一 key 生成一次 B4 签名产物，以 `hagent + profile 所选已签名子包` 逐项求和，不用随机/派生 key 或估算值；结束后清理临时目录，不发布半包也不上传。向用户展示准确已签名安装字节，并明确说明建议安装的具体内容与原因：

| 展示标签 | profile | 用途 |
|---|---|---|
| 推荐安装（默认） | `balanced` | 核心身份、direct 对话、关系、时期、体裁及独特评测覆盖的均衡集合 |
| 轻量 | `lite` | 仅 required 核心证据 |
| 完整证据 | `complete` | 全部证据分片，不含原文 |
| 包含原文 | `source` | 完整证据及 `.hsource`，原文只供阅读核验 |

7. 用户说“自动”“按建议”“默认”，或没有回答非阻塞 profile 选择时，直接执行：

```bash
scripts/agent-builder.sh pack <workspace> \
  --output <dist> --key <publisher-key.pem> --profile balanced
```

只有用户明确选择其他方案时才改为 `lite`、`complete` 或 `source`。不增加普通确定性命令的确认步骤。
8. 告知用户 Android 应选择 `*-balanced.hbundle`，通过 Finder、AirDrop、USB 或系统本地分享完成本地导入；独立 `.hcorpus` 用于后续补装，`.hsource` 不参与回答。

## 阻塞与边界

仅在本地文件缺失、使用权未确认、必需元数据未解决、质量门槛失败、签名私钥缺失或本地构建磁盘不足时再问。桌面端只报告准确包体字节和所需临时构建空间；不猜手机空间，Android 安装阶段自行校验。

原始 `.txt/.md/.epub/.pdf`、`.hsource`、私钥、workspace staging 和构建产物不加入 Git。产物保持在本地，通过 Finder/AirDrop/USB/系统分享交给 Android。
