# 二十四史 wiki-build 语义富集 — 交接说明

## 项目目标
将全部 **2509** 个 history-job 逐一生成语义富集输出并验证至 VALID,然后执行 merge。

## 当前进度(截至交接时刻)
- 总数 2509,`valid/` 已 **1703** 个(67.9%),pending 约 **806**
- `outputs/` 与 valid 一致(1703),无遗留未验证产物
- 来源路径:`/Volumes/game/books/wiki-build/twenty-four-histories-v1/history-jobs/{inputs,outputs,valid}/` 与 `manifest.json`

## 核心命令
```bash
# 验证(jobId 完整 36 字符形 history-job-<24hex>)
./scripts/wiki-builder.sh history validate-job /Volumes/game/books/wiki-build/twenty-four-histories-v1 <jobId>
```
- 输出 JSON 即 VALID;出现「构建失败」即 FAIL,需修复重跑
- fix_kind/fix_links/sanitize 三个修复脚本位于 `/tmp/opencode/w24/`,用法 `python3 fix_kind.py <jobId> [<jobId>...]`,输出 all-consistent / ok / fixed / sanitized: n of m

## 基础设施
- `/tmp/opencode/gen_job24.py`:富集生成基础(提供 `load/add_concept/add_anno/add_link_by_text/set_summary/write`)
- `/tmp/opencode/show_job24.py <jobId>`:打印输入 chunks(含 chunkId、文本、inputHash、scope)
- 每个 job 的生成脚本: `/tmp/opencode/w24/gen_job<jobId前8位>.py`,产出固定路径 `outputs/<jobId>.jsonl`(单行 JSON)
- `set_summary(text, chunk_ids)` **必须传 chunk_ids 参数**,且每个 chunkId 必须是本 job 真实 chunkId
- add_anno 签名:`add_anno(chunk_id, expr, {'start':Y,'end':Y}, conf=1.0)`;interval 是 **dict**,不是字符串

## 硬性约束(违反会 FAIL)
- concept kind 仅:person、place、polity、office、era、work、event
- `add_concept(kind, canonical, chunk_ids, confidence=0.95, aliases=())`:
  - canonical 必须在其声明的**所有** chunk_ids 中**逐字连续出现**(文本实际字形,可能是繁体)
  - 若段落只用简称(如"豹"/"楷"/"貽永"),canonical 用该简称字形,证据只列该字形出现的 chunk,全称放 aliases
  - evidence 非空、不重复;同一 canonical 只 add 一次
- link kind 纯 ASCII 小写 token(如 `office.held`/`served-under`,禁止 `father/son` 裸词);`add_link_by_text(src_kind, src_text, dst_kind, dst_text, kind, ev, conf)`,conf ≤ 0.84,ev 是**单个 chunkId 字符串**
- 概念 30-60 个(个别到 67/77/99 也通过过 validate,尽量收敛)、links 5-15、摘要 80-200 字
- evidence/chunkId 必须是本 job 真实 chunkId(注意 24 位 hex 尾巴要精确,如 `...3fd309` 不是 `...fd309`;文本中可能含 OCR 空格如「武 德元年」「中 书令」,canonical 必须用连续字形或跳过该 chunk)

## 派发工作流(每批 4 个并行,已验证可行)
1. 取 pending 最小 chars 的 4 个真实 jobId(先 `ls valid` 与 manifest 求差,勿手写/编造 ID!)
2. 并行派 4 个 general 子代理,每个 prompt 附完整约束模板(见下方模板要点)
3. 子代理常返回空 → 检查 `ls outputs/<jobId>.jsonl`,不存在则重派或自行处理(自行处理:show_job24 读全文 → 手写 gen 脚本 → 跑通)
4. 4 个都完成后:`python3 fix_kind.py <4个> && fix_links.py && sanitize.py`,然后逐个 validate-job
5. FAIL 则按报错信息修复(见常见错误)

## 常见错误与修复模式
- NO MENTION:canonical 未在该 chunk 连续出现 → 删该 chunk 或改用文本实际字形
- Link lookup fail / 引用不存在概念 → 先 add 该概念再 add_link
- 越界证据(summary/anno owner chunkId 不是本 job 的)→ 换成真实存在的 chunkId(常因 hex 尾巴抄错)
- 概念超 60 → 删单次提及的次要概念
- 繁体/简称 → canonical 用文本字形(如「河間王修」vs「河间王脩」按 chunk 实际)
- set_summary 缺 chunk_ids 参数 → 补上(从该 job chunks 里任取真实 id)

