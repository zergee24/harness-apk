import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock

from tools.agent_builder import corpus_pipeline
from tools.agent_builder import builder as builder_module
from tools.agent_builder.corpus_pipeline import build_corpus_index, write_corpus_index
from tools.agent_builder.builder import prepare_workspace_v2
from tools.agent_builder import extractors as extractors_module
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
        self.assertEqual(("b",), result.chunks[0].source_aliases)
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

        self.assertEqual((), result.chunks[0].source_aliases)

    def test_explicit_safe_wording_variant_merges_as_near_and_remains_auditable(self):
        first = "调查以后再下结论。"
        second = "调查之后，再下结论。"
        result = build_corpus_index(
            [self._document("a", first), self._document("b", second)],
            [self._source("a", period="1926"), self._source("b", period="1926")],
        )

        self.assertEqual(1, len(result.chunks))
        self.assertEqual(1, result.stats.near_duplicate_count)
        self.assertEqual("near", result.duplicates[0].match_type)

    def test_safe_near_duplicate_group_uses_safe_canonical_and_semantic_guard(self):
        source = self._source("a", period="1926")
        first = corpus_pipeline._contextual_chunk(
            source,
            ExtractedSection("正文", "调查以后再下结论。"),
            "调查以后再下结论。",
            (),
            "",
            0,
            0,
        )
        safe_variant = corpus_pipeline._contextual_chunk(
            source,
            ExtractedSection("正文", "调查之后，再下结论。"),
            "调查之后，再下结论。",
            (),
            "",
            0,
            1,
        )
        negation_boundary = corpus_pipeline._contextual_chunk(
            source,
            ExtractedSection("正文", "不，以后再做。"),
            "不，以后再做。",
            (),
            "",
            0,
            2,
        )
        attached_negation = corpus_pipeline._contextual_chunk(
            source,
            ExtractedSection("正文", "不之后再做。"),
            "不之后再做。",
            (),
            "",
            0,
            3,
        )

        self.assertEqual(first.duplicate_group, safe_variant.duplicate_group)
        self.assertNotEqual(negation_boundary.duplicate_group, attached_negation.duplicate_group)

    def test_semantic_guard_preserves_maximal_latin_digit_tokens_and_boundaries(self):
        exact_cases = (
            ("abc1def", "abc1 def"),
            ("notable", "not able"),
            ("abc中文", "abc 中文"),
        )
        for index, (first, second) in enumerate(exact_cases):
            with self.subTest(first=first, second=second):
                self.assertEqual(
                    corpus_pipeline.normalize_for_dedup(first),
                    corpus_pipeline.normalize_for_dedup(second),
                )
                self.assertNotEqual(
                    corpus_pipeline.semantic_guard_canonical(first),
                    corpus_pipeline.semantic_guard_canonical(second),
                )
                result = build_corpus_index(
                    [self._document(f"latin-a-{index}", first), self._document(f"latin-b-{index}", second)],
                    [
                        self._source(f"latin-a-{index}", period="1926"),
                        self._source(f"latin-b-{index}", period="1926"),
                    ],
                )
                self.assertEqual(2, len(result.chunks))

        safe_near = build_corpus_index(
            [
                self._document("latin-near-a", "abc1def 以后"),
                self._document("latin-near-b", "abc1 def 之后"),
            ],
            [
                self._source("latin-near-a", period="1926"),
                self._source("latin-near-b", period="1926"),
            ],
        )
        self.assertEqual(2, len(safe_near.chunks))
        self.assertEqual(0, safe_near.stats.near_duplicate_count)

    def test_duplicate_group_matches_physical_merge_scope(self):
        section = ExtractedSection("第一章", "调查以后再下结论。", conflict_key="method")
        source = self._source("scope", period="1926")
        primary = corpus_pipeline._contextual_chunk(source, section, section.text, (), "", 0, 0)
        safe_variant = corpus_pipeline._contextual_chunk(
            replace(source, source_id="scope-copy", source_hash="hash-scope-copy"),
            ExtractedSection("第一章", "调查之后，再下结论。", conflict_key="method"),
            "调查之后，再下结论。",
            (),
            "",
            0,
            1,
        )
        same_scope = corpus_pipeline._contextual_chunk(
            replace(source, source_id="scope-alias", source_hash="hash-scope-alias"),
            section,
            section.text,
            (),
            "",
            0,
            2,
        )
        semantic_primary = corpus_pipeline._contextual_chunk(
            source,
            ExtractedSection("第一章", "abc1def 以后", conflict_key="method"),
            "abc1def 以后",
            (),
            "",
            0,
            3,
        )
        semantic_variant = corpus_pipeline._contextual_chunk(
            source,
            ExtractedSection("第一章", "abc1 def 之后", conflict_key="method"),
            "abc1 def 之后",
            (),
            "",
            0,
            4,
        )
        scoped_variants = (
            corpus_pipeline._contextual_chunk(
                replace(source, period="1945"), section, section.text, (), "", 0, 5
            ),
            corpus_pipeline._contextual_chunk(
                source,
                ExtractedSection("第一章", section.text, conflict_key="position"),
                section.text,
                (),
                "",
                0,
                6,
            ),
            corpus_pipeline._contextual_chunk(
                replace(source, genre=SourceGenre.LETTER), section, section.text, (), "", 0, 7
            ),
            corpus_pipeline._contextual_chunk(
                replace(source, authorship=Authorship.SECONDARY), section, section.text, (), "", 0, 8
            ),
        )
        unknown_primary = corpus_pipeline._contextual_chunk(
            replace(source, period="unknown"), section, section.text, (), "", 0, 9
        )
        unknown_variants = (
            corpus_pipeline._contextual_chunk(
                replace(source, period="unknown", source_id="other"), section, section.text, (), "", 0, 10
            ),
            corpus_pipeline._contextual_chunk(
                replace(source, period="unknown", source_hash="other-hash"), section, section.text, (), "", 0, 11
            ),
            corpus_pipeline._contextual_chunk(
                replace(source, period="unknown"),
                ExtractedSection("第二章", section.text, conflict_key="method"),
                section.text,
                (),
                "",
                0,
                12,
            ),
        )

        self.assertEqual(primary.duplicate_group, safe_variant.duplicate_group)
        self.assertEqual(primary.duplicate_group, same_scope.duplicate_group)
        self.assertEqual(semantic_primary.safe_near_hash, semantic_variant.safe_near_hash)
        self.assertNotEqual(semantic_primary.duplicate_group, semantic_variant.duplicate_group)
        for variant in scoped_variants:
            self.assertNotEqual(primary.duplicate_group, variant.duplicate_group)
        for variant in unknown_variants:
            self.assertNotEqual(unknown_primary.duplicate_group, variant.duplicate_group)

    def test_semantic_guard_blocks_punctuation_candidate_collisions(self):
        cases = (
            ("不，应该这样做", "不应该这样做"),
            ("not able", "notable"),
        )

        for index, (first, second) in enumerate(cases):
            with self.subTest(first=first, second=second):
                self.assertEqual(
                    corpus_pipeline.normalize_for_dedup(first),
                    corpus_pipeline.normalize_for_dedup(second),
                )
                result = build_corpus_index(
                    [self._document(f"guard-a-{index}", first), self._document(f"guard-b-{index}", second)],
                    [
                        self._source(f"guard-a-{index}", period="1926"),
                        self._source(f"guard-b-{index}", period="1926"),
                    ],
                )
                self.assertEqual(2, len(result.chunks))
                self.assertEqual(0, result.stats.exact_duplicate_count)
                self.assertEqual(0, result.stats.near_duplicate_count)

    def test_semantic_guard_never_merges_opposition_numbers_or_subject_changes(self):
        cases = (
            ("赞成尽快采取这一行动。", "反对尽快采取这一行动。"),
            ("调查 3 次以后再下结论。", "调查 4 次之后再下结论。"),
            ("甲方以后再下结论。", "乙方之后再下结论。"),
            ("不，以后再做。", "不之后再做。"),
        )

        for index, (first, second) in enumerate(cases):
            with self.subTest(first=first, second=second):
                result = build_corpus_index(
                    [self._document(f"boundary-a-{index}", first), self._document(f"boundary-b-{index}", second)],
                    [
                        self._source(f"boundary-a-{index}", period="1926"),
                        self._source(f"boundary-b-{index}", period="1926"),
                    ],
                )
                self.assertEqual(2, len(result.chunks))
                self.assertEqual(0, result.stats.near_duplicate_count)

    def test_distinct_punctuation_only_chunks_do_not_collapse_to_one_exact_duplicate(self):
        result = build_corpus_index(
            [self._document("period", "。"), self._document("exclamation", "！")],
            [self._source("period", period="1926"), self._source("exclamation", period="1926")],
        )

        self.assertEqual(2, len(result.chunks))
        self.assertEqual(0, result.stats.exact_duplicate_count)

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

    def test_streaming_index_recalls_explicit_safe_wording_variant(self):
        root = self.root / "streaming-near"
        first = "调查以后再下结论。"
        second = "调查之后，再下结论。"
        sources = [self._source("a", period="1926"), self._source("b", period="1926")]

        stats = corpus_pipeline.build_corpus_index_streaming(
            root,
            sources,
            lambda source: [
                ExtractedSection("正文", first if source.source_id == "a" else second)
            ],
        )

        chunks = (root / "corpora" / "index" / "chunks.jsonl").read_text("utf-8").splitlines()
        self.assertEqual(1, len(chunks))
        self.assertEqual(1, stats.near_duplicate_count)
        self.assertEqual(["b"], json.loads(chunks[0])["sourceAliases"])

    def test_simhash_remains_deterministic_but_does_not_authorize_a_merge(self):
        self.assertEqual(
            corpus_pipeline.simhash64("调查以后再下结论。"),
            corpus_pipeline.simhash64("调查以后再下结论。"),
        )
        result = build_corpus_index(
            [
                self._document("support", "赞成尽快采取这一行动。"),
                self._document("oppose", "反对尽快采取这一行动。"),
            ],
            [self._source("support", period="1926"), self._source("oppose", period="1926")],
        )
        self.assertEqual(2, len(result.chunks))

    def test_unknown_period_exact_query_uses_source_scoped_exact_index(self):
        candidate = build_corpus_index(
            [self._document("unknown", "共同的模板材料。")],
            [self._source("unknown", period="unknown")],
        ).chunks[0]
        database = sqlite3.connect(self.root / "unknown-exact.sqlite3")
        database.row_factory = sqlite3.Row
        try:
            corpus_pipeline._create_streaming_schema(database)
            where, params = corpus_pipeline._streaming_match_constraints(
                candidate,
                "physical_chunks",
            )
            plan = database.execute(
                "EXPLAIN QUERY PLAN SELECT * FROM physical_chunks WHERE "
                + where
                + " AND physical_chunks.normalized_hash = ? ORDER BY sort_key LIMIT 1",
                (*params, candidate.normalized_hash),
            ).fetchall()
        finally:
            database.close()

        self.assertTrue(
            any("physical_exact_unknown_index" in row[3] for row in plan),
            [row[3] for row in plan],
        )

    def test_safe_near_query_uses_indexed_safe_canonical_hash(self):
        candidate = build_corpus_index(
            [self._document("near", "调查之后，再下结论。")],
            [self._source("near", period="1926")],
        ).chunks[0]
        database = sqlite3.connect(self.root / "safe-near.sqlite3")
        database.row_factory = sqlite3.Row
        try:
            corpus_pipeline._create_streaming_schema(database)
            where, params = corpus_pipeline._streaming_match_constraints(
                candidate,
                "physical_chunks",
            )
            plan = database.execute(
                "EXPLAIN QUERY PLAN SELECT * FROM physical_chunks WHERE "
                + where
                + " AND safe_near_hash = ? AND semantic_guard_hash = ? "
                "AND normalized_hash != ? ORDER BY sort_key LIMIT 1",
                (
                    *params,
                    candidate.safe_near_hash,
                    candidate.semantic_guard_hash,
                    candidate.normalized_hash,
                ),
            ).fetchall()
        finally:
            database.close()

        self.assertTrue(
            any("physical_safe_near_index" in row[3] for row in plan),
            [row[3] for row in plan],
        )

    def test_streaming_index_does_not_publish_partial_files_when_writing_fails(self):
        root = self.root / "atomic-index"
        source = self._source("a", period="1926")

        with mock.patch.object(
            corpus_pipeline,
            "_write_jsonl_cursor",
            side_effect=OSError("injected index write failure"),
        ):
            with self.assertRaisesRegex(OSError, "injected index write failure"):
                corpus_pipeline.build_corpus_index_streaming(
                    root,
                    [source],
                    lambda _: [ExtractedSection("正文", "调查以后再下结论。")],
                )

        index_parent = root / "corpora"
        self.assertFalse((index_parent / "index").exists())
        self.assertFalse(any(path.name.startswith(".index-staging-") for path in index_parent.iterdir()))

    def test_streaming_index_cleans_temp_files_when_sqlite_connect_fails(self):
        root = self.root / "sqlite-connect-failure"
        source = self._source("a", period="1926")

        with mock.patch.object(
            corpus_pipeline.sqlite3,
            "connect",
            side_effect=OSError("injected sqlite connect failure"),
        ):
            with self.assertRaisesRegex(OSError, "injected sqlite connect failure"):
                corpus_pipeline.build_corpus_index_streaming(
                    root,
                    [source],
                    lambda _: [ExtractedSection("正文", "调查以后再下结论。")],
                )

        index_parent = root / "corpora"
        self.assertFalse((index_parent / "index").exists())
        self.assertFalse(any(path.name.startswith(".index-staging-") for path in index_parent.iterdir()))
        self.assertFalse(any(root.glob(".corpus-index-*.sqlite3")))

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
        self.assertEqual(("b",), first.chunks[0].source_aliases)

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
        original = "资料" * 1_100_000
        source.write_text(original, encoding="utf-8")

        with (
            mock.patch.object(Path, "read_bytes", side_effect=AssertionError("whole-file read_bytes")),
            mock.patch.object(Path, "read_text", side_effect=AssertionError("whole-file read_text")),
        ):
            sections = list(iter_v2_plain_text_sections(source, read_chars=31))

        self.assertGreater(len(sections), 100)
        self.assertTrue(all(len(section.text) <= 16_384 for section in sections))
        self.assertEqual(original, "".join(section.text for section in sections))
        self.assertEqual(len(original), sum(len(section.text) for section in sections))

    def test_v2_plain_text_reader_does_not_inject_newlines_into_windowed_single_line(self):
        source = self.root / "windowed-single-line.txt"
        original = "甲" * 1_000
        source.write_text(original, encoding="utf-8")

        sections = list(
            iter_v2_plain_text_sections(source, read_chars=17, max_section_chars=128)
        )

        self.assertEqual(original, "".join(section.text for section in sections))

    def test_v2_plain_text_reader_normalizes_whitespace_stably_across_windows(self):
        source = self.root / "whitespace-boundary.md"
        original = (
            "# 第一章\r\n"
            + ("甲" * 120)
            + " \t 乙\r\n\r\n\r\n丙\r\n"
            + "## 第二节\r\n"
            + "丁\t\t戊"
        )
        source.write_text(original, encoding="utf-8", newline="")

        small = list(iter_v2_plain_text_sections(source, read_chars=1, max_section_chars=128))
        large = list(iter_v2_plain_text_sections(source, read_chars=4096, max_section_chars=128))

        self.assertEqual(small, large)
        self.assertEqual(
            ["第一章", "第一章 / 第二节"],
            list(dict.fromkeys(section.location for section in small)),
        )
        self.assertEqual(
            extractors_module._normalize_text(original),
            self._reconstruct_streamed_sections(small),
        )

    def test_v2_plain_text_reader_keeps_whitespace_across_multiple_manual_flushes(self):
        source = self.root / "manual-flush-whitespace.txt"
        original = (
            ("甲" * 128)
            + "乙 \t  丙"
            + ("丁" * 128)
            + "戊\t\t己"
            + ("庚" * 128)
            + "辛"
        )
        source.write_text(original, encoding="utf-8")

        sections = list(iter_v2_plain_text_sections(source, read_chars=1, max_section_chars=128))

        self.assertGreaterEqual(len(sections), 4)
        self.assertTrue(all(section.location == "正文" for section in sections))
        self.assertEqual(
            extractors_module._normalize_text(original),
            "".join(section.text for section in sections),
        )

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

    def test_prepare_v2_rejects_staged_source_replaced_before_index_publish(self):
        source = self.root / "replace-between-phases.md"
        original = "# 原始\n\n原始资料。".encode("utf-8")
        replacement = "# 替换\n\n替换资料。".encode("utf-8")
        source.write_bytes(original)
        real_build = builder_module.build_corpus_index_streaming

        def replace_before_index(workspace, sources, sections_for_source):
            staged_source = Path(workspace) / "sources" / sources[0].stored_name
            staged_source.write_bytes(replacement)
            return real_build(workspace, sources, sections_for_source)

        with mock.patch.object(
            builder_module,
            "build_corpus_index_streaming",
            side_effect=replace_before_index,
        ):
            with self.assertRaisesRegex(
                Exception,
                "来源文件在索引前发生变化|来源文件.*不匹配|来源文件在读取期间发生变化",
            ):
                prepare_workspace_v2(
                    [source],
                    self.root / "replaced-workspace",
                    agent_id="person.researcher",
                    name="资料研究者",
                    version=2,
                )

    def test_prepare_v2_rejects_source_changed_between_index_hash_and_parse(self):
        source = self.root / "replace-during-index.md"
        source.write_text("# 原始\n\n原始资料。", encoding="utf-8")
        replacement = "# 替换\n\n替换后的索引内容。".encode("utf-8")
        real_iter = builder_module.iter_v2_source_sections_stream
        replaced = False

        def replace_after_hash(stream, suffix, display_name, **kwargs):
            nonlocal replaced
            if not replaced:
                replaced = True
                staging = next(self.root.glob(".source-index-workspace.staging-*"))
                staged_source = next((staging / "sources").iterdir())
                staged_source.write_bytes(replacement)
            yield from real_iter(stream, suffix, display_name, **kwargs)

        with mock.patch.object(
            builder_module,
            "iter_v2_source_sections_stream",
            side_effect=replace_after_hash,
        ):
            with self.assertRaisesRegex(Exception, "来源文件在读取期间发生变化"):
                prepare_workspace_v2(
                    [source],
                    self.root / "source-index-workspace",
                    agent_id="person.researcher",
                    name="资料研究者",
                    version=2,
                )

    def test_prepare_v2_rejects_same_inode_same_size_mtime_restored_change(self):
        source = self.root / "ctime-change.md"
        original = b"# Original\n\nAAAAAA"
        replacement = b"# Modified\n\nBBBBBB"
        self.assertEqual(len(original), len(replacement))
        source.write_bytes(original)
        source_stat = source.stat()
        real_fstat = os.fstat
        changed = False

        def fstat_then_change(descriptor):
            nonlocal changed
            observed = real_fstat(descriptor)
            if not changed:
                changed = True
                source.write_bytes(replacement)
                os.utime(source, ns=(source_stat.st_atime_ns, source_stat.st_mtime_ns))
            return observed

        with mock.patch("tools.agent_builder.builder.os.fstat", side_effect=fstat_then_change):
            with self.assertRaisesRegex(Exception, "输入来源在读取期间发生变化"):
                prepare_workspace_v2(
                    [source],
                    self.root / "ctime-workspace",
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

    @staticmethod
    def _reconstruct_streamed_sections(sections: list[ExtractedSection]) -> str:
        result: list[str] = []
        previous_location: str | None = None
        for section in sections:
            if previous_location is not None and section.location != previous_location:
                result.append("\n")
            result.append(section.text)
            previous_location = section.location
        return "".join(result)


if __name__ == "__main__":
    unittest.main()
