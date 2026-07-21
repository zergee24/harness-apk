# Harness `.hwiki` Protocol And Desktop Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic M4 macOS toolchain that converts authorized local documents plus evidence-linked enrichment assets into a validated, signed schema-v1 `.hwiki` fixture.

**Architecture:** Add a separate `tools/wiki_builder` package that reuses the existing bounded document extraction functions but owns the generic Wiki schema, SQLite writer, enrichment importer, validator, evaluator, and CLI. Promote canonical JSON and Ed25519 ZIP writing into a shared package utility so `.hagent` and `.hwiki` cannot drift cryptographically. Semantic generation remains an external Codex step that writes strict JSONL assets; every publish decision is deterministic and testable.

**Tech Stack:** Python 3.12+, stdlib `sqlite3`, `unittest`, `cryptography`, existing `pypdf` extractors, POSIX shell, Ed25519, SQLite FTS4.

**Authoritative Spec:** `docs/superpowers/specs/2026-07-22-offline-hwiki-knowledge-package-design.md`. If implementation evidence requires changing a product boundary, amend and re-approve the Spec before changing this plan.

## Global Constraints

- Desktop target is Apple M4 Mac mini on macOS, native `darwin/arm64`; do not add Windows, Linux, Intel, GUI, or daemon support.
- `.hwiki` schema version is exactly `1`; package payload is `manifest.json`, `content.sqlite`, `checksums.json`, and `signature.json` only for phase one.
- Package content is declarative and non-executable; reject scripts, dynamic libraries, Provider configuration, absolute paths, parent traversal, duplicate paths, and symlinks.
- Complete displayable source text is mandatory; normalized text and 2/3-gram search fields are additional search assets, never citation text.
- `content.sqlite` must use Android-compatible SQLite plus FTS4; do not add a vector database, embedding dependency, or GraphRAG runtime.
- Summaries, terms, aliases, mentions, annotations, and links must resolve through `evidence_refs` to real source chunks.
- The builder never downloads source books, uploads source text, copies private keys into a workspace, or commits workspaces and built packages.
- Original PDF/EPUB/scan carriers are excluded from the phase-one `.hwiki`; the package contains complete extracted source text.
- Default package capabilities must truthfully declare `generatedPages=none` unless validated page rows exist.
- Use TDD and create one scoped Chinese commit at the end of every task; do not push.

## Plan Series

This is work package 1 of 5. It produces the fixture consumed by:

1. `2026-07-22-hwiki-android-library.md`
2. `2026-07-22-hwiki-chat-retrieval.md`
3. `2026-07-22-hwiki-project-integration.md`
4. `2026-07-22-hwiki-two-history-build.md`

---

### Task 1: Promote The Shared Signed-Package Writer

**Files:**
- Create: `tools/package_format.py`
- Create: `tools/tests/__init__.py`
- Create: `tools/tests/test_package_format.py`
- Modify: `tools/agent_builder/builder.py:1850-2090,2190-2230,2358-2362`
- Test: `tools/agent_builder/tests/test_builder.py`

**Interfaces:**
- Consumes: `cryptography.hazmat.primitives.asymmetric.ed25519.Ed25519PrivateKey`, payload values typed as `bytes | pathlib.Path`, and optional expected `(sha256, size)` identities for path payloads.
- Produces: `canonical_json_bytes(value: Any) -> bytes`, `load_ed25519_private_key(path: Path) -> Ed25519PrivateKey`, `write_signed_package`, `write_signed_package_streaming`, and `measure_signed_package`.

- [ ] **Step 1: Write failing shared-package tests**

```python
class PackageFormatTest(unittest.TestCase):
    def test_signed_package_is_deterministic_and_verifiable(self):
        key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
        first = self.root / "first.hwiki"
        second = self.root / "second.hwiki"
        files = {
            "manifest.json": canonical_json_bytes({"schemaVersion": 1, "type": "hwiki"}),
            "content.sqlite": b"sqlite-fixture",
        }

        write_signed_package(first, files, key)
        write_signed_package(second, files, key)

        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            checksums = json.loads(archive.read("checksums.json"))
            signature = json.loads(archive.read("signature.json"))
            key.public_key().verify(
                base64.b64decode(signature["signature"], validate=True),
                archive.read("checksums.json"),
            )
            self.assertEqual(
                hashlib.sha256(archive.read("content.sqlite")).hexdigest(),
                checksums["files"]["content.sqlite"],
            )
            for info in archive.infolist():
                self.assertEqual((2020, 1, 1, 0, 0, 0), info.date_time)
                self.assertEqual(3, info.create_system)
                self.assertEqual(0o100644, info.external_attr >> 16)

        streamed = self.root / "streamed.hwiki"
        write_signed_package_streaming(streamed, files, key)
        measured_hash, measured_size = measure_signed_package(files, key)
        self.assertEqual(hashlib.sha256(streamed.read_bytes()).hexdigest(), measured_hash)
        self.assertEqual(streamed.stat().st_size, measured_size)
        with zipfile.ZipFile(streamed) as streamed_archive, zipfile.ZipFile(first) as first_archive:
            self.assertEqual(
                streamed_archive.read("checksums.json"),
                first_archive.read("checksums.json"),
            )

    def test_signed_package_rejects_reserved_or_unsafe_paths(self):
        key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
        for path in ("checksums.json", "../escape", "/absolute", "a\\b"):
            with self.subTest(path=path), self.assertRaises(PackageFormatError):
                write_signed_package(self.root / f"{len(path)}.zip", {path: b"x"}, key)
```

