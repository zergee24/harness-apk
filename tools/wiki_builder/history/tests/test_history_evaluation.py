import json
import sqlite3
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.evaluation import RetrievalReport
from tools.wiki_builder.cli import main
from tools.wiki_builder.history.evaluation import (
    PAIR_CATEGORY_MINIMUMS,
    SINGLE_CATEGORIES,
    SINGLE_MINIMUM_PER_CATEGORY,
    PairEvaluationReport,
    create_evaluation_template,
    evaluate_pair,
    pair_gate_failures,
    single_gate_failures,
    validate_evaluation_set,
)
from tools.wiki_builder.history.tests.helpers import build_history_workspace
from tools.wiki_builder.models import BuildError
from tools.wiki_builder.normalization import chinese_ngrams
from tools.wiki_builder.validation import validate_workspace


class HistoryEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.left = build_history_workspace(
            self.root / "left",
            wiki_id="fixture.history.left",
            leaf_texts=(
                ("前403年，共同事件甲见于左史，司马光字君实。",),
                ("共同专题乙分见多卷，赤乌专案。",),
            ),
        )
        self.right = build_history_workspace(
            self.root / "right",
            wiki_id="fixture.history.right",
            leaf_texts=(
                ("前403年，共同事件甲见于右史。",),
                ("共同专题乙另有记载，青龙秘录。",),
            ),
        )
        self._add_semantic_channels(self.left)
        self._add_semantic_channels(self.right)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_templates_are_balanced_and_never_pretend_to_be_reviewed(self):
        single_dir = self.root / "single-evaluation"
        pair_dir = self.root / "pair-evaluation"

        create_evaluation_template((self.left,), single_dir)
        create_evaluation_template((self.left, self.right), pair_dir, cross_wiki=True)

        single = self._read_rows(single_dir / "cases.jsonl")
        pair = self._read_rows(pair_dir / "cases.jsonl")
        self.assertEqual(
            {category: SINGLE_MINIMUM_PER_CATEGORY for category in SINGLE_CATEGORIES},
            Counter(row["category"] for row in single),
        )
        self.assertEqual(dict(PAIR_CATEGORY_MINIMUMS), Counter(row["category"] for row in pair))
        self.assertTrue(all(row["reviewed"] is False for row in single + pair))
        self.assertTrue(all(row["reviewerNotes"] == "" for row in single + pair))

        with self.assertRaisesRegex(BuildError, "尚未人工复核"):
            validate_evaluation_set(single_dir)

    def test_checked_fixture_documents_every_single_category_canonically(self):
        fixture = Path(__file__).parent / "fixtures/history-evaluation.jsonl"
        raw_lines = fixture.read_bytes().splitlines(keepends=True)
        rows = [json.loads(line) for line in raw_lines]

        self.assertEqual(set(SINGLE_CATEGORIES), {row["category"] for row in rows})
        self.assertTrue(all(row["reviewed"] is False for row in rows))
        self.assertEqual(
            raw_lines,
            [canonical_json_bytes(row) + b"\n" for row in rows],
        )

    def test_single_set_requires_balance_unique_queries_and_existing_gold(self):
        evaluation = self.root / "single-valid"
        create_evaluation_template((self.left,), evaluation)
        rows = self._reviewed_single_rows(self.left)
        self._write_rows(evaluation / "cases.jsonl", rows)

        validated = validate_evaluation_set(evaluation)
        self.assertEqual("single", validated.scope)
        self.assertEqual(160, len(validated.cases))

        missing_category = [
            row for row in rows if row["category"] != SINGLE_CATEGORIES[-1]
        ]
        self._write_rows(evaluation / "cases.jsonl", missing_category)
        with self.assertRaisesRegex(BuildError, "类别配额不足"):
            validate_evaluation_set(evaluation)

        duplicate_query = self._reviewed_single_rows(self.left)
        duplicate_query[1]["query"] = duplicate_query[0]["query"]
        self._write_rows(evaluation / "cases.jsonl", duplicate_query)
        with self.assertRaisesRegex(BuildError, "规范化 query 重复"):
            validate_evaluation_set(evaluation)

        missing_gold = self._reviewed_single_rows(self.left)
        missing_gold[0]["goldEvidence"][0]["chunkId"] = "missing-chunk"
        self._write_rows(evaluation / "cases.jsonl", missing_gold)
        with self.assertRaisesRegex(BuildError, "不存在的 chunk"):
            validate_evaluation_set(evaluation)

    def test_quote_and_citation_snapshot_must_match_saved_original(self):
        evaluation = self.root / "citation-validity"
        create_evaluation_template((self.left,), evaluation)

        bad_quote = self._reviewed_single_rows(self.left)
        bad_quote[0]["goldEvidence"][0]["quote"] = "原文中并不存在"
        self._write_rows(evaluation / "cases.jsonl", bad_quote)
        with self.assertRaisesRegex(BuildError, "quote 不是原文连续片段"):
            validate_evaluation_set(evaluation)

        bad_locator = self._reviewed_single_rows(self.left)
        bad_locator[0]["goldEvidence"][0]["locator"]["paragraphNumber"] = 999
        self._write_rows(evaluation / "cases.jsonl", bad_locator)
        with self.assertRaisesRegex(BuildError, "locator 与数据库不一致"):
            validate_evaluation_set(evaluation)

        bad_version = self._reviewed_single_rows(self.left)
        bad_version[0]["goldEvidence"][0]["wikiVersion"] = 2
        self._write_rows(evaluation / "cases.jsonl", bad_version)
        with self.assertRaisesRegex(BuildError, "Wiki 身份不一致"):
            validate_evaluation_set(evaluation)

    def test_positive_gap_and_no_result_categories_execute_real_retrieval(self):
        evaluation = self.root / "single-retrieval"
        create_evaluation_template((self.left,), evaluation)
        self._write_rows(evaluation / "cases.jsonl", self._reviewed_single_rows(self.left))

        validated = validate_evaluation_set(evaluation)
        report = validated.evaluate_single()

        self.assertEqual(160, report.case_count)
        self.assertEqual(1.0, report.overall_recall_at_20, report.to_dict())
        self.assertEqual(set(SINGLE_CATEGORIES), set(report.category_recall))
        self.assertEqual((), single_gate_failures(report))

    def test_cross_wiki_set_covers_both_sides_and_no_result_controls(self):
        evaluation = self.root / "pair-valid"
        create_evaluation_template(
            (self.left, self.right), evaluation, cross_wiki=True
        )
        self._write_rows(
            evaluation / "cases.jsonl",
            self._reviewed_pair_rows(self.left, self.right),
        )

        validated = validate_evaluation_set(evaluation)
        report = evaluate_pair(self.left, self.right, evaluation / "cases.jsonl")

        self.assertEqual("pair", validated.scope)
        self.assertEqual(60, report.case_count)
        self.assertEqual(1.0, report.gold_coverage_at_12)
        self.assertEqual(1.0, report.both_side_case_coverage_at_12)
        self.assertEqual((), report.failed_case_ids)
        self.assertEqual((), pair_gate_failures(report))

    def test_one_sided_gap_fails_when_the_other_wiki_also_returns_evidence(self):
        evaluation = self.root / "pair-leak"
        create_evaluation_template(
            (self.left, self.right), evaluation, cross_wiki=True
        )
        rows = self._reviewed_pair_rows(self.left, self.right)
        leaking = next(row for row in rows if row["category"] == "one-sided-gap")
        leaking["query"] = "共同 one-sided-leak"
        leaking["goldEvidence"] = [self._evidence(self.left)[0]]
        self._write_rows(evaluation / "cases.jsonl", rows)

        report = evaluate_pair(self.left, self.right, evaluation / "cases.jsonl")

        self.assertIn(leaking["caseId"], report.failed_case_ids)
        self.assertLess(report.category_coverage["one-sided-gap"], 1.0)

    def test_publication_threshold_boundaries_are_fixed(self):
        passing_single = RetrievalReport(
            case_count=160,
            overall_recall_at_20=0.90,
            category_recall={category: 0.85 for category in SINGLE_CATEGORIES},
            failed_case_ids=(),
        )
        self.assertEqual((), single_gate_failures(passing_single))
        self.assertIn(
            "overall_recall",
            single_gate_failures(
                RetrievalReport(160, 0.899999, passing_single.category_recall, ())
            ),
        )
        low_category = dict(passing_single.category_recall)
        low_category[SINGLE_CATEGORIES[0]] = 0.849999
        self.assertIn(
            "category_recall",
            single_gate_failures(RetrievalReport(160, 0.90, low_category, ())),
        )

        passing_pair = PairEvaluationReport(
            case_count=60,
            gold_coverage_at_12=0.90,
            both_side_case_coverage_at_12=0.90,
            category_coverage={category: 1.0 for category in PAIR_CATEGORY_MINIMUMS},
            quote_match_rate=1.0,
            citation_validity_rate=1.0,
            failed_case_ids=(),
        )
        self.assertEqual((), pair_gate_failures(passing_pair))
        self.assertIn(
            "pair_gold_coverage",
            pair_gate_failures(
                PairEvaluationReport(
                    60,
                    0.899999,
                    1.0,
                    passing_pair.category_coverage,
                    1.0,
                    1.0,
                    (),
                )
            ),
        )

    def test_generic_validate_accepts_reviewed_history_cases(self):
        evaluation = self.root / "history-validation"
        create_evaluation_template((self.left,), evaluation)
        self._write_rows(evaluation / "cases.jsonl", self._reviewed_single_rows(self.left))

        report = validate_workspace(self.left, evaluation / "cases.jsonl")

        self.assertEqual(1.0, report.retrieval.overall_recall_at_20)
        self.assertNotIn("evaluation_invalid", report.error_codes)

    def test_history_workspace_uses_cases_jsonl_as_default_evaluation(self):
        evaluation = self.left / "evaluation"
        create_evaluation_template((self.left,), evaluation)
        self._write_rows(evaluation / "cases.jsonl", self._reviewed_single_rows(self.left))

        report = validate_workspace(self.left)

        self.assertEqual(1.0, report.retrieval.overall_recall_at_20)
        self.assertNotIn("missing_evaluation", report.error_codes)
        self.assertNotIn("evaluation_invalid", report.error_codes)

    def test_history_cli_creates_validates_and_evaluates_templates(self):
        single = self.root / "cli-single"
        self.assertEqual(
            0,
            main(
                [
                    "history",
                    "create-eval-template",
                    str(self.left),
                    "--output",
                    str(single),
                    "--minimum-cases",
                    "160",
                ]
            ),
        )
        self.assertEqual(1, main(["history", "validate-eval", str(single)]))
        self._write_rows(single / "cases.jsonl", self._reviewed_single_rows(self.left))
        self.assertEqual(0, main(["history", "validate-eval", str(single)]))

        pair = self.root / "cli-pair"
        self.assertEqual(
            0,
            main(
                [
                    "history",
                    "create-eval-template",
                    str(self.left),
                    str(self.right),
                    "--cross-wiki",
                    "--output",
                    str(pair),
                    "--minimum-cases",
                    "60",
                ]
            ),
        )
        self._write_rows(
            pair / "cases.jsonl", self._reviewed_pair_rows(self.left, self.right)
        )
        self.assertEqual(
            0,
            main(
                [
                    "history",
                    "evaluate-pair",
                    str(self.left),
                    str(self.right),
                    "--evaluation",
                    str(pair / "cases.jsonl"),
                ]
            ),
        )

    def _reviewed_single_rows(self, workspace: Path) -> list[dict[str, object]]:
        evidence = self._evidence(workspace)
        rows: list[dict[str, object]] = []
        positive_queries = {
            "original-keyword": "共同事件甲",
            "modern-paraphrase": "现代财政制度",
            "alias-title-place": "君实",
            "time-expression": "前403年",
            "homonym-disambiguation": "司马光字君实",
            "multi-volume-synthesis": "共同",
        }
        for category in SINGLE_CATEGORIES:
            for number in range(1, SINGLE_MINIMUM_PER_CATEGORY + 1):
                positive = category in positive_queries
                query = (
                    f"{positive_queries[category]} case{number:03d}"
                    if positive
                    else f"不存在的火星史料 {category} case{number:03d}"
                )
                gold = [evidence[0]]
                if category == "multi-volume-synthesis":
                    gold = evidence[:2]
                rows.append(
                    self._case(
                        f"{category}-{number:03d}",
                        "single",
                        category,
                        query,
                        "evidence" if positive else "no-result",
                        gold if positive else [],
                    )
                )
        return sorted(rows, key=lambda row: row["caseId"])

    def _reviewed_pair_rows(
        self, left: Path, right: Path
    ) -> list[dict[str, object]]:
        left_evidence = self._evidence(left)
        right_evidence = self._evidence(right)
        rows: list[dict[str, object]] = []
        for category, count in PAIR_CATEGORY_MINIMUMS.items():
            for number in range(1, count + 1):
                if category in {"mutual-corroboration", "differing-account"}:
                    query = f"共同事件甲 pair{category}{number:03d}"
                    expected = "both"
                    gold = [left_evidence[0], right_evidence[0]]
                elif category == "one-sided-gap":
                    query = f"赤乌专案 pair{number:03d}"
                    expected = "one-sided"
                    gold = [left_evidence[1]]
                else:
                    query = f"双库都不存在的火星史料 pair{number:03d}"
                    expected = "no-result"
                    gold = []
                rows.append(
                    self._case(
                        f"{category}-{number:03d}",
                        "pair",
                        category,
                        query,
                        expected,
                        gold,
                    )
                )
        return sorted(rows, key=lambda row: row["caseId"])

    @staticmethod
    def _case(
        case_id: str,
        scope: str,
        category: str,
        query: str,
        expected_result: str,
        gold: list[dict[str, object]],
    ) -> dict[str, object]:
        return {
            "caseId": case_id,
            "scope": scope,
            "category": category,
            "query": query,
            "expectedResult": expected_result,
            "goldEvidence": gold,
            "reviewed": True,
            "reviewerNotes": "已逐条核对原文与可读位置。",
        }

    @staticmethod
    def _evidence(workspace: Path) -> list[dict[str, object]]:
        manifest = json.loads((workspace / "workspace.json").read_bytes())
        with sqlite3.connect(workspace / "content.sqlite") as database:
            rows = database.execute(
                """
                SELECT documents.document_id, sections.section_id, chunks.chunk_id,
                       chunks.original_text, chunks.locator_json
                FROM chunks
                JOIN sections USING(section_id)
                JOIN documents USING(document_id)
                ORDER BY sections.ordinal, chunks.ordinal
                """
            ).fetchall()
        return [
            {
                "wikiId": manifest["wiki"]["id"],
                "wikiVersion": manifest["wiki"]["version"],
                "documentId": document_id,
                "sectionId": section_id,
                "chunkId": chunk_id,
                "quote": original_text,
                "locator": json.loads(locator_json),
            }
            for document_id, section_id, chunk_id, original_text, locator_json in rows
        ]

    @staticmethod
    def _add_semantic_channels(workspace: Path) -> None:
        with sqlite3.connect(workspace / "content.sqlite") as database:
            chunk_id = database.execute(
                "SELECT chunk_id FROM chunks ORDER BY rowid LIMIT 1"
            ).fetchone()[0]
            database.execute(
                "INSERT INTO summaries VALUES (?, ?, ?, ?, ?)",
                ("summary-modern", "document", "fixture", "document", "现代财政制度"),
            )
            database.execute(
                "INSERT INTO summaries_fts VALUES (?, ?)",
                (
                    "summary-modern",
                    "现代财政制度 " + " ".join(chinese_ngrams("现代财政制度")),
                ),
            )
            database.execute(
                "INSERT INTO evidence_refs VALUES (?, ?, ?, ?, ?)",
                ("summary", "summary-modern", chunk_id, "support", 0),
            )
            database.execute(
                "INSERT INTO terms VALUES (?, ?, ?, ?, ?, ?)",
                (
                    "term-sima-guang",
                    "cn-history-v1:person:sima-guang",
                    "司马光",
                    "person",
                    1.0,
                    "{}",
                ),
            )
            database.execute(
                "INSERT INTO aliases VALUES (?, ?, ?, ?, ?)",
                ("alias-junshi", "term-sima-guang", "君实", "君实", 1.0),
            )
            database.execute(
                "INSERT INTO terms_aliases_fts VALUES (?, ?, ?)",
                ("term-sima-guang", "司马光", "君实"),
            )
            database.execute(
                "INSERT INTO evidence_refs VALUES (?, ?, ?, ?, ?)",
                ("term", "term-sima-guang", chunk_id, "support", 0),
            )
            database.commit()

    @staticmethod
    def _write_rows(path: Path, rows: list[dict[str, object]]) -> None:
        path.write_bytes(b"".join(canonical_json_bytes(row) + b"\n" for row in rows))

    @staticmethod
    def _read_rows(path: Path) -> list[dict[str, object]]:
        return [json.loads(line) for line in path.read_bytes().splitlines()]


if __name__ == "__main__":
    unittest.main()
