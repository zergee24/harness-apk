# Harness Two History `.hwiki` Packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, evaluate, sign, install, and acceptance-test two private local schema-v1 knowledge packages: one `.hwiki` containing all Twenty-Four Histories as 24 documents and one `.hwiki` containing all 294 volumes of Zizhi Tongjian as one document hierarchy.

**Architecture:** Add a history profile beside the generic desktop builder. Deterministic adapters convert the pinned local HTML and paired Markdown repositories into structured source records, then reuse the generic SQLite/schema/signing pipeline. Semantic enrichment is resumable and evidence-linked across both workspaces through one versioned `cn-history-v1` concept registry. Real source, workspaces, keys, evaluation answers, and packages stay outside Git; only reusable adapters, tests, and Skill guidance enter the repository.

**Tech Stack:** Python 3.12+, stdlib `html.parser`, existing `tools/wiki_builder`, JSONL, SQLite FTS4, Ed25519, Codex desktop as an explicitly approved semantic-enrichment worker, Android debug build and `adb` for acceptance.

**Authoritative Spec:** `docs/superpowers/specs/2026-07-22-offline-hwiki-knowledge-package-design.md`. If implementation evidence requires changing a product boundary, amend and re-approve the Spec before changing this plan.

## Global Constraints

- This is work package 5 of 5 and begins only after work packages 1-4 pass their final verification tasks.
- Canonical source inputs are currently:
  - `/Volumes/game/books/二十四史/china-history` at Git revision `3c83b6527c3815adfa8956dbbe80d1b38c9fc23c`.
  - `/Volumes/game/books/资治通鉴/zizhitongjian` at Git revision `cac4168cfce75bc0c78133b01fa04f03c1c02667`.
- Re-inventory the paths and revisions at execution time. A changed revision is a new input requiring a fresh lock, not an automatic substitution.
- The Twenty-Four Histories repository currently has no root license file. The Zizhi Tongjian repository has a GPL-3.0 license file, but the build must not infer that every embedded modern translation has independently verified distribution rights.
- Before processing real source, require one explicit rights record for each input, including purpose and whether distribution is allowed. An agent must not manufacture the user's confirmation or legal basis.
- Version 1 output purpose is `private-local-install`; `distributionAllowed=false`. No task uploads either package, source text, workspace, report, or key to OSS, GitHub Releases, object storage, or a catalog.
- Include complete classical source text. Exclude Twenty-Four Histories `-白话`, `-译文`, and `-段译` files and exclude the interleaved modern Zizhi Tongjian translation from version 1.
- Excluding modern translations is a versioned product choice, not a claim about their legal status or quality. Record it in each build report.
- Preserve source text exactly after HTML entity decoding, Unicode newline normalization, and removal of structural indentation. Search normalization is stored separately and never replaces source text.
- Package IDs and versions are exactly:
  - `cn.history.twenty-four-histories`, version `1`, title `二十四史`.
  - `cn.history.zizhi-tongjian`, version `1`, title `资治通鉴`.
- Both manifests use `conceptNamespace=cn-history-v1` and the exact same concept-registry hash.
- Capabilities are truthful: `generatedPages=none`, `claimGraph=false`, `vectorIndex=false`, and `sourceAttachments=false` unless a validated implementation plan explicitly changes a later version.
- Build full original source plus the phase-one retrieval semantic layer. Do not offer a “balanced” package that silently omits source; optimization happens in semantic density and mobile retrieval budgets.
- Use TDD and create scoped Chinese commits for reusable code tasks. Real-source build outputs are not committed and do not get a source-content commit.

## External Workspace Layout

```text
/Volumes/game/books/wiki-build/
  rights-confirmation.json
  source-lock.json
  concept-registry/cn-history-v1.jsonl
  twenty-four-histories-v1/
    source-records.jsonl
    source-map.jsonl
    enrichment/
    evaluation/
    reports/
    content.sqlite
  zizhi-tongjian-v1/
    source-records.jsonl
    source-map.jsonl
    enrichment/
    evaluation/
    reports/
    content.sqlite
/Volumes/game/books/wikis/
  cn.history.twenty-four-histories-v1.hwiki
  cn.history.zizhi-tongjian-v1.hwiki
```