- [ ] **Step 2: Run the focused tests and verify the import fails**

Run: `scripts/agent-builder.sh -m unittest tools.tests.test_package_format -v`

Expected: `ERROR` with `ModuleNotFoundError: No module named 'tools.package_format'`.

- [ ] **Step 3: Implement the shared writer with deterministic ZIP metadata**

```python
ZIP_TIMESTAMP = (2020, 1, 1, 0, 0, 0)
RESERVED_PATHS = {"checksums.json", "signature.json"}


class PackageFormatError(ValueError):
    pass


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def write_signed_package(
    target: Path,
    files: Mapping[str, bytes | Path],
    private_key: Ed25519PrivateKey,
) -> Path:
    normalized = _validated_payloads(files)
    checksums = {
        path: _sha256_payload(payload)
        for path, payload in sorted(normalized.items())
    }
    checksums_bytes = canonical_json_bytes({"files": checksums})
    public_key = private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    signature_bytes = canonical_json_bytes({
        "algorithm": "Ed25519",
        "publicKey": base64.b64encode(public_key).decode("ascii"),
        "signature": base64.b64encode(private_key.sign(checksums_bytes)).decode("ascii"),
        "signedFile": "checksums.json",
    })
    payloads = {
        **normalized,
        "checksums.json": checksums_bytes,
        "signature.json": signature_bytes,
    }
    try:
        with zipfile.ZipFile(target, "x", zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=True) as archive:
            for path, payload in sorted(payloads.items()):
                info = zipfile.ZipInfo(path, ZIP_TIMESTAMP)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                info.create_system = 3
                with archive.open(info, "w") as output:
                    if isinstance(payload, bytes):
                        output.write(payload)
                    else:
                        with payload.open("rb") as source:
                            shutil.copyfileobj(source, output, length=1024 * 1024)
    except BaseException:
        target.unlink(missing_ok=True)
        raise
    return target
```

The abbreviated function above shows the shared preparation and ZIP metadata contract. Implement byte entries with `ZipFile.writestr(..., compresslevel=9)` and path entries with descriptor-anchored streaming exactly as the existing Agent V2 writer does, so extraction does not change archive bytes. `write_signed_package_streaming` uses the same prepared payloads with the existing nonseekable writer; `measure_signed_package` writes those payloads to the existing hashing/counting writer. Keep path validation strict: POSIX relative paths only, no empty segment, `.` or `..`, NUL, backslash, duplicate normalized path, directory entry, or reserved path. Re-stat `Path` payloads before and after streaming and fail closed if identity, size, or mtime changes.

- [ ] **Step 4: Make the agent builder delegate without changing its public output**

```python
from tools.package_format import (
    canonical_json_bytes as _shared_canonical_json_bytes,
    load_ed25519_private_key as _shared_load_private_key,
    measure_signed_package as _shared_measure_signed_package,
    write_signed_package as _shared_write_signed_package,
    write_signed_package_streaming as _shared_write_signed_package_streaming,
)


def _canonical_json_bytes(value: Any) -> bytes:
    return _shared_canonical_json_bytes(value)


def _load_private_key(path: Path) -> Ed25519PrivateKey:
    try:
        return _shared_load_private_key(path)
    except PackageFormatError as error:
        raise BuildError(str(error)) from error
```

Make `_write_signed_package_v2`, `_write_signed_package_v2_streaming`, and `_measure_signed_package_v2` thin error-mapping delegates to the shared functions. Delete the duplicate preparation, ZIP metadata, hash, and path-writing implementation only after all Agent callers delegate. Do not alter package manifests, profiles, entry order, compression level, expected-file checks, timestamps, Unix modes, or public errors.

- [ ] **Step 5: Run shared and agent regression tests**

Run: `scripts/agent-builder.sh -m unittest tools.tests.test_package_format tools.agent_builder.tests.test_builder tools.agent_builder.tests.test_fixture_v2 -v`

Expected: all tests `OK`; checked-in/expected Agent fixture hashes do not change, repeated seekable outputs remain byte-identical, and streaming output agrees byte-for-byte with measured hash/size. Seekable and streaming ZIP containers may differ while their signed payload entries remain identical.

