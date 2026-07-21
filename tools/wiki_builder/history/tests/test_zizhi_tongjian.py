import hashlib
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
    ZIZHI_TONGJIAN_SOURCE_ID,
    inventory_history_repository,
)
from tools.wiki_builder.history.zizhi_tongjian import (
    ZizhiTongjianAdapterError,
    prepare_zizhi_tongjian,
)


class ZizhiTongjianAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        fixture = Path(__file__).parent / "fixtures" / "zizhi"
        self.source = self.root / "zizhitongjian"
        shutil.copytree(fixture, self.source)
        summary = (self.source / "SUMMARY.md").read_text(encoding="utf-8")
        for volume in range(2, 295):
            if volume == 7:
                descriptor = "第七卷(秦纪)"
            elif volume == 17:
                descriptor = "第十七卷(汉纪)"
            elif volume == 18:
                descriptor = "第十八卷(汉纪)"
            elif volume == 88:
                descriptor = "第八十八卷(晋纪)"
            elif volume == 104:
                descriptor = "第一百零四卷(晋纪)"
            elif volume == 220:
                descriptor = "第二百二十卷(唐纪)"
            elif volume == 233:
                descriptor = "第二百三十三卷(唐纪)"
            else:
                descriptor = f"第{volume}卷(测试纪)"
            filename = f"{volume:03d}_资治通鉴{descriptor}.md"
            source_title = (
                "资治通鉴第七卷(秦记)"
                if volume == 7
                else f"资治通鉴{descriptor}"
            )
            if volume == 17:
                paired_content = (
                    "测试纪 第17年（甲子、17）\n\n"
                    "　　[1]第17卷古文。\n\n"
                    "测试纪 第17年（甲子，公元17年）\n\n"
                    "　　[1]第17卷译文。\n"
                )
            elif volume == 18:
                paired_content = (
                    "　　1冬，第18卷古文。\n\n"
                    "测试纪 第18年（甲子、18）\n\n"
                    "测试纪 第18年（甲子，公元18年）\n\n"
                    "　　[1]冬季，第18卷译文。\n"
                )
            elif volume == 88:
                paired_content = (
                    "测试纪 第88年（甲子、88）\n\n"
                    "测试纪 第88年（甲子，公元88年）\n\n"
                    "　　该卷古文。\n\n"
                    "该卷译文。\n"
                )
            elif volume == 104:
                paired_content = (
                    "测试纪 第一百零四年（甲子、104）\n\n"
                    "测试纪 第一百零四年（甲子，公元104年）\n\n"
                    "　　该卷古文。\n\n"
                    "　该卷译文。\n"
                )
            elif volume == 220:
                paired_content = (
                    "测试纪 第二百二十年（甲子、２２０）\n\n"
                    "　　测试纪 第二百二十年（甲子，公元２２０年）\n\n"
                    "　　[1]该卷古文。\n\n"
                    "　　[1]该卷译文。\n"
                )
            elif volume == 233:
                paired_content = (
                    "四年（戊辰、778\n\n"
                    "四年（戊辰，公元788年）\n\n"
                    "　　[1]该卷古文。\n\n"
                    "　　[1]该卷译文。\n"
                )
            else:
                translated_heading_indent = "　" if volume == 65 else ""
                paired_content = (
                    f"测试纪 第{volume}年（甲子、{volume}）\n\n"
                    f"{translated_heading_indent}测试纪 第{volume}年"
                    f"（甲子，公元{volume}年）\n\n"
                    f"　　[1]第{volume}卷古文。\n\n"
                    f"　　[1]第{volume}卷译文。\n"
                )
            summary += f"[{descriptor}](chapters/{filename})\n"
            self._write(
                self.source / "chapters" / filename,
                f"{source_title}\n\n{paired_content}",
            )
        self._write(self.source / "SUMMARY.md", summary)
        self._write(self.source / "LICENSE", "GPL-3.0 fixture\n")
        self._git("init", "-q")
        self._git("config", "user.email", "fixture@example.com")
        self._git("config", "user.name", "Fixture")
        self._git("remote", "add", "origin", "https://example.com/zizhi.git")
        self._commit("fixture")
        self.baseline_revision = self._git("rev-parse", "HEAD")
        self.lock = self._lock()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_prepare_builds_294_volume_source_only_workspace_with_pair_audit(self):
        first = prepare_zizhi_tongjian(
            self.source,
            self.root / "first",
            self.lock,
        )
        second = prepare_zizhi_tongjian(
            self.source,
            self.root / "second",
            self.lock,
        )

        self.assertEqual(
            (first / "content.sqlite").read_bytes(),
            (second / "content.sqlite").read_bytes(),
        )
        self.assertEqual(
            (first / "source-map.jsonl").read_bytes(),
            (second / "source-map.jsonl").read_bytes(),
        )
        with sqlite3.connect(first / "content.sqlite") as database:
            documents = database.execute(
                "SELECT title FROM documents ORDER BY ordinal"
            ).fetchall()
            volume_count = database.execute(
                "SELECT COUNT(*) FROM sections WHERE parent_section_id IS NULL"
            ).fetchone()[0]
            first_sections = database.execute(
                """
                SELECT child.title, parent.title
                FROM sections AS child
                LEFT JOIN sections AS parent
                  ON parent.section_id=child.parent_section_id
                ORDER BY child.ordinal
                LIMIT 2
                """
            ).fetchall()
            first_chunks = database.execute(
                """
                SELECT chunks.original_text, chunks.locator_json
                FROM chunks
                JOIN sections USING(section_id)
                WHERE sections.path LIKE '资治通鉴/001/%'
                ORDER BY chunks.ordinal
                """
            ).fetchall()
            all_text = "\n".join(
                row[0] for row in database.execute("SELECT original_text FROM chunks")
            )
        self.assertEqual([("资治通鉴",)], documents)
        self.assertEqual(294, volume_count)
        self.assertEqual(
            [
                ("资治通鉴第一卷(周纪)", None),
                ("周纪一 威烈王二十三年（戊寅、前403）", "资治通鉴第一卷(周纪)"),
            ],
            first_sections,
        )
        self.assertEqual(
            [
                "[1]初命晋大夫魏斯、赵籍、韩虔为诸侯。",
                "臣光曰：“才者，德之资也；德者，才之帅也。”",
            ],
            [row[0] for row in first_chunks],
        )
        self.assertNotIn("周威烈王初次分封", all_text)
        self.assertNotIn("臣司马光说", all_text)
        locator = json.loads(first_chunks[0][1])
        self.assertEqual("资治通鉴", locator["documentTitle"])
        self.assertEqual(1, locator["volumeNumber"])
        self.assertEqual(1, locator["paragraphNumber"])
        self.assertEqual(
            "chapters/001_资治通鉴第一卷(周纪).md",
            locator["sourcePath"],
        )
        self.assertEqual(
            hashlib.sha256(
                "[1]周威烈王初次分封三家为诸侯。".encode("utf-8")
            ).hexdigest(),
            locator["translationHash"],
        )
        source_map = [
            json.loads(line)
            for line in (first / "source-map.jsonl").read_text(
                encoding="utf-8"
            ).splitlines()
        ]
        excluded = [
            row
            for row in source_map
            if row.get("kind") == "paragraph" and "excludedTextSha256" in row
        ]
        heading_exclusions = [
            row for row in source_map if row.get("kind") == "time-heading"
        ]
        self.assertEqual(295, len(excluded))
        self.assertEqual(294, len(heading_exclusions))
        self.assertEqual(locator["translationHash"], excluded[0]["excludedTextSha256"])
        self.assertEqual("modern-translation-not-packaged-v1", excluded[0]["reason"])

    def test_summary_order_title_and_full_consumption_are_fail_closed(self):
        summary_path = self.source / "SUMMARY.md"
        chapter_path = (
            self.source / "chapters" / "001_资治通鉴第一卷(周纪).md"
        )
        baseline_summary = summary_path.read_text(encoding="utf-8")
        baseline_chapter = chapter_path.read_text(encoding="utf-8")
        last_line = "[第294卷(测试纪)](chapters/294_资治通鉴第294卷(测试纪).md)\n"
        cases = {
            "missing": (
                lambda: self._write(
                    summary_path,
                    baseline_summary.replace(last_line, ""),
                ),
                "294|连续|缺少|消费",
            ),
            "duplicate": (
                lambda: self._write(
                    summary_path,
                    baseline_summary.replace(last_line, baseline_summary.splitlines()[3] + "\n"),
                ),
                "重复|连续|294",
            ),
            "traversal": (
                lambda: self._write(
                    summary_path,
                    baseline_summary.replace(
                        "chapters/001_资治通鉴第一卷(周纪).md",
                        "chapters/../001_资治通鉴第一卷(周纪).md",
                    ),
                ),
                "穿越|不安全|chapters",
            ),
            "title-mismatch": (
                lambda: self._write(
                    chapter_path,
                    baseline_chapter.replace(
                        "资治通鉴第一卷(周纪)",
                        "资治通鉴第二卷(周纪)",
                        1,
                    ),
                ),
                "标题|卷名|一致",
            ),
        }
        for label, (mutate, pattern) in cases.items():
            with self.subTest(label=label):
                self._git("reset", "--hard", self.baseline_revision)
                mutate()
                self._commit(label)
                output = self.root / f"bad-summary-{label}"
                with self.assertRaisesRegex(ZizhiTongjianAdapterError, pattern):
                    prepare_zizhi_tongjian(self.source, output, self._lock())
                self.assertFalse(output.exists())
        self._git("reset", "--hard", self.baseline_revision)

    def test_pair_markers_years_and_unclassified_lines_are_fail_closed(self):
        chapter = self.source / "chapters" / "001_资治通鉴第一卷(周纪).md"
        baseline = chapter.read_text(encoding="utf-8")
        cases = {
            "missing-translation": (
                lambda: self._write(
                    chapter,
                    baseline.replace(
                        "\n　　[2]臣司马光说：“才能辅助德行，德行统领才能。”\n",
                        "\n",
                    ),
                ),
                "奇数|配对|译文",
            ),
            "marker-mismatch": (
                lambda: self._write(
                    chapter,
                    baseline.replace("　　[1]周威烈王初次", "　　[2]周威烈王初次"),
                ),
                "标记|配对",
            ),
            "year-mismatch": (
                lambda: self._write(
                    chapter,
                    baseline.replace("公元前403年", "公元前404年"),
                ),
                "年份|时间",
            ),
            "body-before-time": (
                lambda: self._write(
                    chapter,
                    baseline.replace(
                        "\n\n\n周纪一",
                        "\n\n　　孤立正文。\n\n周纪一",
                    ),
                ),
                "时间|正文|归类",
            ),
        }
        for label, (mutate, pattern) in cases.items():
            with self.subTest(label=label):
                self._git("reset", "--hard", self.baseline_revision)
                mutate()
                self._commit(label)
                output = self.root / f"bad-pair-{label}"
                with self.assertRaisesRegex(ZizhiTongjianAdapterError, pattern):
                    prepare_zizhi_tongjian(self.source, output, self._lock())
                self.assertFalse(output.exists())
        self._git("reset", "--hard", self.baseline_revision)
        with self.assertRaisesRegex(ZizhiTongjianAdapterError, "version 1|译文"):
            prepare_zizhi_tongjian(
                self.source,
                self.root / "translation",
                self.lock,
                include_translation=True,
            )

    def test_cli_requires_rights_before_preparing(self):
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
                    "sourceId": ZIZHI_TONGJIAN_SOURCE_ID,
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
            "prepare-zizhi-tongjian",
            str(self.source),
            "--lock",
            str(lock_path),
            "--rights",
            str(rights_path),
            "--wiki-id",
            "cn.history.zizhi-tongjian",
            "--title",
            "资治通鉴",
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

    def _lock(self) -> SourceLock:
        return SourceLock(
            (
                inventory_history_repository(
                    ZIZHI_TONGJIAN_SOURCE_ID,
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
