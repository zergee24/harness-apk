import hashlib
import json
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.cli import main
from tools.wiki_builder.history import twenty_four_histories as adapter
from tools.wiki_builder.history.source_inventory import (
    SourceLock,
    TWENTY_FOUR_HISTORIES_SOURCE_ID,
    inventory_history_repository,
)
from tools.wiki_builder.history.twenty_four_histories import (
    TWENTY_FOUR_HISTORIES,
    HistoryAdapterError,
    prepare_twenty_four_histories,
)


class TwentyFourHistoriesAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        fixture = (
            Path(__file__).parent / "fixtures" / "twenty-four"
        )
        self.source = self.root / "china-history"
        shutil.copytree(fixture, self.source)
        for title in TWENTY_FOUR_HISTORIES[1:]:
            self._write(
                self.source / title / f"{title}.html",
                f'<html><body><h1>{title}</h1><a href="第一章-原文.html">第一章</a></body></html>',
            )
            self._write(
                self.source / title / "第一章-原文.html",
                f"<html><body><h1>{title}第一章</h1><p>{title}古文。</p></body></html>",
            )
        self._git("init", "-q")
        self._git("config", "user.email", "fixture@example.com")
        self._git("config", "user.name", "Fixture")
        self._git("remote", "add", "origin", "https://example.com/china-history.git")
        self._commit("fixture")
        self.lock = self._lock()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_prepare_builds_exact_24_document_hierarchy_and_preserves_source(self):
        first = prepare_twenty_four_histories(
            self.source,
            self.root / "first",
            self.lock,
        )
        second = prepare_twenty_four_histories(
            self.source,
            self.root / "second",
            self.lock,
        )

        self.assertEqual(
            (first / "content.sqlite").read_bytes(),
            (second / "content.sqlite").read_bytes(),
        )
        self.assertEqual(
            (first / "source-records.jsonl").read_bytes(),
            (second / "source-records.jsonl").read_bytes(),
        )
        with sqlite3.connect(first / "content.sqlite") as database:
            documents = database.execute(
                "SELECT title FROM documents ORDER BY ordinal"
            ).fetchall()
            shiji = database.execute(
                """
                SELECT chunks.original_text, chunks.locator_json
                FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                ORDER BY chunks.ordinal
                """
            ).fetchall()
            hierarchy = database.execute(
                """
                SELECT child.title, parent.title
                FROM sections AS child
                LEFT JOIN sections AS parent ON parent.section_id=child.parent_section_id
                JOIN documents ON documents.document_id=child.document_id
                WHERE documents.title='史记'
                ORDER BY child.ordinal
                """
            ).fetchall()
        self.assertEqual([(title,) for title in TWENTY_FOUR_HISTORIES], documents)
        self.assertEqual(
            [
                "黄帝者，少典之子也。",
                "太史公曰：“学者 & 史家”。",
                '“曲引”与"直引"皆存。',
            ],
            [row[0] for row in shiji],
        )
        self.assertNotIn("现代译文", "".join(row[0] for row in shiji))
        locator = json.loads(shiji[0][1])
        self.assertEqual("史记", locator["documentTitle"])
        self.assertEqual(["十二本纪"], locator["categoryPath"])
        self.assertEqual(1, locator["paragraphNumber"])
        self.assertEqual(
            "史记/十二本纪/第一章-五帝本纪-原文.html",
            locator["sourcePath"],
        )
        self.assertEqual(
            [("十二本纪", None), ("五帝本纪", "十二本纪")],
            hierarchy,
        )

    def test_duplicate_unlinked_missing_and_traversal_links_fail_without_output(self):
        cases = {
            "duplicate": (
                '<a href="十二本纪/第一章-五帝本纪-原文.html">一</a>'
                '<a href="十二本纪/第一章-五帝本纪-原文.html">二</a>',
                "重复",
            ),
            "missing": (
                '<a href="十二本纪/不存在-原文.html">缺失</a>'
                '<a href="十二本纪/第一章-五帝本纪-原文.html">一</a>',
                "缺失|不存在",
            ),
            "traversal": ('<a href="../越界-原文.html">越界</a>', "穿越|不安全|越界"),
            "leading-traversal": (
                '<a href="./../越界-原文.html">越界</a>',
                "穿越|不安全|越界",
            ),
            "internal-dot": (
                '<a href="十二本纪/./第一章-五帝本纪-原文.html">越界</a>',
                "穿越|不安全|越界",
            ),
            "empty-segment": (
                '<a href="十二本纪//第一章-五帝本纪-原文.html">越界</a>',
                "穿越|不安全|越界",
            ),
        }
        original = (self.source / "史记/史记.html").read_text(encoding="utf-8")
        for label, (links, pattern) in cases.items():
            with self.subTest(label=label):
                self._git("reset", "--hard", "HEAD")
                self._write(
                    self.source / "史记/史记.html",
                    f"<html><body><h1>史记</h1>{links}</body></html>",
                )
                self._commit(label)
                lock = self._lock()
                output = self.root / f"bad-{label}"
                with self.assertRaisesRegex(HistoryAdapterError, pattern):
                    prepare_twenty_four_histories(self.source, output, lock)
                self.assertFalse(output.exists())
                self._git("reset", "--hard", "HEAD~1")
        self._write(self.source / "史记/史记.html", original)

    def test_single_leading_dot_segment_is_a_safe_repository_relative_link(self):
        index = self.source / "史记/史记.html"
        payload = index.read_text(encoding="utf-8").replace(
            'href="十二本纪/',
            'href="./十二本纪/',
        )
        self._write(index, payload)
        self._commit("leading dot link")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "leading-dot",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            source_path = database.execute(
                """
                SELECT json_extract(chunks.locator_json, '$.sourcePath')
                FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                ORDER BY chunks.ordinal
                LIMIT 1
                """
            ).fetchone()[0]
        self.assertEqual(
            "史记/十二本纪/第一章-五帝本纪-原文.html",
            source_path,
        )

    def test_unlinked_or_unknown_document_source_is_rejected(self):
        self._write(
            self.source / "史记/十二本纪/第二章-原文.html",
            "<h1>第二章</h1><p>未链接正文。</p>",
        )
        self._write(
            self.source / "野史/第一章-原文.html",
            "<h1>野史</h1><p>未知文档。</p>",
        )
        self._commit("unlinked")
        lock = self._lock()

        with self.assertRaisesRegex(HistoryAdapterError, "未链接|24|白名单|消费"):
            prepare_twenty_four_histories(self.source, self.root / "unlinked", lock)

    def test_empty_or_discarded_visible_body_content_is_rejected(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        for label, body, pattern in (
            ("empty", "<h1>五帝本纪</h1><p>   </p>", "正文|段落"),
            (
                "discarded",
                "<h1>五帝本纪</h1><p>正文。</p><div>被丢弃的可见正文</div>",
                "丢弃|可见",
            ),
        ):
            with self.subTest(label=label):
                self._git("reset", "--hard", "HEAD")
                self._write(chapter, f"<html><body>{body}</body></html>")
                self._commit(label)
                with self.assertRaisesRegex(HistoryAdapterError, pattern):
                    prepare_twenty_four_histories(
                        self.source,
                        self.root / f"bad-body-{label}",
                        self._lock(),
                    )
                self._git("reset", "--hard", "HEAD~1")

    def test_previous_and_next_section_links_are_not_source_paragraphs(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = chapter.read_text(encoding="utf-8").replace(
            "</body>",
            "<div class='pn'><p class='pre'>上一节："
            "<a href='前卷-原文.html'>前卷</a></p>"
            "<p class='next'>下一节：<a href='后卷-原文.html'>后卷</a></p>"
            "</div></body>",
        )
        self._write(chapter, payload)
        self._commit("section navigation")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "section-navigation",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            source = "\n".join(
                row[0]
                for row in database.execute(
                    "SELECT original_text FROM chunks ORDER BY rowid"
                )
            )
        self.assertNotIn("上一节", source)
        self.assertNotIn("下一节", source)

    def test_unclosed_uppercase_br_is_a_void_line_break_inside_paragraph(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = chapter.read_text(encoding="utf-8").replace(
            "黄帝者，少典之子也。  </p>",
            "黄帝者，少典之子也。<BR><BR>  </p>",
        )
        self._write(chapter, payload)
        self._commit("uppercase br")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "uppercase-br",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            paragraphs = database.execute(
                """
                SELECT chunks.original_text FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                ORDER BY chunks.ordinal
                """
            ).fetchall()
        self.assertEqual(
            [
                "黄帝者，少典之子也。",
                "太史公曰：“学者 & 史家”。",
                '“曲引”与"直引"皆存。',
            ],
            [row[0] for row in paragraphs],
        )

    def test_html_table_rows_preserve_cell_and_line_break_structure(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = chapter.read_text(encoding="utf-8").replace(
            '<div class="pager">',
            "<table><tr><th>常数</th><th>月中节<br>月份</th></tr>"
            "<tr><td>冬至</td><td>十一月中</td></tr></table>"
            '<div class="pager">',
        )
        self._write(chapter, payload)
        self._commit("table rows")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "table-rows",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            paragraphs = [
                row[0]
                for row in database.execute(
                    """
                    SELECT chunks.original_text FROM chunks
                    JOIN sections USING(section_id)
                    JOIN documents USING(document_id)
                    WHERE documents.title='史记'
                    ORDER BY chunks.ordinal
                    """
                )
            ]
        self.assertIn("常数\t月中节\n月份", paragraphs)
        self.assertIn("冬至\t十一月中", paragraphs)

    def test_subheadings_are_preserved_as_ordered_citable_blocks(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = chapter.read_text(encoding="utf-8").replace(
            '<div class="pager">',
            "<h3>又仪天法</h3><table><tr><td>冬至</td><td>十一月中</td></tr></table>"
            '<div class="pager">',
        )
        self._write(chapter, payload)
        self._commit("subheading")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "subheading",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            rows = database.execute(
                """
                SELECT chunks.original_text, chunks.locator_json
                FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                ORDER BY chunks.ordinal
                """
            ).fetchall()
        self.assertEqual("又仪天法", rows[-2][0])
        self.assertEqual("subheading", json.loads(rows[-2][1])["blockType"])
        self.assertEqual(3, json.loads(rows[-2][1])["headingLevel"])
        self.assertEqual("冬至\t十一月中", rows[-1][0])
        self.assertEqual("table-row", json.loads(rows[-1][1])["blockType"])

    def test_chapter_title_excludes_nested_source_and_translation_controls(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = chapter.read_text(encoding="utf-8").replace(
            "<h1>五帝本纪</h1>",
            "<h1>五帝本纪<span> 原文</span>"
            "<span><a href='第一章-段译.html'>段译</a></span>"
            "<span><a href='第一章-译文.html'>译文</a></span></h1>",
        )
        self._write(chapter, payload)
        self._commit("chapter title controls")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "chapter-title-controls",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            title, locator = database.execute(
                """
                SELECT sections.title, chunks.locator_json
                FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                ORDER BY chunks.ordinal
                LIMIT 1
                """
            ).fetchone()
        self.assertEqual("五帝本纪", title)
        self.assertEqual("五帝本纪", json.loads(locator)["chapterTitle"])

    def test_orphan_nested_end_tag_does_not_close_the_source_paragraph(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = chapter.read_text(encoding="utf-8").replace(
            "黄帝者，少典之子也。",
            "黄帝者，少典</span>之子也。",
        )
        self._write(chapter, payload)
        self._commit("orphan nested end tag")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "orphan-end-tag",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            first = database.execute(
                """
                SELECT chunks.original_text FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                ORDER BY chunks.ordinal
                LIMIT 1
                """
            ).fetchone()[0]
        self.assertEqual("黄帝者，少典之子也。", first)

    def test_second_paragraph_start_implicitly_closes_the_first(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = chapter.read_text(encoding="utf-8").replace(
            "<p>  黄帝者，少典之子也。",
            "<p><p>  黄帝者，少典之子也。",
        )
        self._write(chapter, payload)
        self._commit("implicit paragraph close")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "implicit-paragraph-close",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            paragraphs = database.execute(
                """
                SELECT chunks.original_text FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                ORDER BY chunks.ordinal
                """
            ).fetchall()
        self.assertEqual(
            [
                "黄帝者，少典之子也。",
                "太史公曰：“学者 & 史家”。",
                '“曲引”与"直引"皆存。',
            ],
            [row[0] for row in paragraphs],
        )

    def test_known_empty_chapter_requires_exact_path_and_hash(self):
        chapter = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        payload = "<html><body><h1>五帝本纪</h1><p></p></body></html>"
        self._write(chapter, payload)
        self._commit("known empty chapter")
        relative = "史记/十二本纪/第一章-五帝本纪-原文.html"
        source_hash = hashlib.sha256(payload.encode("utf-8")).hexdigest()

        with patch.dict(
            adapter._KNOWN_EMPTY_CHAPTERS,
            {relative: (source_hash, "upstream-html-empty-v1")},
            clear=True,
        ):
            workspace = prepare_twenty_four_histories(
                self.source,
                self.root / "known-empty",
                self._lock(),
            )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            metadata = json.loads(
                database.execute(
                    "SELECT metadata_json FROM sections "
                    "WHERE json_extract(metadata_json, '$.sourceFileName')=?",
                    (chapter.name,),
                ).fetchone()[0]
            )
            chunks = database.execute(
                "SELECT COUNT(*) FROM chunks WHERE section_id=("
                "SELECT section_id FROM sections "
                "WHERE json_extract(metadata_json, '$.sourceFileName')=?)",
                (chapter.name,),
            ).fetchone()[0]
        self.assertEqual(0, chunks)
        self.assertEqual("known-empty-source", metadata["sourceState"])
        self.assertEqual("upstream-html-empty-v1", metadata["emptySourceReason"])

        changed = payload.replace("<p></p>", "<p> </p>")
        self._write(chapter, changed)
        self._commit("changed empty chapter")
        with patch.dict(
            adapter._KNOWN_EMPTY_CHAPTERS,
            {relative: (source_hash, "upstream-html-empty-v1")},
            clear=True,
        ):
            with self.assertRaisesRegex(HistoryAdapterError, "正文|段落"):
                prepare_twenty_four_histories(
                    self.source,
                    self.root / "changed-empty",
                    self._lock(),
                )

    def test_unlinked_leading_hyphen_variant_is_consumed_only_when_body_matches(self):
        canonical = self.source / "史记/十二本纪/第一章-五帝本纪-原文.html"
        duplicate = self.source / "史记/十二本纪/-第一章-五帝本纪-原文.html"
        duplicate_text = canonical.read_text(encoding="utf-8").replace(
            "<h1>五帝本纪</h1>",
            "<h1>-五帝本纪</h1>",
        )
        self._write(duplicate, duplicate_text)
        self._commit("verified duplicate")

        workspace = prepare_twenty_four_histories(
            self.source,
            self.root / "verified-duplicate",
            self._lock(),
        )

        with sqlite3.connect(workspace / "content.sqlite") as database:
            metadata = json.loads(
                database.execute(
                    "SELECT metadata_json FROM documents WHERE title='史记'"
                ).fetchone()[0]
            )
            shiji_chunks = database.execute(
                """
                SELECT COUNT(*) FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                WHERE documents.title='史记'
                """
            ).fetchone()[0]
        self.assertEqual(3, shiji_chunks)
        self.assertEqual(
            [
                {
                    "canonicalPath": "史记/十二本纪/第一章-五帝本纪-原文.html",
                    "duplicatePath": "史记/十二本纪/-第一章-五帝本纪-原文.html",
                }
            ],
            [
                {
                    "canonicalPath": row["canonicalPath"],
                    "duplicatePath": row["duplicatePath"],
                }
                for row in metadata["verifiedDuplicateSources"]
            ],
        )

        self._write(
            duplicate,
            duplicate_text.replace("黄帝者，少典之子也。", "重复版本正文发生变化。"),
        )
        self._commit("mismatched duplicate")
        with self.assertRaisesRegex(HistoryAdapterError, "重复|正文|不一致"):
            prepare_twenty_four_histories(
                self.source,
                self.root / "mismatched-duplicate",
                self._lock(),
            )

    def test_cli_requires_explicit_rights_and_canonical_package_identity(self):
        lock_path = self.root / "source-lock.json"
        rights_path = self.root / "rights.json"
        lock_path.write_bytes(canonical_json_bytes(self.lock.to_dict()))
        rights = {
            "type": "hwiki-rights-confirmation",
            "schemaVersion": 1,
            "purpose": "private-local-install",
            "distributionAllowed": False,
            "sources": [
                {
                    "sourceId": TWENTY_FOUR_HISTORIES_SOURCE_ID,
                    "gitRevision": self.lock.sources[0].git_revision,
                    "userConfirmed": False,
                    "basis": "user-provided local source for private installation",
                    "distributionAllowed": False,
                    "semanticProcessingApproved": False,
                    "evidence": [],
                }
            ],
        }
        rights_path.write_bytes(canonical_json_bytes(rights))
        output = self.root / "cli-workspace"
        arguments = [
            "history",
            "prepare-twenty-four",
            str(self.source),
            "--lock",
            str(lock_path),
            "--rights",
            str(rights_path),
            "--wiki-id",
            "cn.history.twenty-four-histories",
            "--title",
            "二十四史",
            "--version",
            "1",
            "--concept-namespace",
            "cn-history-v1",
            "--output",
            str(output),
        ]

        self.assertEqual(1, main(arguments))
        self.assertFalse(output.exists())

        rights["sources"][0]["userConfirmed"] = True
        rights_path.write_bytes(canonical_json_bytes(rights))
        self.assertEqual(0, main(arguments))
        self.assertTrue((output / "content.sqlite").is_file())

        bad_output = self.root / "wrong-identity"
        bad_arguments = list(arguments)
        bad_arguments[bad_arguments.index(str(output))] = str(bad_output)
        bad_arguments[bad_arguments.index("cn.history.twenty-four-histories")] = (
            "custom.history"
        )
        self.assertEqual(1, main(bad_arguments))
        self.assertFalse(bad_output.exists())

    def _lock(self) -> SourceLock:
        return SourceLock(
            (
                inventory_history_repository(
                    TWENTY_FOUR_HISTORIES_SOURCE_ID,
                    self.source,
                ),
            )
        )

    def _commit(self, message: str) -> None:
        self._git("add", ".")
        self._git("commit", "-q", "-m", message)

    def _git(self, *args: str) -> str:
        return subprocess.run(
            ["git", *args],
            cwd=self.source,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def _write(self, path: Path, value: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