- [ ] **Step 6: Commit the shared package utility**

```bash
git add tools/package_format.py tools/tests tools/agent_builder/builder.py
git commit -m "重构：统一知识包签名格式"
```

### Task 2: Define Schema V1 And Create Android-Compatible SQLite

**Files:**
- Create: `tools/wiki_builder/__init__.py`
- Create: `tools/wiki_builder/models.py`
- Create: `tools/wiki_builder/schema.py`
- Create: `tools/wiki_builder/sqlite_schema.py`
- Create: `tools/wiki_builder/tests/__init__.py`
- Create: `tools/wiki_builder/tests/test_schema.py`
- Create: `tools/wiki_builder/tests/test_sqlite_schema.py`

**Interfaces:**
- Consumes: canonical Python dictionaries and an output `Path`.
- Produces: `WikiManifest.from_dict`, `WikiCapabilities.from_dict`, `create_content_database(path: Path) -> sqlite3.Connection`, `validate_sqlite_shape(connection) -> None`, and `CONTENT_SCHEMA_VERSION = 1`.

- [ ] **Step 1: Write failing manifest and schema tests**

```python
def fixture_manifest() -> dict[str, object]:
    return {
        "type": "hwiki",
        "schemaVersion": 1,
        "wiki": {
            "id": "fixture.history",
            "version": 1,
            "title": "史料测试库",
            "language": ["zh-Hant", "zh-Hans"],
            "description": "用于协议测试",
            "contentHash": "0" * 64,
        },
        "publisher": {"keyId": "fixture", "name": "测试发布者"},
        "capabilities": {
            "sourceHierarchy": True,
            "sourceSearch": True,
            "hierarchicalSummaries": True,
            "termIndex": True,
            "temporalAnnotations": True,
            "crossWikiLinks": False,
            "generatedPages": "none",
            "claimGraph": False,
            "vectorIndex": False,
            "sourceAttachments": False,
        },
        "conceptNamespace": "fixture-v1",
        "conceptRegistryHash": "0" * 64,
        "builder": {"name": "harness-wiki-builder", "version": "1", "profile": "generic-v1"},
    }


class SqliteSchemaTest(unittest.TestCase):
    def test_database_has_required_tables_and_fts4_channels(self):
        connection = create_content_database(self.root / "content.sqlite")
        names = {row[0] for row in connection.execute("SELECT name FROM sqlite_master")}
        self.assertTrue({
            "documents", "sections", "chunks", "summaries", "terms", "aliases",
            "mentions", "annotations", "links", "evidence_refs", "source_locators",
            "build_metadata", "chunks_original_fts", "chunks_normalized_fts",
            "summaries_fts", "terms_aliases_fts",
        }.issubset(names))
        self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 1)
```

- [ ] **Step 2: Run tests and verify missing modules fail**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_schema tools.wiki_builder.tests.test_sqlite_schema -v`

Expected: `ERROR` importing `tools.wiki_builder.schema`.

- [ ] **Step 3: Implement strict manifest dataclasses**

```python
@dataclass(frozen=True)
class WikiCapabilities:
    source_hierarchy: bool
    source_search: bool
    hierarchical_summaries: bool
    term_index: bool
    temporal_annotations: bool
    cross_wiki_links: bool
    generated_pages: Literal["none", "partial", "complete"]
    claim_graph: bool
    vector_index: bool
    source_attachments: bool


@dataclass(frozen=True)
class WikiManifest:
    wiki_id: str
    version: int
    title: str
    languages: tuple[str, ...]
    description: str
    content_hash: str
    publisher_key_id: str
    publisher_name: str
    capabilities: WikiCapabilities
    concept_namespace: str
    concept_registry_hash: str
    builder_profile: str
    schema_version: int = 1
