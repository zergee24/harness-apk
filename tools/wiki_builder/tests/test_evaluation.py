import sqlite3
import tempfile
import unittest
from pathlib import Path

from tools.wiki_builder.evaluation import (
    RetrievalCase,
    evaluate_retrieval,
    load_retrieval_cases,
    reciprocal_rank_fusion,
)
from tools.wiki_builder.models import BuildError
from tools.wiki_builder.tests.helpers import build_publishable_workspace


class RetrievalEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.workspace, self.ids = build_publishable_workspace(self.root)
        self.cases = load_retrieval_cases(
            self.workspace / "evaluation/retrieval-eval.jsonl"
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_four_channels_resolve_to_source_chunks_with_full_recall(self):
        report = evaluate_retrieval(self.workspace / "content.sqlite", self.cases)

        self.assertEqual(1.0, report.overall_recall_at_20)
        self.assertEqual(
            {"alias": 1.0, "normalized": 1.0, "original": 1.0, "summary": 1.0},
            report.category_recall,
        )
        self.assertEqual((), report.failed_case_ids)
        self.assertEqual(4, report.case_count)

    def test_rrf_is_deterministic_and_deduplicates_each_channel(self):
        self.assertEqual(
            ["b", "a", "c"],
            reciprocal_rank_fusion([["a", "b", "b"], ["b", "c"]]),
        )
        self.assertEqual(
            reciprocal_rank_fusion([["x", "y"], ["y", "x"]]),
            ["x", "y"],
        )

    def test_unknown_gold_chunk_or_duplicate_case_is_rejected(self):
        bad = RetrievalCase(
            case_id="bad",
            category="original",
            query="司馬光",
            expected_chunk_ids=frozenset({"missing-chunk"}),
        )
        with self.assertRaisesRegex(BuildError, "missing-chunk"):
            evaluate_retrieval(self.workspace / "content.sqlite", [bad])

        duplicate = [self.cases[0], self.cases[0]]
        with self.assertRaisesRegex(BuildError, "重复"):
            evaluate_retrieval(self.workspace / "content.sqlite", duplicate)

    def test_clear_no_result_case_passes_only_when_nothing_is_retrieved(self):
        no_result = RetrievalCase(
            case_id="no-result",
            category="no-result",
            query="完全不存在的火星词条XYZ987",
            expected_chunk_ids=frozenset(),
        )
        report = evaluate_retrieval(
            self.workspace / "content.sqlite", [*self.cases, no_result]
        )
        self.assertEqual(1.0, report.category_recall["no-result"])


if __name__ == "__main__":
    unittest.main()