The signing key is `/Users/tony/.config/harness-apk/keys/local-wiki-publisher.pem`, mode `0600`, and never enters either workspace.

---

### Task 1: Add Source Inventory, Rights, And Revision Gates

**Files:**
- Create: `tools/wiki_builder/history/__init__.py`
- Create: `tools/wiki_builder/history/source_inventory.py`
- Create: `tools/wiki_builder/history/rights.py`
- Create: `tools/wiki_builder/history/tests/__init__.py`
- Create: `tools/wiki_builder/history/tests/test_source_inventory.py`
- Create: `tools/wiki_builder/history/tests/test_rights.py`
- Modify: `tools/wiki_builder/cli.py`
- Modify: `.agents/skills/wiki-builder/SKILL.md`

**Interfaces:**
- Produces `SourceInventory`, `SourceLock`, `RightsConfirmation`, `inventory_history_sources`, and `verify_build_rights`.
- Adds CLI commands `history inventory` and `history verify-rights`.

- [ ] **Step 1: Write failing revision and rights tests**

```python
class RightsTest(unittest.TestCase):
    def test_private_build_requires_explicit_confirmation_for_every_source(self):
        confirmation = RightsConfirmation.from_dict({
            "purpose": "private-local-install",
            "distributionAllowed": False,
            "sources": [{
                "sourceId": "twenty-four-histories",
                "gitRevision": "a" * 40,
                "userConfirmed": True,
                "basis": "user-provided local source for private installation",
            }],
        })
        with self.assertRaisesRegex(RightsError, "zizhi-tongjian"):
            verify_build_rights(confirmation, REQUIRED_SOURCE_LOCK)

    def test_distribution_is_blocked_when_any_source_disallows_it(self):
        with self.assertRaisesRegex(RightsError, "distributionAllowed=false"):
            verify_build_rights(PRIVATE_CONFIRMATION, REQUIRED_SOURCE_LOCK, distribution=True)
```

- [ ] **Step 2: Run tests and verify missing history profile**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_source_inventory tools.wiki_builder.history.tests.test_rights -v`

Expected: `ERROR` importing `tools.wiki_builder.history`.

- [ ] **Step 3: Implement a content-addressed source lock**

Inventory records canonical path, Git remote, exact revision, dirty-state flag, relevant-file count, byte count, deterministic tree hash over relative path plus SHA-256, discovered license paths and hashes, and timestamp only in the human report. The canonical `source-lock.json` excludes timestamps so repeated inventory is byte-identical.

Reject missing Git repositories, dirty relevant files, symlinks escaping the source root, case-folded duplicate paths, unreadable files, wrong encodings, and revision changes not explicitly accepted into a new lock.

- [ ] **Step 4: Implement an explicit, non-self-certifying rights record**

The builder accepts an existing JSON record but cannot create `userConfirmed=true` itself. `history verify-rights` validates source IDs and revisions against the lock, requires a nonblank basis, records `private-local-install`, and rejects distribution unless every source has `distributionAllowed=true` plus at least one evidence path or URL.

The Skill asks one combined question only when this file is absent or mismatched: confirm the two named source revisions, private-local purpose, and no distribution. It explains that the confirmation is a workflow record, not legal advice.

- [ ] **Step 5: Run inventory and rights tests**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_source_inventory tools.wiki_builder.history.tests.test_rights -v`

Expected: all tests pass, including modified source, missing license, private use, and blocked distribution.

- [ ] **Step 6: Commit source gates**

```bash
git add tools/wiki_builder/history tools/wiki_builder/cli.py .agents/skills/wiki-builder/SKILL.md
git commit -m "功能：增加史书来源与使用权门禁"
```

### Task 2: Convert Twenty-Four Histories HTML Into Structured Source Records

**Files:**
- Create: `tools/wiki_builder/history/twenty_four_histories.py`
- Create: `tools/wiki_builder/history/models.py`
- Create: `tools/wiki_builder/history/tests/fixtures/twenty-four/史记/十二本纪/第一章-五帝本纪-原文.html`
- Create: `tools/wiki_builder/history/tests/fixtures/twenty-four/史记/史记.html`
- Create: `tools/wiki_builder/history/tests/fixtures/twenty-four/史记-白话/第一章-五帝本纪-译文.html`
- Create: `tools/wiki_builder/history/tests/test_twenty_four_histories.py`
- Modify: `tools/wiki_builder/cli.py`

