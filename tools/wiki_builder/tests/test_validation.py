import sqlite3
import tempfile
import unittest
from pathlib import Path

from tools.wiki_builder.tests.helpers import build_publishable_workspace, write_jsonl
from tools.wiki_builder.validation import validate_workspace


class WorkspaceValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.workspace, self.ids = build_publishable_workspace(self.root)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_publishable_fixture_meets_all_hard_gates(self):
        report = validate_workspace(self.workspace)

        self.assertTrue(report.publishable, report.to_dict())
        self.assertEqual("ok", report.integrity_check)
        self.assertEqual(0, report.foreign_key_errors)
        self.assertEqual(0, report.orphan_evidence_count)
        self.assertEqual(0, report.invalid_locator_count)
        self.assertEqual(1.0, report.extracted_source_coverage)
        self.assertGreaterEqual(report.retrieval.overall_recall_at_20, 0.90)
        self.assertEqual([], report.to_dict()["errors"])

    def test_missing_volume_summary_blocks_publish(self):
        with sqlite3.connect(self.workspace / "content.sqlite") as database:
            summary_id = database.execute(
                "SELECT summary_id FROM summaries WHERE owner_type='section' ORDER BY summary_id LIMIT 1"
            ).fetchone()[0]
            database.execute("DELETE FROM summaries WHERE summary_id=?", (summary_id,))
            database.execute("DELETE FROM summaries_fts WHERE summary_id=?", (summary_id,))
            database.execute(
                "DELETE FROM evidence_refs WHERE owner_type='summary' AND owner_id=?",
                (summary_id,),
            )

        report = validate_workspace(self.workspace)

        self.assertFalse(report.publishable)
        self.assertIn("missing_volume_summary", report.error_codes)

    def test_locator_noncanonical_json_and_trigger_are_reported_not_raised(self):
        with sqlite3.connect(self.workspace / "content.sqlite") as database:
            database.execute("DELETE FROM source_locators WHERE chunk_id=?", (self.ids["original"],))
            database.execute(
                "UPDATE documents SET metadata_json=' {\"fileName\": \"source.md\"}'"
            )
            database.execute(
                "CREATE TRIGGER forbidden AFTER INSERT ON documents BEGIN SELECT 1; END"
            )

        report = validate_workspace(self.workspace)

        self.assertFalse(report.publishable)
        self.assertIn("invalid_locator", report.error_codes)
        self.assertIn("noncanonical_json", report.error_codes)
        self.assertIn("sqlite_shape", report.error_codes)

    def test_retrieval_thresholds_block_publication(self):
        write_jsonl(
            self.workspace / "evaluation/retrieval-eval.jsonl",
            [
                {
                    "caseId": "miss",
                    "category": "summary",
                    "query": "不存在的查询",
                    "expectedChunkIds": [self.ids["summary"]],
                }
            ],
        )

        report = validate_workspace(self.workspace)

        self.assertFalse(report.publishable)
        self.assertIn("overall_recall", report.error_codes)
        self.assertIn("category_recall", report.error_codes)


if __name__ == "__main__":
    unittest.main()