```

Reject unknown schema versions, noncanonical identifiers, nonpositive versions, non-64-hex hashes, empty languages, `generatedPages` outside the three allowed values, `vectorIndex=true`, `sourceAttachments=true`, and `type != hwiki`.

- [ ] **Step 4: Implement the complete core DDL**

```python
CORE_DDL = """
PRAGMA foreign_keys=ON;
CREATE TABLE documents(
  document_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  responsibility TEXT NOT NULL,
  edition TEXT NOT NULL,
  language TEXT NOT NULL,
  rights TEXT NOT NULL,
  source_hash TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE sections(
  section_id TEXT PRIMARY KEY,
  document_id TEXT NOT NULL REFERENCES documents(document_id),
  parent_section_id TEXT REFERENCES sections(section_id),
  title TEXT NOT NULL,
  path TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE chunks(
  chunk_id TEXT PRIMARY KEY,
  section_id TEXT NOT NULL REFERENCES sections(section_id),
  ordinal INTEGER NOT NULL,
  original_text TEXT NOT NULL,
  normalized_text TEXT NOT NULL,
  original_ngrams TEXT NOT NULL,
  normalized_ngrams TEXT NOT NULL,
  locator_json TEXT NOT NULL,
  content_hash TEXT NOT NULL
);
CREATE TABLE summaries(summary_id TEXT PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, level TEXT NOT NULL, text TEXT NOT NULL);
CREATE TABLE terms(term_id TEXT PRIMARY KEY, concept_key TEXT NOT NULL, canonical_text TEXT NOT NULL, kind TEXT NOT NULL, confidence REAL NOT NULL, metadata_json TEXT NOT NULL);
CREATE TABLE aliases(alias_id TEXT PRIMARY KEY, term_id TEXT NOT NULL REFERENCES terms(term_id), alias_text TEXT NOT NULL, normalized_alias TEXT NOT NULL, confidence REAL NOT NULL);
CREATE TABLE mentions(mention_id TEXT PRIMARY KEY, term_id TEXT NOT NULL REFERENCES terms(term_id), chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id), start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, confidence REAL NOT NULL);
CREATE TABLE annotations(annotation_id TEXT PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, kind TEXT NOT NULL, value_json TEXT NOT NULL, confidence REAL NOT NULL);
CREATE TABLE links(link_id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_id TEXT NOT NULL, target_namespace TEXT NOT NULL, target_type TEXT NOT NULL, target_id TEXT NOT NULL, kind TEXT NOT NULL, confidence REAL NOT NULL);
CREATE TABLE evidence_refs(owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id), role TEXT NOT NULL, ordinal INTEGER NOT NULL, PRIMARY KEY(owner_type, owner_id, chunk_id));
CREATE TABLE source_locators(locator_id TEXT PRIMARY KEY, chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id), label TEXT NOT NULL, locator_json TEXT NOT NULL);
CREATE TABLE build_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE VIRTUAL TABLE chunks_original_fts USING FTS4(chunk_id, original_text, original_ngrams, tokenize=unicode61);
CREATE VIRTUAL TABLE chunks_normalized_fts USING FTS4(chunk_id, normalized_text, normalized_ngrams, tokenize=unicode61);
CREATE VIRTUAL TABLE summaries_fts USING FTS4(summary_id, text, tokenize=unicode61);
CREATE VIRTUAL TABLE terms_aliases_fts USING FTS4(owner_id, canonical_text, aliases_text, tokenize=unicode61);
PRAGMA user_version=1;
"""
```

Add deterministic indices for every foreign-key lookup and `(document_id, ordinal)`, `(parent_section_id, ordinal)`, `(section_id, ordinal)`, `terms(concept_key)`, `annotations(kind)`, and `links(target_namespace, target_id)`.

- [ ] **Step 5: Verify FTS4 works with pretokenized Chinese n-grams**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_schema tools.wiki_builder.tests.test_sqlite_schema -v`

Expected: all tests `OK`, including a test that inserts `司马光` plus `司马 马光` tokens and finds the row with `MATCH '司马'`.

- [ ] **Step 6: Commit schema v1**

```bash
git add tools/wiki_builder
git commit -m "功能：定义 hwiki 通用协议"
```

### Task 3: Build Deterministic Source Hierarchy And Chunks

**Files:**
- Create: `tools/wiki_builder/normalization.py`
- Create: `tools/wiki_builder/extractors.py`
- Create: `tools/wiki_builder/workspace.py`
- Create: `tools/wiki_builder/builder.py`
- Create: `tools/wiki_builder/tests/test_normalization.py`
- Create: `tools/wiki_builder/tests/test_builder.py`
- Reuse: `tools/agent_builder/extractors.py`

**Interfaces:**
- Consumes: authorized `.txt`, `.md`, `.markdown`, `.epub`, or text-layer `.pdf` paths.
- Produces: `prepare_workspace(inputs: Sequence[Path], output: Path, wiki_id: str, title: str, version: int, concept_namespace: str) -> Path` and a workspace containing `workspace.json`, `source-catalog.json`, `content.sqlite`, and `enrichment/` templates.

- [ ] **Step 1: Write deterministic ID, normalization, and extraction tests**

```python
class WikiBuilderTest(unittest.TestCase):
    def test_prepare_is_deterministic_and_preserves_original_text(self):
        source = self.root / "source.md"
        source.write_text("# 卷一\n\n司馬光曰：臣聞天子之職莫大於禮。", encoding="utf-8")
        first = prepare_workspace([source], self.root / "first", "fixture.history", "史料", 1, "fixture-v1")
        second = prepare_workspace([source], self.root / "second", "fixture.history", "史料", 1, "fixture-v1")

        self.assertEqual((first / "content.sqlite").read_bytes(), (second / "content.sqlite").read_bytes())
        with sqlite3.connect(first / "content.sqlite") as db:
            row = db.execute("SELECT original_text, normalized_text, normalized_ngrams FROM chunks").fetchone()
        self.assertEqual(row[0], "司馬光曰：臣聞天子之職莫大於禮。")
        self.assertIn("司马光", row[1])
        self.assertIn("司马", row[2].split())
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_normalization tools.wiki_builder.tests.test_builder -v`