**Interfaces:**
- Produces `HistorySourceRecord`, `HistoryDocumentRecord`, `HistorySectionRecord`, `HistoryParagraphRecord`, and `prepare_twenty_four_histories(source, workspace, lock)`.
- Adds CLI command `history prepare-twenty-four`.

- [ ] **Step 1: Write parser and completeness tests**

Test nested navigation exclusion, body paragraph preservation, HTML entities, curly/straight quotation marks, empty paragraph removal, chapter order from index links, duplicate chapter link, unlinked source file, missing source file, path traversal link, `-白话/-译文/-段译` exclusion, stable IDs, and identical output across two runs.

- [ ] **Step 2: Run focused tests and verify missing adapter**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_twenty_four_histories -v`

Expected: `ERROR` importing the adapter.

- [ ] **Step 3: Define the canonical 24-document allowlist**

Use exactly this order:

```python
TWENTY_FOUR_HISTORIES = (
    "史记", "汉书", "后汉书", "三国志", "晋书", "宋书", "南齐书", "梁书",
    "陈书", "魏书", "北齐书", "周书", "隋书", "南史", "北史", "旧唐书",
    "新唐书", "旧五代史", "新五代史", "宋史", "辽史", "金史", "元史", "明史",
)
```

Each item becomes one `documents` row. Relative directories beneath it form intermediate sections; each original HTML chapter forms a leaf section. Do not infer a 25th document from arbitrary top-level directories.

- [ ] **Step 4: Parse only source-bearing body content**

Use a strict `HTMLParser` state machine. Accept UTF-8 HTML, one source `<h1>`, and direct body source `<p>` elements after the heading; exclude navigation containers and trailing previous/next controls. Decode entities through the parser, preserve punctuation, normalize CRLF to LF, and fail when visible nonnavigation content is discarded.

- [ ] **Step 5: Preserve stable readable locators**

For every paragraph record, store document title, relative category path, clean chapter title, one-based paragraph number, original relative HTML path, and source hash. IDs derive from package ID, source revision, canonical relative path, and paragraph ordinal, not absolute volume path or extraction time.

- [ ] **Step 6: Enforce full pinned-source consumption**

At the currently pinned revision, expect 24 documents and 2,482 `*-原文.html` files. The adapter derives these counts from the source lock and fails on missing, duplicate, unlinked, empty, or unexpectedly included files. A future accepted revision updates the lock and reviewed expected inventory; it does not silently reuse `2,482`.

- [ ] **Step 7: Populate the generic workspace**

Write canonical `source-records.jsonl` and `source-map.jsonl`, then call the generic builder's structured population API to create documents, sections, chunks, source locators, original/normalized text, and both source FTS channels. No real source text enters repository fixtures beyond the tiny test excerpt.

- [ ] **Step 8: Run adapter tests**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_twenty_four_histories -v`

Expected: all tests pass and fixture rebuild hashes are identical.

- [ ] **Step 9: Commit the HTML adapter**

```bash
git add tools/wiki_builder/history tools/wiki_builder/cli.py
git commit -m "功能：转换二十四史原文结构"
```

### Task 3: Extract Classical Zizhi Tongjian From Paired Markdown

**Files:**
- Create: `tools/wiki_builder/history/zizhi_tongjian.py`
- Create: `tools/wiki_builder/history/tests/fixtures/zizhi/SUMMARY.md`
- Create: `tools/wiki_builder/history/tests/fixtures/zizhi/chapters/001_资治通鉴第一卷(周纪).md`
- Create: `tools/wiki_builder/history/tests/test_zizhi_tongjian.py`
- Modify: `tools/wiki_builder/cli.py`

**Interfaces:**
- Produces `prepare_zizhi_tongjian(source, workspace, lock, include_translation=False)`.
- Adds CLI command `history prepare-zizhi-tongjian`; version 1 rejects `--include-translation` unless a new reviewed package version and rights record authorize it.

- [ ] **Step 1: Write paired-structure and source-only tests**

