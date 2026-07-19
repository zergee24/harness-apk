import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock

from tools.agent_builder import corpus_pipeline
from tools.agent_builder.corpus_pipeline import build_corpus_index, write_corpus_index
from tools.agent_builder.builder import prepare_workspace_v2
from tools.agent_builder.extractors import iter_v2_plain_text_sections
from tools.agent_builder.models import ExtractedDocument, ExtractedSection
from tools.agent_builder.schema_v2 import Authorship, SourceGenre, SourceRecord


class CorpusPipelineTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_exact_duplicates_in_same_period_share_physical_chunk_and_aliases(self):
        result = build_corpus_index(
            [
                self._document("a", "调查以后再下结论。"),
                self._document("b", "调查以后再下结论。"),
            ],
            [self._source("a", period="1926"), self._source("b", period="1926")],
        )

        self.assertEqual(1, len(result.chunks))
        self.assertEqual(("a", "b"), result.chunks[0].source_aliases)
        self.assertEqual(1, result.stats.exact_duplicate_count)
        self.assertEqual(1, len(result.duplicates))

    def test_merged_source_aliases_are_sorted_and_unique(self):
        result = build_corpus_index(
            [
                self._document("a", "调查以后再下结论。"),
                self._document("a-repeat", "调查以后再下结论。"),
            ],
            [
                self._source("a", period="1926"),
                replace(self._source("a-repeat", period="1926"), source_id="a"),
            ],
        )

        self.assertEqual(("a",), result.chunks[0].source_aliases)

    def test_near_duplicates_merge_and_remain_auditable(self):
        first = "调查研究必须从事实出发，先摸清情况再作决定。"
        second = "调查研究应从事实出发，先摸清情况再作决定。"
        result = build_corpus_index(
            [self._document("a", first), self._document("b", second)],
            [self._source("a", period="1926"), self._source("b", period="1926")],
        )

        self.assertEqual(1, len(result.chunks))
        self.assertEqual(1, result.stats.near_duplicate_count)
        self.assertEqual("near", result.duplicates[0].match_type)

    def test_different_or_unknown_periods_do_not_merge_across_sources(self):
        text = "组织形式应当调整。"
        different_periods = build_corpus_index(
            [self._document("early", text), self._document("late", text)],
            [self._source("early", period="1926"), self._source("late", period="1945")],
        )
        unknown_periods = build_corpus_index(
            [self._document("unknown-a", text), self._document("unknown-b", text)],
            [self._source("unknown-a", period="unknown"), self._source("unknown-b", period="unknown")],
        )

        self.assertEqual(2, len(different_periods.chunks))
        self.assertEqual(2, len(unknown_periods.chunks))

    def test_different_conflict_keys_do_not_merge(self):
        text = "组织形式应当调整。"
        result = build_corpus_index(
            [
                self._document("a", text, conflict_key="position-a"),
                self._document("b", text, conflict_key="position-b"),
            ],
            [self._source("a", period="1926"), self._source("b", period="1926")],
        )

        self.assertEqual(2, len(result.chunks))

    def test_different_authorship_or_genre_do_not_merge(self):
        text = "组织形式应当调整。"
        direct = self._source("a", period="1926")
        secondary_authorship = replace(
            self._source("b", period="1926"),
            authorship=Authorship.SECONDARY,
        )
        letter_genre = replace(
            self._source("b", period="1926"),
            genre=SourceGenre.LETTER,
        )

        by_authorship = build_corpus_index(
            [self._document("a", text), self._document("b", text)],
            [direct, secondary_authorship],
        )
        by_genre = build_corpus_index(
            [self._document("a", text), self._document("b", text)],
            [direct, letter_genre],
        )

        self.assertEqual(2, len(by_authorship.chunks))
        self.assertEqual(2, len(by_genre.chunks))

    def test_streaming_index_does_not_merge_different_authorship(self):
        root = self.root / "streaming-authorship"
        sources = [
            self._source("a", period="1926"),
            replace(self._source("b", period="1926"), authorship=Authorship.SECONDARY),
        ]

        corpus_pipeline.build_corpus_index_streaming(
            root,
            sources,
            lambda source: [ExtractedSection("正文", "组织形式应当调整。")],
        )

        chunks = (root / "corpora" / "index" / "chunks.jsonl").read_text("utf-8").splitlines()
        self.assertEqual(2, len(chunks))

    def test_streaming_simhash_bands_recall_a_distance_sixteen_candidate(self):
        base = build_corpus_index(
            [self._document("a", "调查研究必须从事实出发，先摸清情况再作决定。")],
            [self._source("a", period="1926")],
        ).chunks[0]
        changed_bits = 0
        offset = 0
        for band_index, width in enumerate((4,) * 13 + (3,) * 4):
            if band_index < 16:
                changed_bits |= 1 << offset
            offset += width
        primary = replace(base, simhash=0)
        candidate = replace(
            base,
            id="chunk-distance-sixteen",
            source_id="b",
            source_hash="hash-b",
            source_aliases=("b",),
            simhash=changed_bits,
        )
        database = sqlite3.connect(self.root / "bands.sqlite3")
        database.row_factory = sqlite3.Row
        try:
            corpus_pipeline._create_streaming_schema(database)
            corpus_pipeline._insert_physical_chunk(
                database,
                primary,
                corpus_pipeline._stream_sort_key(primary),
            )
            found = corpus_pipeline._find_near_streaming_match(database, candidate)
        finally:
            database.close()

        self.assertEqual(16, (primary.simhash ^ candidate.simhash).bit_count())
        self.assertTrue(set(corpus_pipeline._simhash_bands(primary.simhash)) & set(corpus_pipeline._simhash_bands(candidate.simhash)))
        self.assertEqual(primary.id, found.id)

    def test_chunks_include_bounded_context_and_hierarchy(self):
        result = build_corpus_index(
            [self._document("source", "正文。", location="第一章 / 第一节")],
            [self._source("source", title="调查研究", period="1926")],
        )
        chunk = result.chunks[0]

        self.assertEqual(3, len(chunk.parent_ids))
        self.assertIn("第一章", chunk.context)
        self.assertIn("调查研究", chunk.context)
        self.assertLessEqual(len(chunk.context), 320)
        self.assertTrue(any(node.id == chunk.parent_ids[-1] for node in result.nodes))

    def test_output_is_deterministic_and_duplicates_jsonl_is_auditable(self):
        documents = [
            self._document("b", "调查以后再下结论。"),
            self._document("a", "调查以后再下结论。"),
        ]
        sources = [self._source("b", period="1926"), self._source("a", period="1926")]
        first = build_corpus_index(documents, sources)
        second = build_corpus_index(list(reversed(documents)), list(reversed(sources)))
        first_root = self.root / "first"
        second_root = self.root / "second"

        write_corpus_index(first_root, first)
        write_corpus_index(second_root, second)

        for name in ("nodes.jsonl", "chunks.jsonl", "duplicates.jsonl"):
            self.assertEqual(
                (first_root / "corpora" / "index" / name).read_bytes(),
                (second_root / "corpora" / "index" / name).read_bytes(),
            )
        duplicate = json.loads((first_root / "corpora" / "index" / "duplicates.jsonl").read_text("utf-8"))
        self.assertEqual("exact", duplicate["matchType"])
        self.assertEqual(("a", "b"), first.chunks[0].source_aliases)

    def test_short_chinese_empty_and_non_chinese_sections_are_safe(self):
        result = build_corpus_index(
            [
                self._document("zh", "好"),
                self._document("empty", "   "),
                self._document("en", "A short English sentence."),
            ],
            [
                self._source("zh", period="1926"),
                self._source("empty", period="1926"),
                self._source("en", period="1926"),
            ],
        )

        self.assertEqual(2, len(result.chunks))
        self.assertEqual(3, result.stats.source_count)

    def test_v2_plain_text_section_reader_is_windowed_and_boundary_stable(self):
        source = self.root / "stream.md"
        source.write_text("# 第一章\n\n甲乙丙丁。\n\n## 第二节\n\n多字节 UTF-8 文本。\n", encoding="utf-8")

        with (
            mock.patch.object(Path, "read_bytes", side_effect=AssertionError("whole-file read_bytes")),
            mock.patch.object(Path, "read_text", side_effect=AssertionError("whole-file read_text")),
        ):
            small = list(iter_v2_plain_text_sections(source, read_chars=5))
            large = list(iter_v2_plain_text_sections(source, read_chars=4096))

        self.assertEqual(small, large)
        self.assertEqual(["第一章", "第一章 / 第二节"], [section.location for section in small])
        self.assertEqual(["# 第一章\n\n甲乙丙丁。", "## 第二节\n\n多字节 UTF-8 文本。"], [section.text for section in small])

    def test_v2_plain_text_reader_supports_gb18030_without_whole_file_read(self):
        source = self.root / "gb18030.md"
        source.write_bytes("# 第一章\n\n资料必须经过核验。".encode("gb18030"))

        with (
            mock.patch.object(Path, "read_bytes", side_effect=AssertionError("whole-file read_bytes")),
            mock.patch.object(Path, "read_text", side_effect=AssertionError("whole-file read_text")),
        ):
            sections = list(iter_v2_plain_text_sections(source, read_chars=3))

        self.assertEqual(["第一章"], [section.location for section in sections])
        self.assertEqual("# 第一章\n\n资料必须经过核验。", sections[0].text)

    def test_v2_plain_text_reader_keeps_long_stream_windowed(self):
        source = self.root / "long.md"
        source.write_text("# 长文\n\n" + ("多字节资料内容。\n" * 20_000), encoding="utf-8")

        with (
            mock.patch.object(Path, "read_bytes", side_effect=AssertionError("whole-file read_bytes")),
            mock.patch.object(Path, "read_text", side_effect=AssertionError("whole-file read_text")),
        ):
            sections = list(iter_v2_plain_text_sections(source, read_chars=17))

        self.assertGreater(len(sections), 1)
        self.assertTrue(all(section.location == "长文" for section in sections))
        self.assertTrue(all(len(section.text) <= 16_384 for section in sections))

    def test_v2_plain_text_reader_caps_a_multi_megabyte_single_line(self):
        source = self.root / "single-line.txt"
        source.write_text("资料" * 1_100_000, encoding="utf-8")

        with (
            mock.patch.object(Path, "read_bytes", side_effect=AssertionError("whole-file read_bytes")),
            mock.patch.object(Path, "read_text", side_effect=AssertionError("whole-file read_text")),
        ):
            sections = list(iter_v2_plain_text_sections(source, read_chars=31))

        self.assertGreater(len(sections), 100)
        self.assertTrue(all(len(section.text) <= 16_384 for section in sections))
        self.assertGreaterEqual(sum(len(section.text) for section in sections), 2_100_000)

    def test_index_bytes_are_identical_across_fresh_python_processes(self):
        script = textwrap.dedent(
            """
            import sys
            import tempfile
            from pathlib import Path
            from tools.agent_builder.corpus_pipeline import build_corpus_index_streaming
            from tools.agent_builder.models import ExtractedSection
            from tools.agent_builder.schema_v2 import Authorship, SourceGenre, SourceRecord

            def source(identifier):
                return SourceRecord(identifier, '资料 ' + identifier, identifier + '.md', 'stored-' + identifier + '.md', 'hash-' + identifier, 'md', SourceGenre.SPEECH, Authorship.DIRECT, '1926', 1, 1)
            root = Path(tempfile.mkdtemp())
            identifiers = ['a', 'b'] if sys.argv[1] == 'forward' else ['b', 'a']
            build_corpus_index_streaming(
                root,
                [source(identifier) for identifier in identifiers],
                lambda source_record: [ExtractedSection('第一章 / 第一节', '调查以后再下结论。')],
            )
            for name in ('nodes.jsonl', 'chunks.jsonl', 'duplicates.jsonl', 'report.json'):
                print((root / 'corpora' / 'index' / name).read_bytes().hex())
            """
        )
        outputs = [
            subprocess.check_output([sys.executable, "-c", script, order], cwd=Path.cwd(), text=True)
            for order in ("forward", "reverse")
        ]

        self.assertEqual(outputs[0], outputs[1])

    def test_prepare_v2_rejects_source_changed_after_descriptor_open(self):
        source = self.root / "changing.md"
        original = "# 原始\n\n原始资料。".encode("utf-8")
        replacement = "# 替换\n\n替换后的资料内容。".encode("utf-8")
        source.write_bytes(original)
        real_open = os.open
        replaced = False

        def open_then_replace(path, flags, *args, **kwargs):
            nonlocal replaced
            descriptor = real_open(path, flags, *args, **kwargs)
            if Path(path) == source and not replaced:
                replaced = True
                source.write_bytes(replacement)
            return descriptor

        with mock.patch("tools.agent_builder.builder.os.open", side_effect=open_then_replace):
            with self.assertRaisesRegex(Exception, "输入来源在读取期间发生变化"):
                prepare_workspace_v2(
                    [source],
                    self.root / "changed-workspace",
                    agent_id="person.researcher",
                    name="资料研究者",
                    version=2,
                )

    def test_prepare_v2_rejects_source_without_extractable_text(self):
        source = self.root / "empty.txt"
        source.write_text(" \n\n\t", encoding="utf-8")

        with self.assertRaisesRegex(Exception, "没有可提取文本"):
            prepare_workspace_v2(
                [source],
                self.root / "empty-workspace",
                agent_id="person.researcher",
                name="资料研究者",
                version=2,
            )

    def test_prepare_v2_writes_corpus_index(self):
        source = self.root / "source.md"
        source.write_text("# 第一章\n\n调查以后再下结论。", encoding="utf-8")
        workspace = prepare_workspace_v2(
            [source],
            self.root / "workspace",
            agent_id="person.researcher",
            name="资料研究者",
            version=2,
        )

        index = workspace / "corpora" / "index"

        self.assertTrue((index / "nodes.jsonl").is_file())
        self.assertTrue((index / "chunks.jsonl").is_file())
        self.assertTrue((index / "duplicates.jsonl").is_file())
        self.assertTrue((index / "report.json").is_file())
        chunk = json.loads((index / "chunks.jsonl").read_text("utf-8"))
        self.assertIn("context", chunk)
        self.assertIn("chunksAfterDeduplication", json.loads((index / "report.json").read_text("utf-8")))

    def _document(self, source_id: str, text: str, location: str = "正文", conflict_key: str = "") -> ExtractedDocument:
        path = self.root / f"{source_id}.md"
        path.write_text(text, encoding="utf-8")
        return ExtractedDocument(
            title=f"标题 {source_id}",
            source_path=path,
            source_hash=f"hash-{source_id}",
            sections=[ExtractedSection(location, text, conflict_key=conflict_key)],
        )

    @staticmethod
    def _source(source_id: str, period: str, title: str | None = None) -> SourceRecord:
        return SourceRecord(
            source_id=source_id,
            title=title or f"标题 {source_id}",
            file_name=f"{source_id}.md",
            stored_name=f"stored-{source_id}.md",
            source_hash=f"hash-{source_id}",
            format="md",
            genre=SourceGenre.SPEECH,
            authorship=Authorship.DIRECT,
            period=period,
            raw_size_bytes=10,
            extracted_chars=0,
        )


if __name__ == "__main__":
    unittest.main()
