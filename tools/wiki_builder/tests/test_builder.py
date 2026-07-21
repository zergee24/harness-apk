import hashlib
import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path

from tools.wiki_builder.builder import prepare_workspace
from tools.wiki_builder.models import BuildError


class WikiBuilderTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_prepare_is_deterministic_and_preserves_original_text(self):
        source = self.root / "source.md"
        source.write_text(
            "# 卷一\n\n司馬光曰：臣聞天子之職莫大於禮。\n",
            encoding="utf-8",
        )

        first = prepare_workspace(
            [source], self.root / "first", "fixture.history", "史料", 1, "fixture-v1"
        )
        second = prepare_workspace(
            [source], self.root / "second", "fixture.history", "史料", 1, "fixture-v1"
        )

        first_bytes = (first / "content.sqlite").read_bytes()
        self.assertEqual(first_bytes, (second / "content.sqlite").read_bytes())
        self.assertEqual(
            hashlib.sha256(first_bytes).hexdigest(),
            hashlib.sha256((second / "content.sqlite").read_bytes()).hexdigest(),
        )
        with sqlite3.connect(first / "content.sqlite") as database:
            row = database.execute(
                "SELECT original_text, normalized_text, normalized_ngrams FROM chunks"
            ).fetchone()
            original_hit = database.execute(
                "SELECT chunk_id FROM chunks_original_fts WHERE chunks_original_fts MATCH '司馬'"
            ).fetchone()
            normalized_hit = database.execute(
                "SELECT chunk_id FROM chunks_normalized_fts WHERE chunks_normalized_fts MATCH '司马'"
            ).fetchone()
            self.assertEqual("ok", database.execute("PRAGMA integrity_check").fetchone()[0])

        self.assertEqual("司馬光曰：臣聞天子之職莫大於禮。", row[0])
        self.assertIn("司马光", row[1])
        self.assertIn("司马", row[2].split())
        self.assertIsNotNone(original_hit)
        self.assertEqual(original_hit, normalized_hit)

    def test_prepare_creates_generic_hierarchy_catalog_and_templates(self):
        source = self.root / "history.md"
        source.write_text(
            "# 卷一\n\n## 本纪\n\n太祖即位。\n\n## 列传\n\n群臣进言。",
            encoding="utf-8",
        )

        workspace = prepare_workspace(
            [source], self.root / "workspace", "fixture.history", "史料", 1, "fixture-v1"
        )

        self.assertEqual(
            {
                "aliases.jsonl",
                "annotations.jsonl",
                "concept-registry.jsonl",
                "links.jsonl",
                "mentions.jsonl",
                "summaries.jsonl",
                "terms.jsonl",
            },
            {path.name for path in (workspace / "enrichment").iterdir()},
        )
        manifest = json.loads((workspace / "workspace.json").read_bytes())
        catalog = json.loads((workspace / "source-catalog.json").read_bytes())
        self.assertEqual("fixture.history", manifest["wiki"]["id"])
        self.assertEqual("fixture-v1", manifest["conceptNamespace"])
        self.assertEqual(1, manifest["normalization"]["version"])
        self.assertEqual("history.md", catalog["sources"][0]["fileName"])
        self.assertNotIn(str(self.root), json.dumps(catalog, ensure_ascii=False))

        with sqlite3.connect(workspace / "content.sqlite") as database:
            sections = database.execute(
                """
                SELECT child.title, parent.title, child.path
                FROM sections AS child
                LEFT JOIN sections AS parent ON parent.section_id = child.parent_section_id
                ORDER BY child.ordinal
                """
            ).fetchall()
            chunk_text = [
                row[0]
                for row in database.execute("SELECT original_text FROM chunks ORDER BY ordinal")
            ]
        self.assertEqual(
            [("卷一", None, "卷一"), ("本纪", "卷一", "卷一 / 本纪"), ("列传", "卷一", "卷一 / 列传")],
            sections,
        )
        self.assertEqual(["太祖即位。", "群臣进言。"], chunk_text)

    def test_chunks_respect_hard_limit_without_duplicating_original_overlap(self):
        source = self.root / "long.md"
        source.write_text(
            "# 长文\n\n" + "甲" * 2100 + "\n\n" + "乙" * 1100,
            encoding="utf-8",
        )
        workspace = prepare_workspace(
            [source], self.root / "long", "fixture.long", "长文", 1, "fixture-v1"
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            rows = database.execute(
                "SELECT original_text, normalized_text FROM chunks ORDER BY ordinal"
            ).fetchall()

        self.assertGreater(len(rows), 1)
        self.assertLessEqual(max(len(row[0]) for row in rows), 2000)
        self.assertEqual(3200, sum(len(row[0].replace("\n", "")) for row in rows))
        self.assertGreater(len(rows[1][1]), len(rows[1][0]))

    def test_prepare_sorts_inputs_and_refuses_existing_output(self):
        alpha = self.root / "a.md"
        beta = self.root / "b.md"
        alpha.write_text("甲。", encoding="utf-8")
        beta.write_text("乙。", encoding="utf-8")
        first = prepare_workspace(
            [beta, alpha], self.root / "sorted", "fixture.sorted", "排序", 1, "fixture-v1"
        )
        catalog = json.loads((first / "source-catalog.json").read_bytes())
        self.assertEqual(["a.md", "b.md"], [row["fileName"] for row in catalog["sources"]])

        marker = self.root / "existing"
        marker.mkdir()
        (marker / "keep").write_text("keep", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            prepare_workspace(
                [alpha], marker, "fixture.sorted", "排序", 1, "fixture-v1"
            )
        self.assertEqual("keep", (marker / "keep").read_text(encoding="utf-8"))

    def test_prepare_rejects_empty_unsupported_and_symlink_sources(self):
        empty = self.root / "empty.md"
        empty.write_text("\n", encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "没有可提取文本"):
            prepare_workspace(
                [empty], self.root / "empty-output", "fixture.empty", "空", 1, "fixture-v1"
            )
        self.assertFalse((self.root / "empty-output").exists())

        unsupported = self.root / "source.html"
        unsupported.write_text("<p>正文</p>", encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "不支持"):
            prepare_workspace(
                [unsupported], self.root / "html-output", "fixture.html", "网页", 1, "fixture-v1"
            )

        if hasattr(os, "symlink"):
            link = self.root / "link.md"
            link.symlink_to(empty)
            with self.assertRaises(BuildError):
                prepare_workspace(
                    [link], self.root / "link-output", "fixture.link", "链接", 1, "fixture-v1"
                )


if __name__ == "__main__":
    unittest.main()