Test numeric volume ordering, filename/title mismatch, missing SUMMARY entry, duplicate volume, 294-volume completeness, classical/translation pairing, `[n]` marker alignment, year-heading pair, `臣光曰` passage, odd paragraph count, malformed pair, source-only output, translation hash audit, stable section IDs, and locator validity.

- [ ] **Step 2: Run focused tests and verify missing adapter**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_zizhi_tongjian -v`

Expected: `ERROR` importing the adapter.

- [ ] **Step 3: Parse authoritative volume order from `SUMMARY.md`**

Require exactly 294 unique chapter links numbered `001` through `294`. Resolve links under `chapters/` only, reject traversal and case-fold duplicates, and require each filename, first title line, and volume number to agree after known punctuation normalization. Do not use lexical filename order as authority.

- [ ] **Step 4: Validate classical/modern paragraph pairs before excluding translations**

Follow the repository's documented format: volume title, optional classical/modern time heading pair, then repeated classical/modern paragraph pairs. Validate compatible `[n]` markers and time headings. Save only the classical member as source text, but record the paired translation SHA-256 and exclusion reason in `source-map.jsonl` so accidental pair inversion is detectable.

- [ ] **Step 5: Build one document with 294 volume sections**

Create one `资治通鉴` document. Each volume is a second-level section; time headings create child sections; classical paragraphs become source records with volume number, era/year heading, paragraph ordinal, chapter path, and source hash. Keep commentaries such as `臣光曰` as ordinary classical source paragraphs.

- [ ] **Step 6: Populate and validate the generic workspace**

Write source records and call the same structured population API as Task 2. Fail unless every classical paragraph maps to a source locator, every chapter contributes text, and the extraction audit reports zero unclassified nonblank paragraphs.

- [ ] **Step 7: Run adapter tests**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_zizhi_tongjian -v`

Expected: all tests pass; no modern translation string appears in fixture `chunks.original_text`.

- [ ] **Step 8: Commit the paired Markdown adapter**

```bash
git add tools/wiki_builder/history tools/wiki_builder/cli.py
git commit -m "功能：提取资治通鉴古文原文"
```

### Task 4: Add Resumable History Semantic-Enrichment Jobs

**Files:**
- Create: `tools/wiki_builder/history/enrichment_jobs.py`
- Create: `tools/wiki_builder/history/history_profile.py`
- Create: `tools/wiki_builder/history/concept_registry.py`
- Create: `tools/wiki_builder/history/tests/test_enrichment_jobs.py`
- Create: `tools/wiki_builder/history/tests/test_concept_registry.py`
- Create: `.agents/skills/wiki-builder/references/history-retrieval-v1.md`
- Modify: `.agents/skills/wiki-builder/SKILL.md`
- Modify: `tools/wiki_builder/cli.py`

**Interfaces:**
- Adds `history create-jobs`, `history validate-job`, `history merge-jobs`, and `history validate-pair`.
- Produces immutable job inputs and strict outputs for summaries, terms, aliases, mentions, annotations, links, and shared concepts.
- History profile version is exactly `history-retrieval-v1`.

- [ ] **Step 1: Write interruption, evidence, and registry tests**

Cover stable job IDs, input-hash change, completed-job reuse, interrupted job, malformed JSONL, output not grounded in supplied chunk IDs, duplicate concept key, alias conflict, low-confidence link, cross-Wiki key agreement, mismatched registry hashes, and merge rollback.