Expected: `ERROR` importing `prepare_workspace`.

- [ ] **Step 3: Implement stable normalization and 2/3-gram generation**

```python
def normalize_for_search(text: str) -> str:
    value = unicodedata.normalize("NFKC", text)
    value = value.translate(TRADITIONAL_VARIANT_MAP)
    value = re.sub(r"[\s\u3000]+", "", value)
    return value.translate(PUNCTUATION_DELETE_TABLE)


def chinese_ngrams(text: str) -> tuple[str, ...]:
    compact = normalize_for_search(text)
    tokens = {compact[index:index + size]
              for size in (2, 3)
              for index in range(max(0, len(compact) - size + 1))}
    return tuple(sorted(token for token in tokens if any("\u3400" <= char <= "\u9fff" for char in token)))
```

Keep `TRADITIONAL_VARIANT_MAP` versioned and intentionally small in schema v1. Record its SHA-256 and `normalizationVersion=1` in `build_metadata`; do not claim full linguistic conversion.

- [ ] **Step 4: Wrap the existing bounded extractors**

```python
def extract_documents(paths: Sequence[Path]) -> Iterator[PreparedDocument]:
    for ordinal, path in enumerate(sorted_resolved_inputs(paths)):
        source_hash = sha256_file(path)
        sections = tuple(iter_source_sections(path))
        if not sections or not any(section.text.strip() for section in sections):
            raise BuildError(f"没有可提取文本：{path.name}")
        yield PreparedDocument(
            document_id=stable_id("doc", source_hash, path.name),
            title=path.stem,
            source_path=path,
            source_hash=source_hash,
            ordinal=ordinal,
            sections=sections,
        )
```

`iter_source_sections` must open a descriptor-anchored stream and call `iter_v2_source_sections_stream`; never call `read_text()` on a large source.

- [ ] **Step 5: Populate the base content database transactionally**

```python
with create_content_database(temp_database) as db:
    db.execute("BEGIN IMMEDIATE")
    for document in extract_documents(inputs):
        insert_document(db, document)
        for section in document.sections:
            insert_section_and_chunks(db, document, section)
    populate_source_fts(db)
    db.execute("INSERT INTO build_metadata VALUES (?, ?)", ("normalizationVersion", "1"))
    db.commit()
    db.execute("VACUUM")
os.replace(temp_database, workspace / "content.sqlite")
```

Chunk on paragraph boundaries with a soft target of 1,200 Chinese characters and hard maximum of 2,000. Preserve paragraph text exactly, use a 200-character overlap only in search fields, and never duplicate overlap in `original_text`.

- [ ] **Step 6: Run builder tests and inspect deterministic SQLite**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_normalization tools.wiki_builder.tests.test_builder -v`

Expected: all tests `OK`; `PRAGMA integrity_check` returns `ok`; repeated builds have identical SHA-256.

- [ ] **Step 7: Commit deterministic preparation**

```bash
git add tools/wiki_builder
git commit -m "功能：构建 hwiki 原文层级索引"
```

### Task 4: Import Evidence-Linked Enrichment Assets

**Files:**
- Create: `tools/wiki_builder/enrichment.py`
- Create: `tools/wiki_builder/tests/test_enrichment.py`
- Modify: `tools/wiki_builder/builder.py`
- Modify: `tools/wiki_builder/workspace.py`

**Interfaces:**
- Consumes: `enrichment/summaries.jsonl`, `terms.jsonl`, `aliases.jsonl`, `mentions.jsonl`, `annotations.jsonl`, `links.jsonl`, and `concept-registry.jsonl`.
- Produces: `import_enrichment(workspace: Path) -> EnrichmentStats`; all imported rows and FTS rows are committed atomically.

- [ ] **Step 1: Write failing evidence and rollback tests**

```python
class EnrichmentImportTest(unittest.TestCase):
    def test_import_requires_real_chunk_evidence(self):
        write_jsonl(self.workspace / "enrichment/summaries.jsonl", [{
            "id": "summary-volume-1",
            "ownerType": "section",
            "ownerId": self.section_id,
            "level": "volume",
            "text": "本卷记述礼制与名分。",
            "evidence": [self.chunk_id],
        }])
        stats = import_enrichment(self.workspace)
        self.assertEqual(stats.summaries, 1)

    def test_unknown_evidence_rolls_back_every_asset(self):
        write_jsonl(self.workspace / "enrichment/summaries.jsonl", [{
            "id": "bad", "ownerType": "section", "ownerId": self.section_id,
            "level": "volume", "text": "无效", "evidence": ["missing-chunk"],
        }])
        with self.assertRaisesRegex(BuildError, "missing-chunk"):
            import_enrichment(self.workspace)
        self.assertEqual(count_rows(self.workspace, "summaries"), 0)
