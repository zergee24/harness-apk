import hashlib
import sqlite3
import tempfile
import unittest
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.builder import prepare_workspace
from tools.wiki_builder.enrichment import import_enrichment
from tools.wiki_builder.models import BuildError


class EnrichmentImportTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        source = self.root / "source.md"
        source.write_text(
            "# 卷一\n\n司馬光曰：臣聞天子之職莫大於禮。",
            encoding="utf-8",
        )
        self.workspace = prepare_workspace(
            [source],
            self.root / "workspace",
            "fixture.history",
            "史料",
            1,
            "fixture-v1",
        )
        with sqlite3.connect(self.workspace / "content.sqlite") as database:
            self.document_id = database.execute(
                "SELECT document_id FROM documents"
            ).fetchone()[0]
            self.section_id = database.execute(
                "SELECT section_id FROM sections"
            ).fetchone()[0]
            self.chunk_id, self.original_text = database.execute(
                "SELECT chunk_id, original_text FROM chunks"
            ).fetchone()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_imports_every_asset_with_evidence_and_rebuilds_semantic_fts(self):
        concept_key = "fixture-v1:person:sima-guang"
        self.write_rows(
            "concept-registry.jsonl",
            [{"conceptKey": concept_key, "kind": "person", "canonicalText": "司马光"}],
        )
        self.write_rows(
            "summaries.jsonl",
            [
                {
                    "id": "summary-volume-1",
                    "ownerType": "section",
                    "ownerId": self.section_id,
                    "level": "volume",
                    "text": "本卷讨论礼制与天子职责。",
                    "evidence": [self.chunk_id],
                }
            ],
        )
        self.write_rows(
            "terms.jsonl",
            [
                {
                    "id": "term-sima-guang",
                    "conceptKey": concept_key,
                    "canonicalText": "司马光",
                    "kind": "person",
                    "confidence": 1.0,
                    "metadata": {"source": "mechanical"},
                    "evidence": [self.chunk_id],
                }
            ],
        )
        self.write_rows(
            "aliases.jsonl",
            [
                {
                    "id": "alias-junzi",
                    "termId": "term-sima-guang",
                    "aliasText": "君实",
                    "confidence": 0.9,
                    "evidence": [self.chunk_id],
                }
            ],
        )
        self.write_rows(
            "mentions.jsonl",
            [
                {
                    "id": "mention-sima-guang-1",
                    "termId": "term-sima-guang",
                    "chunkId": self.chunk_id,
                    "startOffset": 0,
                    "endOffset": 3,
                    "text": "司馬光",
                    "confidence": 1.0,
                }
            ],
        )
        self.write_rows(
            "annotations.jsonl",
            [
                {
                    "id": "annotation-duty",
                    "ownerType": "chunk",
                    "ownerId": self.chunk_id,
                    "kind": "topic",
                    "value": {"name": "礼制"},
                    "confidence": 0.8,
                    "evidence": [{"chunkId": self.chunk_id, "role": "primary"}],
                }
            ],
        )
        self.write_rows(
            "links.jsonl",
            [
                {
                    "id": "link-sima-guang",
                    "sourceType": "term",
                    "sourceId": "term-sima-guang",
                    "targetNamespace": "external-v1",
                    "targetType": "concept",
                    "targetId": "external-v1:person:sima-guang",
                    "kind": "same_as",
                    "confidence": 0.7,
                    "evidence": [self.chunk_id],
                }
            ],
        )

        stats = import_enrichment(self.workspace)

        self.assertEqual(1, stats.summaries)
        self.assertEqual(1, stats.terms)
        self.assertEqual(1, stats.aliases)
        self.assertEqual(1, stats.mentions)
        self.assertEqual(1, stats.annotations)
        self.assertEqual(1, stats.links)
        with sqlite3.connect(self.workspace / "content.sqlite") as database:
            self.assertEqual(6, database.execute("SELECT COUNT(*) FROM evidence_refs").fetchone()[0])
            self.assertEqual(
                "{\"name\":\"礼制\"}",
                database.execute("SELECT value_json FROM annotations").fetchone()[0],
            )
            self.assertIsNotNone(
                database.execute(
                    "SELECT summary_id FROM summaries_fts WHERE summaries_fts MATCH '礼制'"
                ).fetchone()
            )
            self.assertIsNotNone(
                database.execute(
                    "SELECT owner_id FROM terms_aliases_fts WHERE terms_aliases_fts MATCH '君实'"
                ).fetchone()
            )
            registry_hash = database.execute(
                "SELECT value FROM build_metadata WHERE key='conceptRegistryHash'"
            ).fetchone()[0]
        self.assertEqual(
            hashlib.sha256(
                (self.workspace / "enrichment/concept-registry.jsonl").read_bytes()
            ).hexdigest(),
            registry_hash,
        )

    def test_unknown_evidence_rolls_back_every_asset(self):
        self.write_rows(
            "summaries.jsonl",
            [
                {
                    "id": "a-valid",
                    "ownerType": "section",
                    "ownerId": self.section_id,
                    "level": "volume",
                    "text": "有效摘要",
                    "evidence": [self.chunk_id],
                },
                {
                    "id": "z-bad",
                    "ownerType": "section",
                    "ownerId": self.section_id,
                    "level": "volume",
                    "text": "无效摘要",
                    "evidence": ["missing-chunk"],
                },
            ],
        )

        with self.assertRaisesRegex(BuildError, "missing-chunk"):
            import_enrichment(self.workspace)

        self.assertEqual(0, self.count_rows("summaries"))
        self.assertEqual(0, self.count_rows("evidence_refs"))

    def test_invalid_mention_offset_and_text_roll_back(self):
        self._write_one_term()
        self.write_rows(
            "mentions.jsonl",
            [
                {
                    "id": "bad-mention",
                    "termId": "term-sima-guang",
                    "chunkId": self.chunk_id,
                    "startOffset": 0,
                    "endOffset": 3,
                    "text": "不是原文",
                    "confidence": 1.0,
                }
            ],
        )

        with self.assertRaisesRegex(BuildError, "offset|原文"):
            import_enrichment(self.workspace)

        self.assertEqual(0, self.count_rows("terms"))
        self.assertEqual(0, self.count_rows("mentions"))

    def test_duplicate_ids_or_orphan_aliases_are_rejected(self):
        duplicate = {
            "id": "duplicate",
            "ownerType": "section",
            "ownerId": self.section_id,
            "level": "volume",
            "text": "摘要",
            "evidence": [self.chunk_id],
        }
        self.write_rows("summaries.jsonl", [duplicate, duplicate])
        with self.assertRaisesRegex(BuildError, "重复"):
            import_enrichment(self.workspace)

        self.write_rows("summaries.jsonl", [])
        self.write_rows(
            "aliases.jsonl",
            [
                {
                    "id": "orphan-alias",
                    "termId": "missing-term",
                    "aliasText": "别名",
                    "confidence": 0.5,
                    "evidence": [self.chunk_id],
                }
            ],
        )
        with self.assertRaisesRegex(BuildError, "missing-term"):
            import_enrichment(self.workspace)

    def test_term_requires_registry_entry_in_workspace_namespace(self):
        self.write_rows(
            "terms.jsonl",
            [
                {
                    "id": "term-sima-guang",
                    "conceptKey": "wrong-v1:person:sima-guang",
                    "canonicalText": "司马光",
                    "kind": "person",
                    "confidence": 1.0,
                    "evidence": [self.chunk_id],
                }
            ],
        )
        with self.assertRaisesRegex(BuildError, "concept|命名空间"):
            import_enrichment(self.workspace)

        self.write_rows(
            "terms.jsonl",
            [
                {
                    "id": "term-sima-guang",
                    "conceptKey": "fixture-v1:person:sima-guang",
                    "canonicalText": "司马光",
                    "kind": "person",
                    "confidence": 1.0,
                    "evidence": [self.chunk_id],
                }
            ],
        )
        with self.assertRaisesRegex(BuildError, "registry"):
            import_enrichment(self.workspace)

    def test_unknown_fields_boolean_confidence_and_invalid_json_fail_closed(self):
        row = {
            "id": "summary",
            "ownerType": "section",
            "ownerId": self.section_id,
            "level": "volume",
            "text": "摘要",
            "evidence": [self.chunk_id],
            "unexpected": True,
        }
        self.write_rows("summaries.jsonl", [row])
        with self.assertRaisesRegex(BuildError, "未知字段"):
            import_enrichment(self.workspace)

        self.write_rows("summaries.jsonl", [])
        self.write_rows(
            "annotations.jsonl",
            [
                {
                    "id": "boolean-confidence",
                    "ownerType": "chunk",
                    "ownerId": self.chunk_id,
                    "kind": "topic",
                    "value": "礼制",
                    "confidence": True,
                    "evidence": [self.chunk_id],
                }
            ],
        )
        with self.assertRaisesRegex(BuildError, "0 到 1"):
            import_enrichment(self.workspace)

        (self.workspace / "enrichment/summaries.jsonl").write_bytes(b"{bad json}\n")
        with self.assertRaisesRegex(BuildError, "第 1 行"):
            import_enrichment(self.workspace)

    def _write_one_term(self):
        concept_key = "fixture-v1:person:sima-guang"
        self.write_rows(
            "concept-registry.jsonl",
            [{"conceptKey": concept_key, "kind": "person", "canonicalText": "司马光"}],
        )
        self.write_rows(
            "terms.jsonl",
            [
                {
                    "id": "term-sima-guang",
                    "conceptKey": concept_key,
                    "canonicalText": "司马光",
                    "kind": "person",
                    "confidence": 1.0,
                    "evidence": [self.chunk_id],
                }
            ],
        )

    def write_rows(self, file_name: str, rows: list[dict[str, object]]) -> None:
        payload = b"".join(canonical_json_bytes(row) + b"\n" for row in rows)
        (self.workspace / "enrichment" / file_name).write_bytes(payload)

    def count_rows(self, table: str) -> int:
        with sqlite3.connect(self.workspace / "content.sqlite") as database:
            return database.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]


if __name__ == "__main__":
    unittest.main()