- [ ] **Step 2: Run focused tests and verify missing orchestrator**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_enrichment_jobs tools.wiki_builder.history.tests.test_concept_registry -v`

Expected: `ERROR` importing enrichment modules.

- [ ] **Step 3: Create immutable, bounded semantic jobs**

Partition by stable section boundaries with at most 18,000 source characters per job and no paragraph splitting unless a single paragraph exceeds the bound. A job stores profile/prompt version, source chunk IDs/hashes, output schema, and predecessor context containing only parent summary plus bounded adjacent headings. Completed output is reused only when every input hash and version matches.

- [ ] **Step 4: Define required semantic depth**

Generate evidence-linked:

1. Leaf-section summaries and hierarchical rollups for volume/document routing.
2. Person, place, polity, office/title, era, work, and event terms.
3. Attested aliases including personal names, style names, posthumous names, temple names, offices, historical place names, and normalized forms.
4. Exact mentions with chunk and character offsets.
5. Temporal annotations with original expression, normalized interval when resolvable, confidence, and evidence.
6. Weak intra-Wiki and cross-Wiki links with relation kind, confidence, extractor version, and evidence.

Summaries and links never become final source evidence. Low-confidence terms remain searchable at reduced weight; low-confidence links cannot hard-filter retrieval.

- [ ] **Step 5: Build the shared concept registry conservatively**

Concept keys use `cn-history-v1:<kind>:<stable-slug-or-hash>`. Merge only high-confidence identity matches; retain unresolved candidates as separate keys with review state. Every nonmechanical merge records both Wiki evidence refs and decision provenance. The same frozen registry file is imported into both workspaces before package validation.

- [ ] **Step 6: Document Codex interaction and privacy boundary in the Skill**

The scripts themselves make no network calls. Before Codex processes real source jobs, the Skill states that bounded job text will be provided to the configured Codex service and asks once for approval if the current rights record does not already include `semanticProcessingApproved=true`. It recommends the complete classical source plus `history-retrieval-v1` semantic layer and explains that original PDF/EPUB carriers and modern translations are excluded.

Codex writes only strict job-output JSONL, validates it immediately, retries only the failed job, and never writes claims without supplied evidence refs.

- [ ] **Step 7: Run enrichment orchestration tests**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_enrichment_jobs tools.wiki_builder.history.tests.test_concept_registry -v`

Expected: all tests pass and a simulated interruption resumes only incomplete job IDs.

- [ ] **Step 8: Commit enrichment orchestration**

```bash
git add tools/wiki_builder/history tools/wiki_builder/cli.py .agents/skills/wiki-builder
git commit -m "功能：编排双史书语义加工"
```

### Task 5: Build Human-Verified Single-Wiki And Cross-Wiki Evaluation Sets

**Files:**
- Create: `tools/wiki_builder/history/evaluation.py`
- Create: `tools/wiki_builder/history/tests/test_history_evaluation.py`
- Create: `tools/wiki_builder/history/tests/fixtures/history-evaluation.jsonl`
- Modify: `tools/wiki_builder/validation.py`
- Modify: `tools/wiki_builder/cli.py`

**Interfaces:**
- Adds `history create-eval-template`, `history validate-eval`, and `history evaluate-pair`.
- Produces per-Wiki and cross-Wiki reports without embedding real-source gold text in Git.

- [ ] **Step 1: Write category, gold-evidence, and threshold tests**

Test missing categories, duplicate query, nonexistent gold chunk, unreviewed case, source-only keyword case, modern paraphrase case, alias case, time case, homonym case, multi-volume case, cross-Wiki comparison, evidence gap, no-result case, and each publication threshold boundary.

