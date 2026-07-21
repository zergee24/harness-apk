import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.cli import main
from tools.wiki_builder.history.source_inventory import (
    InventoryError,
    SourceLock,
    inventory_history_sources,
)


class SourceInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.twenty = self.root / "twenty"
        self.zizhi = self.root / "zizhi"
        self._init_repo(self.twenty)
        self._init_repo(self.zizhi)
        self._write(
            self.twenty / "史记/第一章-五帝本纪-原文.html",
            "<h1>五帝本纪</h1><p>黄帝者，少典之子也。</p>",
        )
        self._write(
            self.twenty / "史记/史记.html",
            '<a href="第一章-五帝本纪-原文.html">五帝本纪</a>',
        )
        self._write(
            self.twenty / "史记-白话/第一章-五帝本纪-译文.html",
            "<p>现代译文</p>",
        )
        self._write(
            self.zizhi / "SUMMARY.md",
            "- [第一卷](chapters/001_资治通鉴第一卷(周纪).md)\n",
        )
        self._write(
            self.zizhi / "chapters/001_资治通鉴第一卷(周纪).md",
            "# 资治通鉴第一卷\n\n古文。\n\n现代翻译。\n",
        )
        self._write(self.zizhi / "LICENSE", "GPL-3.0 fixture\n")
        self._commit(self.twenty, "twenty fixture")
        self._commit(self.zizhi, "zizhi fixture")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_inventory_is_canonical_content_addressed_and_round_trips(self):
        first = inventory_history_sources(self.twenty, self.zizhi)
        second = inventory_history_sources(self.twenty, self.zizhi)

        self.assertEqual(first.to_dict(), second.to_dict())
        self.assertEqual(first, SourceLock.from_dict(first.to_dict()))
        by_id = {source.source_id: source for source in first.sources}
        self.assertEqual(1, by_id["twenty-four-histories"].relevant_file_count)
        self.assertEqual(1, by_id["zizhi-tongjian"].relevant_file_count)
        self.assertEqual(1, by_id["twenty-four-histories"].support_file_count)
        self.assertEqual(1, by_id["zizhi-tongjian"].support_file_count)
        self.assertEqual((), by_id["twenty-four-histories"].licenses)
        self.assertEqual("LICENSE", by_id["zizhi-tongjian"].licenses[0].path)
        self.assertRegex(by_id["zizhi-tongjian"].tree_hash, r"^[0-9a-f]{64}$")
        self.assertNotIn("timestamp", str(first.to_dict()).lower())

    def test_excluded_translation_changes_do_not_change_source_lock(self):
        before = inventory_history_sources(self.twenty, self.zizhi)
        self._write(
            self.twenty / "史记-白话/第一章-五帝本纪-译文.html",
            "<p>修改后的现代译文</p>",
        )

        after = inventory_history_sources(self.twenty, self.zizhi)

        self.assertEqual(before, after)

    def test_dirty_relevant_file_and_revision_drift_are_rejected(self):
        lock = inventory_history_sources(self.twenty, self.zizhi)
        source = self.twenty / "史记/第一章-五帝本纪-原文.html"
        self._write(source, "<h1>五帝本纪</h1><p>正文被修改。</p>")
        with self.assertRaisesRegex(InventoryError, "dirty|未提交"):
            inventory_history_sources(self.twenty, self.zizhi)

        self._git(self.twenty, "checkout", "--", str(source.relative_to(self.twenty)))
        self._write(self.twenty / "史记/第二章-原文.html", "<h1>第二章</h1><p>新增。</p>")
        self._commit(self.twenty, "new revision")
        with self.assertRaisesRegex(InventoryError, "revision|版本"):
            inventory_history_sources(self.twenty, self.zizhi, expected_lock=lock)

    def test_cli_validates_an_existing_lock_without_writing_another_lock(self):
        lock = inventory_history_sources(self.twenty, self.zizhi)
        lock_path = self.root / "source-lock.json"
        lock_path.write_bytes(canonical_json_bytes(lock.to_dict()))

        self.assertEqual(
            0,
            main(
                [
                    "history",
                    "validate-lock",
                    "--twenty-four",
                    str(self.twenty),
                    "--zizhi-tongjian",
                    str(self.zizhi),
                    "--lock",
                    str(lock_path),
                ]
            ),
        )
        self.assertEqual(
            {"source-lock.json"},
            {path.name for path in self.root.glob("*lock*.json")},
        )

    def test_missing_repo_bad_encoding_and_escaping_symlink_fail_closed(self):
        with self.assertRaisesRegex(InventoryError, "Git"):
            inventory_history_sources(self.root / "missing", self.zizhi)

        bad = self.zizhi / "chapters/001_资治通鉴第一卷(周纪).md"
        bad.write_bytes(b"\xff\xfe")
        self._commit(self.zizhi, "bad encoding")
        with self.assertRaisesRegex(InventoryError, "UTF-8|编码"):
            inventory_history_sources(self.twenty, self.zizhi)

        self._git(self.zizhi, "reset", "--hard", "HEAD~1")
        outside = self.root / "outside-原文.html"
        self._write(outside, "<p>outside</p>")
        link = self.twenty / "史记/越界-原文.html"
        link.symlink_to(outside)
        self._commit(self.twenty, "escaping symlink")
        with self.assertRaisesRegex(InventoryError, "符号链接|越界"):
            inventory_history_sources(self.twenty, self.zizhi)

    def _init_repo(self, path: Path) -> None:
        path.mkdir()
        self._git(path, "init", "-q")
        self._git(path, "config", "user.email", "fixture@example.com")
        self._git(path, "config", "user.name", "Fixture")
        self._git(path, "remote", "add", "origin", f"https://example.com/{path.name}.git")

    def _commit(self, path: Path, message: str) -> None:
        self._git(path, "add", ".")
        self._git(path, "commit", "-q", "-m", message)

    def _git(self, path: Path, *args: str) -> str:
        return subprocess.run(
            ["git", *args],
            cwd=path,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def _write(self, path: Path, value: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
