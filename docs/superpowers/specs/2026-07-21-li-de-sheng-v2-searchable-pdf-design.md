# 李德胜 V2 可检索 PDF 与重新蒸馏设计

日期：2026-07-21

## 目标

将“李德胜”V1 使用的 14 份扫描 PDF 重新处理为保留原页面视觉内容、带坐标化中文 OCR 文本层的可检索 PDF，再与其余 37 份资料共同构建 schema v2 人格智能体。V1 工作区、V1 成品、原始扫描 PDF 和现有逐页 OCR TXT 均保留且不覆盖。

智能体参数固定为：

- 名称：李德胜
- `agent-id`：`li-de-sheng`
- 版本：`2`
- 默认发布 profile：`balanced`
- 发布者私钥：沿用现有李德胜私钥，不生成替代密钥

所有处理均在本机完成，不上传原稿、OCR 结果、工作区、构建产物或私钥。

## 输入与输出

输入：

- 原始资料集：`/Volumes/game/books/agents/李德胜-v1-原始书稿`
- 14 份既有逐页 OCR 文本：`/Volumes/game/books/agent-build/li-de-sheng-ocr`
- 现有发布者私钥：`/Users/tony/.config/harness-apk/keys/li-de-sheng.pem`

输出：

- V2 最终输入集：`/Volumes/game/books/agents/李德胜-v2-可检索原稿`
- V2 元数据发现工作区：`/Volumes/game/books/agent-build/li-de-sheng-v2-discovery`
- V2 工作区：`/Volumes/game/books/agent-build/li-de-sheng-v2`
- V2 发布目录：`/Volumes/game/books/agents/li-de-sheng-v2-balanced-release`
- Android 主安装包：发布目录中的 `li-de-sheng-v2-balanced.hbundle`

最终输入集保留原资料集的相对目录。14 份扫描 PDF 由带 OCR 文本层的版本替换；其余 37 份文件按字节复制。原始扫描 PDF 继续保存在 V1 原始书稿目录中。

## PDF 处理方案

### 识别

使用 macOS Apple Vision `VNRecognizeTextRequest`，选择准确优先模式，启用简体中文、繁体中文、英文识别和语言纠正。每页先以 300 DPI 从原 PDF 渲染为仅供 OCR 的临时图像；该图像不写回成品 PDF。识别结果记录每一行的文字、置信度和归一化边界框。

现有 OCR TXT 仅用于回归比较、页数核对和异常发现，不直接作为新文本层内容。若 Vision 某页识别为空而既有 OCR 明显非空，该页进入异常清单并以 400 DPI 重试；仍失败则停止该文档发布，不静默跳页。

### 文本层

依据 Vision 返回的边界框，将每行文字映射到原 PDF 的 crop box、旋转方向和坐标系。使用支持中文 Unicode 映射的字体创建不可见文本绘制指令，再将叠层合并到原页面。

合成过程不得栅格化、重新压缩或替换原页面图像。成品保留原页数、页面尺寸、crop/media box 和旋转信息。文本层只用于搜索、复制和后续语料提取，不改变肉眼可见内容。

### 事务与恢复

每份 PDF 使用独立 staging 目录，先写临时成品和逐页 OCR 记录，全部验证通过后再原子移动到最终输入集。中断时只保留可识别的 checkpoint，不发布半成品；重新运行时可从完整 checkpoint 恢复。

批处理最多同时处理 2 页，避免 5,151 页同时占用大量内存。每份文档以 JSONL 记录逐页 checkpoint，临时页面图像在对应页面处理完成后立即删除。

## PDF 验收

自动验收覆盖全部 14 份 PDF：

1. 原稿页数、成品页数和 OCR 页面记录必须完全一致。
2. 每页 media box、crop box 和 rotation 必须一致。
3. 原 PDF 的页面图像对象不得被重新编码或替换。
4. 每个非空 Vision 识别页必须能从成品 PDF 提取中文文本。
5. 提取文字与 Vision 识别结果按去除空白后的字符多重集比较，覆盖率必须达到 99.5%；低于 99.5% 的页面进入失败清单并阻止发布。
6. 每份文档以 150 DPI 渲染首页、中页和末页，原稿与成品像素哈希必须一致，并制作抽检图供视觉检查。
7. 每份文档再按固定随机种子抽取 2 个正文页，检查搜索、复制顺序和文本框位置，使同一输入每次抽检相同页面。

只有 14 份文档全部通过，才进入 V2 `prepare-v2`。

## V2 蒸馏流程

1. 以最终输入集中的 51 份文件在元数据发现工作区执行首次 `prepare-v2`。
2. 从 `source-catalog.json` 汇总所有 `genre`、`authorship`、`period` 未知项，按标题和文件名稳定排序，一次性向用户确认；允许给出明确标注为“建议”的系列级默认值。
3. 使用确认后的 catalog 在新的空工作区重新执行 `prepare-v2`。
4. 只依据 `corpora/index/chunks.jsonl` 生成九类资产：`persona`、`identity`、`voice`、`worldview`、`episodes`、`concepts`、`examples`、`openers`、`eval`。
5. `voice`、直接引语和人物语气只引用 `direct` 或 `edited_direct` 证据；`secondary` 只作事实旁证。
6. 保留不同时期的立场差异；required/recommended corpus 各配置至少两道真实归属评测题。
7. 运行 `validate`，修正具体证据或资产问题，不绕过质量门槛。
8. 使用现有发布者私钥运行 `recommend`，报告四档实际签名体积、预检耗时、原始资料字节和临时产物占用。
9. 未收到其他 profile 指定时，直接按 `balanced` 打包并独立复验签名、校验和、bundle 内容和 Android 文件名。

## 失败边界

以下情况阻止进入下一阶段：

- 任一原文件、OCR 页或私钥缺失；
- OCR 与 PDF 页数不一致；
- 文本层不可提取、页面视觉发生变化或坐标映射异常；
- V2 来源元数据仍有 unknown；
- 人物资产引用不存在、错误时期或错误 authorship 的 chunk；
- `validate` 或签名复验失败；
- 输出目录非空或磁盘空间不足。

失败时保留原始输入和已验证 checkpoint，不覆盖 V1，也不发布部分 V2 目录。

## 非目标

- 不人工改写原著内容或纠正其观点。
- 不把既有 OCR TXT 当作原始扫描 PDF 的替代存档。
- 不生成 `source` profile，除非用户随后明确要求。
- 不自动上传、推送或发送任何构建产物。
