import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.agent_builder.builder import prepare_workspace_v2, validate_workspace_v2
from tools.agent_builder import evaluation
from tools.agent_builder.evaluation import (
    MAX_INDEX_TERMS_PER_CHUNK,
    MAX_JSONL_LINE_BYTES,
    MINIMUM_EVAL_COUNTS,
    MIN_GROUNDING_RATE,
    MIN_STANCE_RATE,
    evaluate_workspace,
    validate_declared_corpus_question_coverage,
)


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.source = self.root / "source.md"
        self.source.write_text("# 调查\n\n调查以后再下结论。", encoding="utf-8")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_valid_assets_and_eval_are_publishable_and_stratified(self):
        workspace = self._complete_workspace()

        report = validate_workspace_v2(workspace)
        evaluation = evaluate_workspace(workspace)

        self.assertTrue(report.publishable, report.errors)
        self.assertEqual(set(MINIMUM_EVAL_COUNTS), set(evaluation.category_metrics))
        for category, minimum in MINIMUM_EVAL_COUNTS.items():
            self.assertEqual(minimum, evaluation.category_metrics[category].total)
        self.assertEqual(MIN_GROUNDING_RATE, evaluation.minimum_grounding_rate)
        self.assertEqual(MIN_STANCE_RATE, evaluation.minimum_stance_rate)
        self.assertIn("1926", evaluation.by_period)
        self.assertIn("direct", evaluation.by_authorship)
        self.assertIn("unassigned", evaluation.by_corpus)

    def test_secondary_evidence_cannot_ground_voice_or_episode(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        rows[0]["authorship"] = "secondary"
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertIn("voice 只能引用 direct 或 edited_direct", joined)
        self.assertIn("episode 至少需要一条 direct 或 edited_direct 证据", joined)

    def test_rejects_bad_evidence_and_synthesized_example_contract(self):
        workspace = self._complete_workspace()
        identity = self._asset(workspace, "identity.json")
        value = json.loads(identity.read_text("utf-8"))
        value["relationships"] = [{"subject": "甲", "relation": "同事", "period": "1926", "evidence": ["missing", "missing"]}]
        identity.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        examples = self._asset(workspace, "examples.jsonl")
        row = json.loads(examples.read_text("utf-8").strip())
        row["generationType"] = "historical_quote"
        examples.write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertIn("evidence 不能包含重复 chunk ID", joined)
        self.assertIn("引用了不存在的 chunk ID：missing", joined)
        self.assertIn("examples.generationType 必须是 synthesized", joined)

    def test_grounding_and_stance_thresholds_block_independently(self):
        workspace = self._complete_workspace()
        eval_path = self._asset(workspace, "eval.jsonl")
        rows = [json.loads(line) for line in eval_path.read_text("utf-8").splitlines()]
        for row in rows[:4]:
            row["question"] = "完全不相干的检索词"
        rows[MINIMUM_EVAL_COUNTS["grounding"]]["period"] = "1945"
        eval_path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertIn("grounding 通过率不足", joined)
        self.assertIn("stance 通过率不足", joined)

    def test_uses_manifest_asset_paths_and_rejects_symlinked_custom_asset(self):
        workspace = self._complete_workspace()
        manifest_path = workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        custom = workspace / "agent" / "custom"
        custom.mkdir()
        for key, relative in manifest["assets"].items():
            if key == "persona":
                continue
            source = workspace / relative
            target = custom / source.name
            source.replace(target)
            manifest["assets"][key] = f"agent/custom/{target.name}"
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")

        self.assertTrue(validate_workspace_v2(workspace).publishable)

        outside = self.root / "outside-identity.json"
        outside.write_text('{"relationships":[]}', encoding="utf-8")
        identity = custom / "identity.json"
        identity.unlink()
        identity.symlink_to(outside)
        report = evaluate_workspace(workspace)

        self.assertIn("identity 无法读取", "\n".join(report.errors))

    def test_rejects_symlink_duplicate_and_malformed_chunk_rows_with_line_numbers(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        first = chunks.read_bytes().splitlines()[0]
        chunks.write_bytes(first + b"\n" + first + b"\n{" + b"\n")

        report = evaluate_workspace(workspace)

        joined = "\n".join(report.errors)
        self.assertIn("第 2 行存在重复 chunk ID", joined)
        self.assertIn("第 3 行无法读取", joined)

        outside = self.root / "outside-chunks.jsonl"
        outside.write_bytes(first + b"\n")
        chunks.unlink()
        chunks.symlink_to(outside)
        report = evaluate_workspace(workspace)
        self.assertIn("资料索引无法读取", "\n".join(report.errors))

    def test_rejects_duplicate_semantic_ids_and_deep_chunk_json_without_raising(self):
        workspace = self._complete_workspace()
        examples = self._asset(workspace, "examples.jsonl")
        row = json.loads(examples.read_text("utf-8").strip())
        examples.write_text(
            "\n".join((json.dumps(row, ensure_ascii=False), json.dumps(row, ensure_ascii=False))) + "\n",
            encoding="utf-8",
        )
        chunks = self._chunks_path(workspace)
        chunks.write_bytes(b"[" * 2048 + b"]" * 2048 + b"\n")

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertIn("examples 存在重复语义 ID", joined)
        self.assertIn("资料索引 JSONL 第 1 行", joined)

    def test_chunk_index_is_streamed_bounded_and_does_not_use_path_whole_file_reads(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        original_read_text = Path.read_text
        original_read_bytes = Path.read_bytes

        def protected_read_text(path, *args, **kwargs):
            if path == chunks:
                raise AssertionError("chunks.jsonl cannot use read_text")
            return original_read_text(path, *args, **kwargs)

        def protected_read_bytes(path, *args, **kwargs):
            if path == chunks:
                raise AssertionError("chunks.jsonl cannot use read_bytes")
            return original_read_bytes(path, *args, **kwargs)

        with (
            mock.patch.object(Path, "read_text", protected_read_text),
            mock.patch.object(Path, "read_bytes", protected_read_bytes),
        ):
            self.assertFalse(evaluate_workspace(workspace).errors)

        chunks.write_bytes(b"{" + b" " * (MAX_JSONL_LINE_BYTES + 1) + b"\n")
        report = evaluate_workspace(workspace)
        self.assertIn("第 1 行超过", "\n".join(report.errors))

    def test_index_terms_have_a_deterministic_hard_cap(self):
        terms = evaluation._bounded_index_terms(
            [f"keyword-{index:03d}" for index in range(MAX_INDEX_TERMS_PER_CHUNK + 10)],
            [f"ngram-{index:03d}" for index in range(MAX_INDEX_TERMS_PER_CHUNK + 10)],
        )

        self.assertEqual(MAX_INDEX_TERMS_PER_CHUNK, len(terms))
        self.assertEqual("keyword-000", terms[0])
        self.assertEqual(f"keyword-{MAX_INDEX_TERMS_PER_CHUNK - 1:03d}", terms[-1])

    def test_all_evidence_entry_points_and_corpus_id_are_validated(self):
        workspace = self._complete_workspace()
        missing = "missing-chunk"
        identity = self._asset(workspace, "identity.json")
        identity.write_text(json.dumps({"relationships": [{"period": "1926", "evidence": [missing]}]}), encoding="utf-8")
        voice = self._asset(workspace, "voice.json")
        voice.write_text(json.dumps({"evidence": [missing]}), encoding="utf-8")
        for name, field in (("worldview.jsonl", "evidence"), ("episodes.jsonl", "evidence"), ("examples.jsonl", "evidence"), ("eval.jsonl", "expectedEvidence")):
            path = self._asset(workspace, name)
            rows = [json.loads(line) for line in path.read_text("utf-8").splitlines()]
            rows[0][field] = [missing]
            if name == "eval.jsonl":
                rows[0]["corpusId"] = 7
            path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        for asset in ("identity.relationships", "voice", "worldview", "episodes", "examples", "eval"):
            self.assertIn(asset, joined)
        self.assertIn("corpusId 必须是非空字符串", joined)

    def test_metrics_are_stratified_and_stable_across_chunk_row_order_and_processes(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        second = dict(rows[0])
        second.update({"id": "chunk-secondary", "authorship": "secondary", "period": "1927"})
        rows.append(second)
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        eval_path = self._asset(workspace, "eval.jsonl")
        eval_rows = [json.loads(line) for line in eval_path.read_text("utf-8").splitlines()]
        for index in range(2):
            eval_rows.append({
                "id": f"grounding-secondary-{index}", "category": "grounding", "question": "调查以后再下结论",
                "period": "1927", "expectedEvidence": ["chunk-secondary"], "corpusId": "archive-a",
            })
        eval_path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in eval_rows) + "\n", encoding="utf-8")

        first = evaluate_workspace(workspace)
        self.assertIn("1927", first.by_period)
        self.assertIn("secondary", first.by_authorship)
        self.assertIn("archive-a", first.by_corpus)
        self.assertEqual([], validate_declared_corpus_question_coverage(first, {"archive-a": "recommended"}))
        self.assertIn(
            "至少需要 2 道可归因评估题",
            "\n".join(validate_declared_corpus_question_coverage(first, {"missing": "required"})),
        )
        command = (
            "import json,sys;from pathlib import Path;"
            "from tools.agent_builder.evaluation import evaluate_workspace;"
            "print(json.dumps(evaluate_workspace(Path(sys.argv[1])).metrics(),sort_keys=True,ensure_ascii=False))"
        )
        first_process = subprocess.check_output([sys.executable, "-c", command, str(workspace)], text=True).strip()
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in reversed(rows)) + "\n", encoding="utf-8")
        second_process = subprocess.check_output([sys.executable, "-c", command, str(workspace)], text=True).strip()

        self.assertEqual(first_process, second_process)

    def test_temporary_index_is_cleaned_when_sqlite_setup_fails(self):
        workspace = self._complete_workspace()
        before = set(Path(tempfile.gettempdir()).glob(".harness-evaluation-*"))
        with mock.patch.object(evaluation.sqlite3, "connect", side_effect=sqlite3.Error("boom")):
            report = evaluate_workspace(workspace)
        after = set(Path(tempfile.gettempdir()).glob(".harness-evaluation-*"))

        self.assertIn("V2 评测无法完成", "\n".join(report.errors))
        self.assertEqual(before, after)

    def test_temporary_index_is_cleaned_after_sqlite_connection_opens(self):
        workspace = self._complete_workspace()
        before = set(Path(tempfile.gettempdir()).glob(".harness-evaluation-*"))
        with mock.patch.object(evaluation, "_create_index", side_effect=sqlite3.Error("boom")):
            report = evaluate_workspace(workspace)
        after = set(Path(tempfile.gettempdir()).glob(".harness-evaluation-*"))

        self.assertIn("V2 评测无法完成", "\n".join(report.errors))
        self.assertEqual(before, after)

    def _complete_workspace(self) -> Path:
        catalog = self.root / "catalog.json"
        catalog.write_text(json.dumps({"sources": [{
            "sourceId": "source-research", "fileName": "source.md", "title": "调查研究",
            "genre": "speech", "authorship": "direct", "period": "1926",
        }]}, ensure_ascii=False), encoding="utf-8")
        workspace = prepare_workspace_v2(
            [self.source], self.root / "workspace", agent_id="person.researcher", name="资料研究者", version=2,
            source_catalog_path=catalog,
        )
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        self.assertEqual(1, len(rows))
        chunk_id = rows[0]["id"]
        self._asset(workspace, "identity.json").write_text(json.dumps({
            "selfNames": ["我"], "timeHorizon": "1926", "roles": ["组织者"], "relationships": [],
        }, ensure_ascii=False), encoding="utf-8")
        self._asset(workspace, "voice.json").write_text(json.dumps({
            "defaultForm": "先判断", "sentenceRhythm": ["短句"], "rhetoricalMoves": ["对比"],
            "preferredTerms": ["调查"], "avoidPatterns": [], "evidence": [chunk_id],
        }, ensure_ascii=False), encoding="utf-8")
        self._asset(workspace, "worldview.jsonl").write_text(json.dumps({
            "id": "stance-001", "topic": "调查", "statement": "调查以后再下结论", "period": "1926", "evidence": [chunk_id],
        }, ensure_ascii=False) + "\n", encoding="utf-8")
        self._asset(workspace, "episodes.jsonl").write_text(json.dumps({
            "id": "episode-001", "period": "1926", "location": "湖南", "participants": ["群众"],
            "summary": "我在调查中形成判断。", "meaning": "调查先于结论。", "evidence": [chunk_id],
        }, ensure_ascii=False) + "\n", encoding="utf-8")
        self._asset(workspace, "examples.jsonl").write_text(json.dumps({
            "id": "example-001", "intent": "方法", "user": "如何判断", "assistant": "先调查。",
            "styleTags": ["判断优先"], "generationType": "synthesized", "evidence": [chunk_id],
        }, ensure_ascii=False) + "\n", encoding="utf-8")
        categories = tuple(MINIMUM_EVAL_COUNTS.items())
        rows = []
        for category, total in categories:
            for index in range(total):
                rows.append({
                    "id": f"{category}-{index:03d}", "category": category,
                    "question": "调查以后再下结论", "period": "1926",
                    "expectedEvidence": [chunk_id], "corpusId": "unassigned",
                })
        self._asset(workspace, "eval.jsonl").write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8"
        )
        return workspace

    def _asset(self, workspace: Path, name: str) -> Path:
        return workspace / "agent" / name

    def _chunks_path(self, workspace: Path) -> Path:
        return workspace / "corpora" / "index" / "chunks.jsonl"


if __name__ == "__main__":
    unittest.main()