- [ ] **Step 2: Run focused tests and verify missing history evaluator**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_history_evaluation -v`

Expected: `ERROR` importing the evaluator.

- [ ] **Step 3: Require balanced manually reviewed sets**

Create at least 160 reviewed cases for each Wiki: 20 each for original keyword, modern paraphrase, alias/title/place, time expression, homonym disambiguation, multi-volume synthesis, known evidence gap, and clear no-result. Create at least 60 cross-Wiki cases: 20 mutual corroboration, 20 differing accounts or framing, 10 one-sided evidence gaps, and 10 no-result controls.

Every positive case stores exact gold chunk IDs and reviewer state; no-result cases store reviewed rationale. Generated questions are not publishable until manually marked reviewed.

- [ ] **Step 4: Enforce approved quality gates without lowering them**

- Single-Wiki gold `Recall@20 >= 0.90` overall.
- Every single-Wiki category `Recall@20 >= 0.85`.
- Cross-Wiki final-12 coverage of both sides' gold evidence `>= 0.90`.
- Direct quotation to saved original-source match `1.00`.
- Citation document/section/version/locator validity `1.00`.
- Invalid citation token visible pass-through `0.00`.

If a gate fails, preserve failure cases, fix parsing/enrichment/retrieval, rebuild affected assets, and rerun. Do not edit thresholds to publish.

- [ ] **Step 5: Run evaluator tests**

Run: `scripts/wiki-builder.sh -m unittest tools.wiki_builder.history.tests.test_history_evaluation -v`

Expected: all tests pass and an unreviewed or underrepresented set is nonpublishable.

- [ ] **Step 6: Commit evaluation tooling**

```bash
git add tools/wiki_builder/history tools/wiki_builder/validation.py tools/wiki_builder/cli.py
git commit -m "验证：建立双史书检索评测"
```

### Task 6: Prepare Both Real Source Workspaces

**Files:**
- External only: `/Volumes/game/books/wiki-build/rights-confirmation.json`
- External only: `/Volumes/game/books/wiki-build/source-lock.json`
- External only: both package workspaces.
- Do not modify repository files in this task.

- [ ] **Step 1: Confirm free space and immutable source revisions**

Run: `df -h /Volumes/game/books`

Expected: free space exceeds four times the combined relevant source size plus 10 GiB for staging and indexes.

Run: `git -C /Volumes/game/books/二十四史/china-history status --short`

Run: `git -C /Volumes/game/books/资治通鉴/zizhitongjian status --short`

Expected: no relevant source modifications.

- [ ] **Step 2: Inventory both repositories**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history inventory --twenty-four /Volumes/game/books/二十四史/china-history --zizhi /Volumes/game/books/资治通鉴/zizhitongjian --output /Volumes/game/books/wiki-build/source-lock.json`

Expected: lock reports 24 Twenty-Four Histories documents, 2,482 original HTML chapters, 294 Zizhi Tongjian volumes, exact Git revisions, and discovered license evidence without claiming unverified rights.

- [ ] **Step 3: Obtain and validate the user's rights record once**

The implementing worker presents the two source IDs, revisions, exclusions, `private-local-install`, `distributionAllowed=false`, and Codex semantic-processing boundary in one combined confirmation. After the user explicitly confirms, write the external record through the builder's validated input path; do not commit it.

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history verify-rights --lock /Volumes/game/books/wiki-build/source-lock.json --confirmation /Volumes/game/books/wiki-build/rights-confirmation.json`

Expected: exit code `0`; distribution check remains disabled.

- [ ] **Step 4: Prepare Twenty-Four Histories**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history prepare-twenty-four /Volumes/game/books/二十四史/china-history --lock /Volumes/game/books/wiki-build/source-lock.json --rights /Volumes/game/books/wiki-build/rights-confirmation.json --wiki-id cn.history.twenty-four-histories --title 二十四史 --version 1 --concept-namespace cn-history-v1 --output /Volumes/game/books/wiki-build/twenty-four-histories-v1`

Expected: 24 documents, 2,482 consumed chapter files, 100% classified source paragraphs, zero translation files, zero extraction errors, and an integrity-valid base database.