```

- [ ] **Step 2: Run the test and verify the importer is missing**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_enrichment -v`

Expected: `ERROR` importing `tools.wiki_builder.enrichment`.

- [ ] **Step 3: Implement strict JSONL records and cross-file validation**

```python
@dataclass(frozen=True)
class EvidenceRefInput:
    chunk_id: str
    role: str = "support"


@dataclass(frozen=True)
class SummaryInput:
    summary_id: str
    owner_type: str
    owner_id: str
    level: str
    text: str
    evidence: tuple[EvidenceRefInput, ...]


def require_chunks(db: sqlite3.Connection, chunk_ids: Collection[str]) -> None:
    found = {row[0] for row in db.execute(
        f"SELECT chunk_id FROM chunks WHERE chunk_id IN ({','.join('?' for _ in chunk_ids)})",
        tuple(chunk_ids),
    )}
    missing = sorted(set(chunk_ids) - found)
    if missing:
        raise BuildError(f"enrichment 引用了不存在的 chunk：{', '.join(missing)}")
```

Validate mention offsets against `original_text`, confidence within `0..1`, alias ownership, owner existence, duplicate IDs, concept-key namespace, annotation JSON canonical form, and links with nonempty external target namespace.

- [ ] **Step 4: Import assets and rebuild semantic FTS in one transaction**

```python
with sqlite3.connect(workspace / "content.sqlite") as db:
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("BEGIN IMMEDIATE")
    clear_enrichment_tables(db)
    insert_summaries(db, assets.summaries)
    insert_terms_aliases_mentions(db, assets)
    insert_annotations_links(db, assets)
    insert_evidence_refs(db, assets.all_evidence())
    rebuild_summary_fts(db)
    rebuild_term_alias_fts(db)
    db.commit()
```

An empty optional asset file is valid, but every document and volume section must have a summary before `validate` can mark the history profile publishable.

- [ ] **Step 5: Run all enrichment tests**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_enrichment tools.wiki_builder.tests.test_sqlite_schema -v`

Expected: all tests `OK`, including rollback, offset, duplicate, orphan evidence, and registry-hash cases.

- [ ] **Step 6: Commit enrichment import**

```bash
git add tools/wiki_builder
git commit -m "功能：导入可追溯 Wiki 语义资产"
```

### Task 5: Add Validation And Retrieval Evaluation Gates

**Files:**
- Create: `tools/wiki_builder/validation.py`
- Create: `tools/wiki_builder/evaluation.py`
- Create: `tools/wiki_builder/tests/test_validation.py`
- Create: `tools/wiki_builder/tests/test_evaluation.py`
- Create: `tools/wiki_builder/tests/fixtures/retrieval-eval.jsonl`

**Interfaces:**
- Consumes: a prepared workspace and a JSONL retrieval evaluation set.
- Produces: `validate_workspace(workspace: Path) -> ValidationReport`, `evaluate_retrieval(database: Path, cases: Sequence[RetrievalCase]) -> RetrievalReport`, and process exit code `2` for a structurally valid but nonpublishable workspace.

- [ ] **Step 1: Write failing publication-gate tests**

```python
class ValidationTest(unittest.TestCase):
    def test_publishable_fixture_meets_all_hard_gates(self):
        report = validate_workspace(self.workspace)
        self.assertTrue(report.publishable, report.to_dict())
        self.assertEqual(report.integrity_check, "ok")
        self.assertEqual(report.orphan_evidence_count, 0)
        self.assertGreaterEqual(report.retrieval.overall_recall_at_20, 0.90)

    def test_missing_volume_summary_blocks_publish(self):
        delete_volume_summary(self.workspace)
        report = validate_workspace(self.workspace)
        self.assertFalse(report.publishable)
        self.assertIn("missing_volume_summary", report.error_codes)
```

- [ ] **Step 2: Run tests and verify missing validator failure**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_validation tools.wiki_builder.tests.test_evaluation -v`

Expected: `ERROR` importing the validator.

- [ ] **Step 3: Implement deterministic multi-channel retrieval evaluation**

```python
@dataclass(frozen=True)
class RetrievalCase:
    case_id: str
    category: str
    query: str
    expected_chunk_ids: frozenset[str]


def reciprocal_rank_fusion(rankings: Sequence[Sequence[str]], k: int = 60) -> list[str]:
    scores: dict[str, float] = defaultdict(float)
    for ranking in rankings:
        for rank, item_id in enumerate(ranking, start=1):
            scores[item_id] += 1.0 / (k + rank)
    return sorted(scores, key=lambda item_id: (-scores[item_id], item_id))
```

The evaluator must query original, normalized, summary, and alias channels, resolve summary/term hits back to evidence chunks, apply RRF, and calculate `Recall@20` overall plus per category. Do not use raw BM25 values across channels.