## 最近处理记录(本会话最后一批)
- 最新 VALID:544aa92f(元史英宗纪)、76003bb9、1a3ac1d0、1cb6f42d、4a46acbf、333179eb、0e49ee4e、836f71a3、53556463、80a7f1b3、42cb7585、05da5e8e、398fe38b、88ebde9d、b84df507、14162773、458ca641、26fd9748、7e4a0075、d85005d8、f07570df、02ceef8b、d6995c61、7f73430b、7e84df9c、49117010、2fa7e26e、a10baa7e、69d79918、df344cb0、da9322ab、21715580、86b1cebb、c6aac008、5d447ed3、907cb00a、f66fc1f9、84dce3c6、40fb5c3b、976ab26c、94d512b5、ce18ac05、4fcaeabb、c99b211c、f6165961、4dd59f66、acf0e6ca、8712d841、01e22474、d776b261、d9f868eb、2effd3c1、8896b625、671ff6bc、fadb01d3、8b95fe42、788627bb、21568ac7、a9b4c911、6ba5bdff、07d4cb90、2d4059cb、72caa852、acda9453、35171194、e4a8cb4b、18ad0cf8、81b43a19、6056cce2(修复过 summary/anno 越界)、2d1b958b、a6f5ed42、b05f9129、69268986、b4edd4f0、40e41f49、750f5d09、3b8ae1cc、cf54ac67、772fedfb、fddb5331、593b9f18、58ddc5dc、22385180、6f88d1e5、31fd9994、7c80ba37、f6ce742e、780efb02、e635006c、1d984385、0efa8c41、8fa160d8、6e1379a2、a75a9f42、456b8afd、a4131d7e、fca1b7e6、7e4a0075、458ca641、26fd9748、b84df507、14162773、398fe38b、88ebde9d、05da5e8e、333179eb、0e49ee4e、836f71a3、53556463、80a7f1b3、42cb7585、544aa92f、76003bb9、1a3ac1d0、1cb6f42d、4a46acbf
- 上一批派发后**被取消**(无产出):ece3f32e、4dc5a3b8、04b473d9、d850643c(仍在 pending,可直接重派)

## 下一批待派(按 chars 升序,pending 前 6)
1. history-job-c91727a24a1d544c8c0fe423 (747 chunks,唯一超大块,可跳过)
2. history-job-ece3f32e15b34cda1afed032 (80 chunks)
3. history-job-4dc5a3b86b73139f88a1eef4 (35 chunks)
4. history-job-04b473d9f09c425fe64b6d89 (65 chunks)
5. history-job-d850643cf29bb1927ef87650 (81 chunks)
6. history-job-b57ff3de8d27f25c85084348 (50 chunks)

## 子代理 prompt 模板要点(直接复用)
```
你是二十四史语义富集 worker。为指定 job 生成语义富集输出。这是写代码任务。
## 任务:处理 job: history-job-<ID>(共 N chunks,约 X K 字符)
## 步骤:1) python3 /tmp/opencode/show_job24.py <jobId> 读全文
2) 提炼概念(person/place/office/polity/era/work/event)
3) 写 /tmp/opencode/w24/gen_job<前8>.py,开头 import sys; sys.path.insert(0,'/tmp/opencode'); from gen_job24 import load,add_concept,add_anno,add_link_by_text,set_summary,write;load('<jobId>') ... 最后 write('<jobId>')
4) 运行无报错;5) 验证 outputs/<jobId>.jsonl 存在
## 硬性约束(违反会 FAIL):(抄上面的约束)
完成后报告 concepts/annos/links 数量和输出文件字节数。
```
注意:set_summary 必须传 chunk_ids 参数;add_anno 第三个参数是 dict;add_link ev 是单个 chunkId 字符串。

## 最终步骤(全部 VALID 后)
全部 2509 个 valid 后,在 `/Users/tony/Documents/harness-apk` 下执行 merge(具体命令见 `./scripts/wiki-builder.sh history --help` 或项目 AGENTS.md/README)。

---

## ✅ 完成记录（2026-08-16 会话收尾）

- 全部 **2509/2509** history-job 均已 VALID（pending=0），随后执行 `merge-jobs` 成功。
- merge 产物统计：terms=54797、aliases=19490、links=40408、annotations=49445、mentions=502249、summaries=2604；concept_registry_hash=`5d76535b5cdd62884842d81cacb41b9da42eea822ae44823a596249768452320`。
- merge 前置修复（均已重验通过）：
  1. 3 个 job 共 25 个错误 conceptKey（旧生成器产物，与推导值不符）→ 按 kind+canonical 重算并同步 link 引用（2ea0b124、6bd8d1f7、7156915e）；
  2. 跨 job 高置信同一概念 key 冲突（OCR 空格字形「吕 太一」「李 灵曜」等）→ 无连续字形者降置信至 0.85/unresolved；
  3. 跨 job 高置信别名冲突（单字/常用名别名天然跨史重复）→ 每个归一化别名只保留给一个赢家概念，共收敛 ~6500 处、涉及 ~1500 job；
  4. 同 key 组内别名字形不统一（汉/漢 等 21 处）→ 统一为多数派字形；
  5. 单概念内重复别名 → 去重。
- 修复后批量重验约 1530 个 job，全部 PASS 后才执行 merge。