- [ ] **Step 5: Prepare Zizhi Tongjian**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history prepare-zizhi-tongjian /Volumes/game/books/资治通鉴/zizhitongjian --lock /Volumes/game/books/wiki-build/source-lock.json --rights /Volumes/game/books/wiki-build/rights-confirmation.json --wiki-id cn.history.zizhi-tongjian --title 资治通鉴 --version 1 --concept-namespace cn-history-v1 --output /Volumes/game/books/wiki-build/zizhi-tongjian-v1`

Expected: one document, 294 volume sections, 100% paired paragraph classification, complete classical source, zero modern translation chunks, and an integrity-valid base database.

- [ ] **Step 6: Compare two deterministic preparations**

Repeat both prepare commands into sibling `-rebuild-check` workspaces and compare canonical source-record, source-map, manifest-input, and SQLite logical-dump hashes.

Expected: all canonical hashes match. SQLite comparison uses normalized `.dump` output rather than file bytes when page-layout metadata differs.

No repository commit is created for real source workspaces.

### Task 7: Complete Semantic Enrichment And Freeze The Shared Registry

**Files:**
- External only: both `enrichment/` directories.
- External only: `/Volumes/game/books/wiki-build/concept-registry/cn-history-v1.jsonl`.
- Do not modify repository files in this task.

- [ ] **Step 1: Generate stable jobs for both workspaces**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history create-jobs /Volumes/game/books/wiki-build/twenty-four-histories-v1 --profile history-retrieval-v1`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history create-jobs /Volumes/game/books/wiki-build/zizhi-tongjian-v1 --profile history-retrieval-v1`

Expected: every source chunk belongs to one deterministic leaf-summary context and all job inputs pass evidence validation.

- [ ] **Step 2: Process jobs through the Wiki Builder Skill**

Use `.agents/skills/wiki-builder/SKILL.md` to process pending jobs in stable ID order. After each output, run `history validate-job`; retry only invalid jobs. Stop and report a real blocker when rights approval is absent, a source section is malformed, or repeated model output cannot meet the schema; do not skip the section.

Expected: all jobs are `VALID`, with no orphan summary, term, alias, mention, annotation, or link.

- [ ] **Step 3: Merge deterministic semantic assets per workspace**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history merge-jobs /Volumes/game/books/wiki-build/twenty-four-histories-v1`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history merge-jobs /Volumes/game/books/wiki-build/zizhi-tongjian-v1`

Expected: transactional merge succeeds; four FTS channels are populated and every generated owner resolves to source evidence.

- [ ] **Step 4: Review and freeze the shared concept registry**

Merge high-confidence cross-Wiki candidates, review collisions and homonyms, and write one canonical registry. Import that exact file into both workspaces.

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history validate-pair /Volumes/game/books/wiki-build/twenty-four-histories-v1 /Volumes/game/books/wiki-build/zizhi-tongjian-v1 --registry /Volumes/game/books/wiki-build/concept-registry/cn-history-v1.jsonl`

Expected: both manifests record the same registry SHA-256; dangling cross-Wiki candidates are reported as weak unresolved links, not silently merged.

- [ ] **Step 5: Re-run workspace structural validation**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder validate /Volumes/game/books/wiki-build/twenty-four-histories-v1`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder validate /Volumes/game/books/wiki-build/zizhi-tongjian-v1`

Expected: structural, evidence, hierarchy, locator, FTS, and SQLite checks pass; retrieval publication state may remain pending until Task 8.

No repository commit is created for generated semantic assets.

### Task 8: Review Evaluation Sets And Meet Publication Gates

**Files:**
- External only: both `evaluation/` directories and pair evaluation directory.
- Do not modify repository files in this task unless a reusable parser or retrieval defect is found and fixed through TDD.

- [ ] **Step 1: Create balanced templates from actual source IDs**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history create-eval-template /Volumes/game/books/wiki-build/twenty-four-histories-v1 --minimum-cases 160`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history create-eval-template /Volumes/game/books/wiki-build/zizhi-tongjian-v1 --minimum-cases 160`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history create-eval-template /Volumes/game/books/wiki-build/twenty-four-histories-v1 /Volumes/game/books/wiki-build/zizhi-tongjian-v1 --cross-wiki --minimum-cases 60`

Expected: category slots and candidate gold sources are created, all with `reviewed=false`.

- [ ] **Step 2: Manually verify every query and gold source**

Review source text and locators in the desktop workspace, correct query wording and gold chunk sets, record reviewer notes for evidence gaps/no-result cases, and set `reviewed=true` only after inspection. Generated answer confidence is not a substitute for source review.

- [ ] **Step 3: Validate set balance and evidence references**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history validate-eval /Volumes/game/books/wiki-build/twenty-four-histories-v1/evaluation`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history validate-eval /Volumes/game/books/wiki-build/zizhi-tongjian-v1/evaluation`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history validate-eval /Volumes/game/books/wiki-build/pair-evaluation`

Expected: all cases reviewed, minimum category counts met, every gold locator valid, and no duplicate normalized query.

