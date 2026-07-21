import json
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.cli import main
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