- [ ] **Step 4: Implement structural and evidence gates**

```python
HARD_GATES = {
    "integrity_check": lambda report: report.integrity_check == "ok",
    "foreign_key_check": lambda report: report.foreign_key_errors == 0,
    "source_coverage": lambda report: report.extracted_source_coverage == 1.0,
    "orphan_evidence": lambda report: report.orphan_evidence_count == 0,
    "locator_coverage": lambda report: report.invalid_locator_count == 0,
    "overall_recall": lambda report: report.retrieval.overall_recall_at_20 >= 0.90,
    "category_recall": lambda report: min(report.retrieval.category_recall.values()) >= 0.85,
}
```

Also reject triggers, unknown virtual-table modules, noncanonical metadata JSON, mutable schema, unreferenced generated assertions, missing source hashes, and capabilities that overstate actual tables.

- [ ] **Step 5: Run validation and evaluation tests**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_validation tools.wiki_builder.tests.test_evaluation -v`

Expected: all tests `OK`; a category below `0.85` yields `publishable=false` and exit code `2` without deleting the workspace.

- [ ] **Step 6: Commit quality gates**

```bash
git add tools/wiki_builder
git commit -m "验证：建立 hwiki 检索质量门槛"
```

### Task 6: Add CLI, Skill, Signed Fixture, And Build Report

**Files:**
- Create: `tools/wiki_builder/cli.py`
- Create: `tools/wiki_builder/__main__.py`
- Create: `tools/wiki_builder/reporting.py`
- Create: `tools/wiki_builder/tests/fixture.py`
- Create: `tools/wiki_builder/tests/test_cli.py`
- Create: `tools/wiki_builder/requirements.txt`
- Create: `scripts/wiki-builder.sh`
- Create: `.agents/skills/wiki-builder/SKILL.md`
- Create: `app/src/test/resources/wiki/source.md`

**Interfaces:**
- Consumes: CLI commands `prepare`, `enrich`, `validate`, `pack`, `inspect`, and fixture command `python -m tools.wiki_builder.tests.fixture`.
- Produces: a signed `.hwiki`, canonical `build-report.json`, readable `build-report.md`, and a Codex Skill that asks only for real blockers.

- [ ] **Step 1: Write failing CLI state-machine tests**

```python
class WikiBuilderCliTest(unittest.TestCase):
    def test_prepare_enrich_validate_pack_flow(self):
        self.assertEqual(main([
            "prepare", str(self.source), "--wiki-id", "fixture.history",
            "--title", "史料测试库", "--version", "1",
            "--concept-namespace", "fixture-v1", "--output", str(self.workspace),
        ]), 0)
        write_fixture_enrichment(self.workspace)
        self.assertEqual(main(["enrich", str(self.workspace)]), 0)
        self.assertEqual(main(["validate", str(self.workspace)]), 0)
        self.assertEqual(main([
            "pack", str(self.workspace), "--output", str(self.dist), "--key", str(self.key),
        ]), 0)
        self.assertTrue((self.dist / "fixture.history-v1.hwiki").is_file())
        self.assertEqual(main([
            "inspect", str(self.dist / "fixture.history-v1.hwiki"),
        ]), 0)

    def test_pack_refuses_nonpublishable_workspace(self):
        with self.assertRaisesRegex(BuildError, "validate 未通过"):
            pack_workspace(self.workspace, self.dist, self.key)
```

- [ ] **Step 2: Run the CLI tests and verify failure**

Run: `scripts/agent-builder.sh -m unittest tools.wiki_builder.tests.test_cli -v`

Expected: `ERROR` importing `tools.wiki_builder.cli`.

- [ ] **Step 3: Implement explicit CLI commands**

```python
prepare = subparsers.add_parser("prepare")
prepare.add_argument("inputs", nargs="+", type=Path)
prepare.add_argument("--wiki-id", required=True)
prepare.add_argument("--title", required=True)
prepare.add_argument("--version", required=True, type=int)
prepare.add_argument("--concept-namespace", required=True)
prepare.add_argument("--output", required=True, type=Path)

enrich = subparsers.add_parser("enrich")
enrich.add_argument("workspace", type=Path)

validate = subparsers.add_parser("validate")
validate.add_argument("workspace", type=Path)
validate.add_argument("--eval", type=Path)

pack = subparsers.add_parser("pack")
pack.add_argument("workspace", type=Path)
pack.add_argument("--output", required=True, type=Path)
pack.add_argument("--key", required=True, type=Path)

inspect = subparsers.add_parser("inspect")
inspect.add_argument("package", type=Path)
```

`pack` recalculates `contentHash` from the final SQLite bytes, writes canonical manifest and reports, then calls `write_signed_package`. It refuses a nonempty output collision and never generates a private key automatically. `inspect` reuses the package path/checksum/signature/schema allowlists, opens `content.sqlite` read-only from a temporary directory, prints canonical manifest/fingerprint/size/capability data, and removes its temporary files on success or failure.

- [ ] **Step 4: Add the shell wrapper**

```sh
#!/bin/sh
set -eu