- [ ] **Step 4: Run single and pair retrieval evaluation**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder validate /Volumes/game/books/wiki-build/twenty-four-histories-v1 --eval /Volumes/game/books/wiki-build/twenty-four-histories-v1/evaluation/cases.jsonl`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder validate /Volumes/game/books/wiki-build/zizhi-tongjian-v1 --eval /Volumes/game/books/wiki-build/zizhi-tongjian-v1/evaluation/cases.jsonl`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder history evaluate-pair /Volumes/game/books/wiki-build/twenty-four-histories-v1 /Volumes/game/books/wiki-build/zizhi-tongjian-v1 --evaluation /Volumes/game/books/wiki-build/pair-evaluation/cases.jsonl`

Expected: every approved threshold passes. Preserve detailed failures and repair causes rather than lowering gates.

- [ ] **Step 5: Run Android/Python retrieval parity checks**

Export 40 deterministic queries spanning every category, run them through the Android `WikiRouter`/`WikiRetriever` fixture harness, and compare selected exact refs/chunk IDs with the Python evaluator under the same algorithm versions.

Expected: route selection matches for all 40; final evidence set differences are zero except explicitly documented stable tie ordering, which must then be aligned before publication.

No repository commit is created unless a code defect was fixed and verified.

### Task 9: Sign, Install, And Accept Both Real Packages

**Files:**
- External only: signing key and two `.hwiki` packages.
- External only: build reports under both workspaces.
- Do not commit artifacts.

- [ ] **Step 1: Create or inspect the local Ed25519 signing key**

Run when the key is absent: `mkdir -p /Users/tony/.config/harness-apk/keys && openssl genpkey -algorithm ED25519 -out /Users/tony/.config/harness-apk/keys/local-wiki-publisher.pem && chmod 600 /Users/tony/.config/harness-apk/keys/local-wiki-publisher.pem`

Expected: private key exists with mode `0600`; only the fingerprint enters build reports.

- [ ] **Step 2: Pack both validated workspaces**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder pack /Volumes/game/books/wiki-build/twenty-four-histories-v1 --output /Volumes/game/books/wikis --key /Users/tony/.config/harness-apk/keys/local-wiki-publisher.pem`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder pack /Volumes/game/books/wiki-build/zizhi-tongjian-v1 --output /Volumes/game/books/wikis --key /Users/tony/.config/harness-apk/keys/local-wiki-publisher.pem`

Expected: exactly two signed packages with schema v1 and matching concept-registry hashes.

- [ ] **Step 3: Verify package contents, signatures, and measured sizes**

Run: `ls -lh /Volumes/game/books/wikis/*.hwiki`

Run: `shasum -a 256 /Volumes/game/books/wikis/*.hwiki`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder inspect /Volumes/game/books/wikis/cn.history.twenty-four-histories-v1.hwiki`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder inspect /Volumes/game/books/wikis/cn.history.zizhi-tongjian-v1.hwiki`

Expected: signatures and checksums pass, payload allowlist is exact, and reports record actual compressed, installed, source, and semantic-index sizes. Report measured values; do not estimate them in advance.

- [ ] **Step 4: Build and install the Android test APK**

Run: `./gradlew assembleDebug`

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Expected: both commands succeed on the target Android device/emulator.

- [ ] **Step 5: Import both packages through the real app flow**

Use DocumentsUI or `ACTION_VIEW` to import each package, review the same publisher fingerprint, enable both for new conversations, and restart the app.

Expected: both remain installed and browseable; new conversation shows `自动 · 2`; no network is needed for directory, source search, routing, or citation opening.

- [ ] **Step 6: Execute the complete product acceptance set**

Verify directory browsing across all 24 histories and selected Zizhi Tongjian volumes, exact source search, alias/time routing, a single-book question, a two-book comparison, a one-turn `只看《资治通鉴》` override, no-result behavior, inline citation opening, old-version snapshot behavior using a fixture update, and project Markdown footnote review/apply.

Expected: all ten approved phase-one acceptance scenarios pass and every displayed quotation resolves to original source.

- [ ] **Step 7: Measure mobile performance and package storage**

Run the work package 3 benchmark with both real packages enabled.

Expected: warm route plus retrieval P95 at most 1,000 ms and cold first query for one package at most 2,000 ms on the target device. Record device model, Android version, package sizes, query set hash, and algorithm versions in the build report.

- [ ] **Step 8: Confirm private-local boundary and repository hygiene**

Run: `git status --short`

Expected: no source text, source lock, rights record, semantic output, evaluation set, private key, database, package, APK, or report is staged or untracked in the repository. Reusable implementation commits from Tasks 1-5 are the only repository changes.

Do not upload or push package artifacts. The final handoff gives the user the two local package paths, measured install sizes, signing fingerprint, build-report paths, test results, and known source limitations.