CODEX_RUNTIME_PYTHON="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
PYTHON_BIN="${CODEX_PYTHON:-}"
[ -n "$PYTHON_BIN" ] || [ ! -x "$CODEX_RUNTIME_PYTHON" ] || PYTHON_BIN="$CODEX_RUNTIME_PYTHON"
[ -n "$PYTHON_BIN" ] || PYTHON_BIN="$(command -v python3 || true)"
[ -n "$PYTHON_BIN" ] || { echo "未找到 Python 3；请设置 CODEX_PYTHON。" >&2; exit 1; }

if [ "${1:-}" = "-m" ]; then exec "$PYTHON_BIN" "$@"; fi
exec "$PYTHON_BIN" -m tools.wiki_builder "$@"
```

Mark it executable with `chmod +x scripts/wiki-builder.sh`.

- [ ] **Step 5: Write the root Skill with bounded interaction**

```markdown
---
name: wiki-builder
description: Use when building, enriching, validating, or packing an authorized local Harness .hwiki on an M4 Mac.
---

# Harness Wiki Builder

1. Ask once for wiki name, stable ID, integer version, all input paths, concept namespace, publisher key, and confirmation that the user may use every input.
2. Run `prepare` immediately after that answer. Combine every unresolved source/edition/rights field into one sorted question.
3. Generate only the JSONL files emitted in `enrichment/tasks.jsonl`; every generated row must cite existing chunk IDs.
4. Run `enrich`, then `validate`; repair evidence or retrieval failures instead of lowering gates.
5. Run `pack` without another confirmation after validation passes.
6. Recommend complete extracted source text plus the retrieval semantic layer. Do not include original PDF/EPUB carriers.
7. Never upload source files, workspaces, packages, reports, or the publisher key.
```

- [ ] **Step 6: Generate and inspect the signed fixture**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder.tests.fixture --source app/src/test/resources/wiki/source.md --output build/wiki-fixture --reset`

Expected: `build/wiki-fixture/fixture.history-v1.hwiki`, `build-report.json`, and `build-report.md` exist; ZIP contains exactly four phase-one entries; `content.sqlite` passes `PRAGMA integrity_check`.

- [ ] **Step 7: Run the complete desktop suite**

Run: `scripts/wiki-builder.sh -m unittest discover -s tools/wiki_builder/tests -v`

Expected: all Wiki builder tests `OK`.

Run: `scripts/agent-builder.sh -m unittest discover -s tools/agent_builder/tests -v`

Expected: all existing Agent builder tests `OK`.

- [ ] **Step 8: Commit the usable desktop builder**

```bash
git add .agents/skills/wiki-builder scripts/wiki-builder.sh tools/wiki_builder app/src/test/resources/wiki
git commit -m "功能：交付桌面 hwiki 构建器"
```

### Task 7: Final Work-Package Verification

**Files:**
- Verify only; do not modify code unless a failing check identifies a defect.

**Interfaces:**
- Consumes: all Task 1-6 deliverables.
- Produces: a clean worktree, a reproducible signed fixture, and the handoff contract for the Android plan.

- [ ] **Step 1: Run all Python tests from a clean process**

Run: `scripts/wiki-builder.sh -m unittest discover -s tools/wiki_builder/tests -v`

Expected: all tests `OK`.

- [ ] **Step 2: Rebuild the fixture twice and compare hashes**

Run: `scripts/wiki-builder.sh -m tools.wiki_builder.tests.fixture --source app/src/test/resources/wiki/source.md --output build/wiki-fixture-a --reset`

Run: `scripts/wiki-builder.sh -m tools.wiki_builder.tests.fixture --source app/src/test/resources/wiki/source.md --output build/wiki-fixture-b --reset`

Run: `shasum -a 256 build/wiki-fixture-a/fixture.history-v1.hwiki build/wiki-fixture-b/fixture.history-v1.hwiki`

Expected: both SHA-256 values are identical.

- [ ] **Step 3: Verify the package contents and database**

Run: `unzip -l build/wiki-fixture-a/fixture.history-v1.hwiki`

Expected: exactly `manifest.json`, `content.sqlite`, `checksums.json`, and `signature.json`.

Run: `sqlite3 build/wiki-fixture-a/content.sqlite 'PRAGMA integrity_check; SELECT count(*) FROM chunks; SELECT count(*) FROM summaries;'`

Expected: first line `ok`; both counts are positive.

- [ ] **Step 4: Confirm repository state**

Run: `git status --short`

Expected: no tracked or untracked build artifacts; `build/` remains ignored.

No final “verification” commit is needed when the worktree is clean.
