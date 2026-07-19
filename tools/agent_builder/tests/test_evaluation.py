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
        self.companion_source = self.root / "companion.md"
        self.companion_source.write_text(
            "# 组织\n\n调查要从组织实际出发，不能借旧结论代替事实。",
            encoding="utf-8",
        )

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

    def test_fts_retrieves_a_tail_term_without_truncating_chunk_terms(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        rows[0]["keywords"] = [f"leading{index:03d}" for index in range(400)] + ["tailgroundingterm"]
        rows[0]["ngrams"] = []
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")

        with tempfile.TemporaryDirectory() as directory:
            connection = sqlite3.connect(Path(directory) / "evaluation.sqlite")
            connection.row_factory = sqlite3.Row
            try:
                evaluation._create_index(connection)
                errors: list[str] = []
                manifest = evaluation._load_workspace(workspace, errors)
                self.assertIsNotNone(manifest)
                source_hashes = {source.source_id: source.source_hash for source in manifest.sources}
                evaluation._stream_node_index(workspace, connection, source_hashes, errors)
                evaluation._stream_chunk_index(workspace, connection, errors, source_hashes)
                self.assertEqual([], errors)
                self.assertIn(rows[0]["id"], evaluation._SqliteChunks(connection).retrieve("tailgroundingterm"))
            finally:
                connection.close()

    def test_diversity_and_global_reject_one_attributable_expected_chunk(self):
        workspace = self._complete_workspace()
        eval_path = self._asset(workspace, "eval.jsonl")
        rows = [json.loads(line) for line in eval_path.read_text("utf-8").splitlines()]
        for row in rows:
            if row["category"] in {"diversity", "global"}:
                row["expectedEvidence"] = [row["expectedEvidence"][0]]
        eval_path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertFalse(report.publishable)
        self.assertIn("diversity 至少需要 2 条可归因 expectedEvidence", joined)
        self.assertIn("global 至少需要 2 条可归因 expectedEvidence", joined)

    def test_stance_temporal_and_period_assets_reject_mixed_period_evidence(self):
        workspace = self._complete_workspace()
        chunk_id, companion_id = self._source_chunk_ids(workspace)
        eval_path = self._asset(workspace, "eval.jsonl")
        rows = [json.loads(line) for line in eval_path.read_text("utf-8").splitlines()]
        for row in rows:
            if row["category"] in {"stance", "temporal"}:
                row["expectedEvidence"] = [chunk_id, companion_id]
        eval_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8"
        )
        worldview_path = self._asset(workspace, "worldview.jsonl")
        worldview = json.loads(worldview_path.read_text("utf-8"))
        worldview["evidence"] = [chunk_id, companion_id]
        worldview_path.write_text(json.dumps(worldview, ensure_ascii=False) + "\n", encoding="utf-8")

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertFalse(report.publishable)
        self.assertGreaterEqual(joined.count("period 与 evidence 时期不兼容"), 3)
        evaluation_report = evaluate_workspace(workspace)
        self.assertEqual(0, evaluation_report.category_metrics["stance"].passed)
        self.assertEqual(0, evaluation_report.category_metrics["temporal"].passed)

    def test_voice_evaluation_rejects_any_secondary_expected_evidence(self):
        workspace = self._complete_workspace()
        chunk_id, companion_id = self._source_chunk_ids(workspace)
        eval_path = self._asset(workspace, "eval.jsonl")
        rows = [json.loads(line) for line in eval_path.read_text("utf-8").splitlines()]
        for row in rows:
            if row["category"] == "voice":
                row["expectedEvidence"] = [chunk_id, companion_id]
        eval_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8"
        )

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertFalse(report.publishable)
        self.assertIn("voice expectedEvidence 只能引用 direct 或 edited_direct", joined)
        self.assertEqual(0, evaluate_workspace(workspace).category_metrics["voice"].passed)

    def test_period_and_voice_categories_reject_non_expected_unsafe_retrieval(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        companion = next(row for row in rows if row["sourceId"] == "source-companion")
        companion["keywords"].append("以后")
        chunks.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8",
        )

        report = evaluate_workspace(workspace)

        self.assertEqual(MINIMUM_EVAL_COUNTS["grounding"], report.category_metrics["grounding"].passed)
        self.assertEqual(0, report.category_metrics["stance"].passed)
        self.assertEqual(0, report.category_metrics["temporal"].passed)
        self.assertEqual(0, report.category_metrics["voice"].passed)

    def test_global_rejects_two_routes_from_one_source(self):
        workspace = self._complete_workspace()
        chunk_id, _ = self._source_chunk_ids(workspace)
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        primary = next(row for row in rows if row["id"] == chunk_id)
        nodes = self._nodes_path(workspace)
        node_rows = [json.loads(line) for line in nodes.read_text("utf-8").splitlines()]
        source_root = next(
            row for row in node_rows if row["sourceId"] == "source-research" and row["kind"] == "source"
        )
        alternate_node = {
            "id": "node-same-source-alternate-route",
            "kind": "section",
            "parentId": source_root["id"],
            "path": [source_root["title"], "旁证"],
            "sourceId": "source-research",
            "summary": "调查的另一条路径",
            "title": "旁证",
        }
        node_rows.append(alternate_node)
        nodes.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in node_rows) + "\n", encoding="utf-8"
        )
        clone = dict(primary)
        clone.update(
            {
                "id": "chunk-same-source-alternate-route",
                "duplicateGroup": "alternate-route",
                "parentIds": [source_root["id"], alternate_node["id"]],
            }
        )
        rows.append(clone)
        chunks.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8"
        )
        eval_path = self._asset(workspace, "eval.jsonl")
        eval_rows = [json.loads(line) for line in eval_path.read_text("utf-8").splitlines()]
        for row in eval_rows:
            if row["category"] == "global":
                row["expectedEvidence"] = [chunk_id, clone["id"]]
        eval_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in eval_rows) + "\n", encoding="utf-8"
        )

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertFalse(report.publishable)
        self.assertIn("global expectedEvidence 必须来自至少 2 个 sourceId", joined)
        self.assertEqual(0, evaluate_workspace(workspace).category_metrics["global"].passed)

    def test_rejects_invalid_node_graphs_and_chunk_parent_chains(self):
        workspace = self._complete_workspace("missing-node-parent")
        nodes = self._nodes_path(workspace)
        rows = [json.loads(line) for line in nodes.read_text("utf-8").splitlines()]
        section = next(row for row in rows if row["sourceId"] == "source-research" and row["kind"] == "section")
        section["parentId"] = "missing-node-parent"
        nodes.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        self.assertIn("parentId 引用了不存在的 node ID", "\n".join(evaluate_workspace(workspace).errors))

        workspace = self._complete_workspace("cross-source-node-parent")
        nodes = self._nodes_path(workspace)
        rows = [json.loads(line) for line in nodes.read_text("utf-8").splitlines()]
        section = next(row for row in rows if row["sourceId"] == "source-research" and row["kind"] == "section")
        foreign_root = next(row for row in rows if row["sourceId"] == "source-companion" and row["kind"] == "source")
        section["parentId"] = foreign_root["id"]
        nodes.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        self.assertIn("parentId 与 node sourceId 不一致", "\n".join(evaluate_workspace(workspace).errors))

        workspace = self._complete_workspace("cyclic-node-parent")
        nodes = self._nodes_path(workspace)
        rows = [json.loads(line) for line in nodes.read_text("utf-8").splitlines()]
        section = next(row for row in rows if row["sourceId"] == "source-research" and row["kind"] == "section")
        section["parentId"] = section["id"]
        nodes.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        self.assertIn("存在 parentId 环", "\n".join(evaluate_workspace(workspace).errors))

        workspace = self._complete_workspace("source-root-parent")
        nodes = self._nodes_path(workspace)
        rows = [json.loads(line) for line in nodes.read_text("utf-8").splitlines()]
        source_root = next(
            row for row in rows if row["sourceId"] == "source-research" and row["kind"] == "source"
        )
        source_root["parentId"] = source_root["id"]
        nodes.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        self.assertIn("source root，parentId 必须为 null", "\n".join(evaluate_workspace(workspace).errors))

        workspace = self._complete_workspace("unordered-chunk-chain")
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        rows[0]["parentIds"] = list(reversed(rows[0]["parentIds"]))
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        self.assertIn("parentIds 必须从 source root 逐级连接", "\n".join(evaluate_workspace(workspace).errors))

        workspace = self._complete_workspace("jumped-chunk-chain")
        nodes = self._nodes_path(workspace)
        node_rows = [json.loads(line) for line in nodes.read_text("utf-8").splitlines()]
        source_root = next(
            row for row in node_rows if row["sourceId"] == "source-research" and row["kind"] == "source"
        )
        section = next(row for row in node_rows if row["sourceId"] == "source-research" and row["kind"] == "section")
        node_rows.append(
            {
                "id": "node-jumped-chain-leaf",
                "kind": "section",
                "parentId": section["id"],
                "path": [source_root["title"], section["title"], "叶节点"],
                "sourceId": "source-research",
                "summary": "跳级测试叶节点",
                "title": "叶节点",
            }
        )
        nodes.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in node_rows) + "\n", encoding="utf-8"
        )
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        rows[0]["parentIds"] = [source_root["id"], "node-jumped-chain-leaf"]
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        self.assertIn("parentIds 必须从 source root 逐级连接", "\n".join(evaluate_workspace(workspace).errors))

    def test_rejects_node_with_invalid_declared_shape(self):
        workspace = self._complete_workspace("invalid-node-shape")
        nodes = self._nodes_path(workspace)
        rows = [json.loads(line) for line in nodes.read_text("utf-8").splitlines()]
        rows[0]["title"] = ""
        rows[0]["summary"] = []
        rows[0]["path"] = []
        nodes.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")

        report = evaluate_workspace(workspace)

        joined = "\n".join(report.errors)
        self.assertIn("缺少或包含无效 title", joined)
        self.assertIn("缺少或包含无效 summary", joined)
        self.assertIn("path 必须是非空字符串数组", joined)

    def test_runtime_asset_shapes_and_coverage_summaries_are_explicit(self):
        workspace = self._complete_workspace()
        identity = json.loads(self._asset(workspace, "identity.json").read_text("utf-8"))
        identity["selfNames"] = "我"
        identity["relationships"] = [{"subject": [], "relation": 17, "period": "1926", "evidence": []}]
        self._asset(workspace, "identity.json").write_text(json.dumps(identity, ensure_ascii=False), encoding="utf-8")
        voice = json.loads(self._asset(workspace, "voice.json").read_text("utf-8"))
        voice["defaultForm"] = []
        self._asset(workspace, "voice.json").write_text(json.dumps(voice, ensure_ascii=False), encoding="utf-8")
        worldview = json.loads(self._asset(workspace, "worldview.jsonl").read_text("utf-8"))
        worldview.update({"topic": [], "conditions": "bad", "aliases": [7], "confidence": True})
        self._asset(workspace, "worldview.jsonl").write_text(json.dumps(worldview, ensure_ascii=False) + "\n", encoding="utf-8")
        episode = json.loads(self._asset(workspace, "episodes.jsonl").read_text("utf-8"))
        episode.update({"location": [], "participants": "群众"})
        self._asset(workspace, "episodes.jsonl").write_text(json.dumps(episode, ensure_ascii=False) + "\n", encoding="utf-8")
        example = json.loads(self._asset(workspace, "examples.jsonl").read_text("utf-8"))
        example.update({"intent": [], "user": 1, "styleTags": "bad"})
        self._asset(workspace, "examples.jsonl").write_text(json.dumps(example, ensure_ascii=False) + "\n", encoding="utf-8")
        self._asset(workspace, "concepts.json").write_text(
            json.dumps({"concepts": [{"id": [], "name": 1, "aliases": "bad", "keywords": [7], "evidence": []}]}, ensure_ascii=False),
            encoding="utf-8",
        )
        self._asset(workspace, "openers.json").write_text(
            json.dumps({"default": [], "alternatives": ["一", "二", "三"]}, ensure_ascii=False), encoding="utf-8"
        )

        report = validate_workspace_v2(workspace)

        joined = "\n".join(report.errors)
        self.assertFalse(report.publishable)
        for label in (
            "identity.selfNames",
            "identity.relationships[1].subject",
            "voice.defaultForm",
            "worldview[1].topic",
            "worldview[1].confidence",
            "episodes[1].participants",
            "examples[1].styleTags",
            "concepts[1].keywords",
            "openers.alternatives",
        ):
            self.assertIn(label, joined)

        clean = self._complete_workspace("clean")
        metrics = evaluate_workspace(clean).metrics()
        self.assertIn("assetItems", metrics["factualCoverage"])
        self.assertIn("evidence", metrics["factualCoverage"])
        self.assertIn("periods", metrics["stanceCoverage"])

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
        secondary_id = next(row["id"] for row in rows if row["sourceId"] == "source-companion")
        eval_path = self._asset(workspace, "eval.jsonl")
        eval_rows = [json.loads(line) for line in eval_path.read_text("utf-8").splitlines()]
        for index in range(2):
            eval_rows.append({
                "id": f"grounding-secondary-{index}", "category": "grounding", "question": "调查",
                "period": "1927", "expectedEvidence": [secondary_id], "corpusId": "archive-a",
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

    def test_rejects_chunk_source_id_or_hash_outside_workspace_manifest(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]

        rows[0]["sourceId"] = "source-ghost"
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        ghost_report = evaluate_workspace(workspace)
        self.assertIn("sourceId 不在 workspace.json sources 中：source-ghost", "\n".join(ghost_report.errors))

        workspace = self._complete_workspace("hash-mismatch")
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        rows[0]["sourceHash"] = "incorrect-source-hash"
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        hash_report = evaluate_workspace(workspace)
        self.assertIn("sourceHash 与 workspace.json sources 不一致", "\n".join(hash_report.errors))

    def test_rejects_missing_chunk_parent_and_duplicate_node_id(self):
        workspace = self._complete_workspace()
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        rows[0]["parentIds"] = ["node-does-not-exist"]
        chunks.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        missing_parent = evaluate_workspace(workspace)
        self.assertIn("parentIds 引用了不存在的 node ID：node-does-not-exist", "\n".join(missing_parent.errors))

        workspace = self._complete_workspace("duplicate-node")
        nodes = self._nodes_path(workspace)
        first = nodes.read_bytes().splitlines()[0]
        nodes.write_bytes(first + b"\n" + first + b"\n")
        duplicate_node = evaluate_workspace(workspace)
        self.assertIn("第 2 行存在重复 node ID", "\n".join(duplicate_node.errors))

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

    def _complete_workspace(self, name: str = "workspace") -> Path:
        catalog = self.root / "catalog.json"
        catalog.write_text(json.dumps({"sources": [
            {
                "sourceId": "source-research", "fileName": "source.md", "title": "调查研究",
                "genre": "speech", "authorship": "direct", "period": "1926",
            },
            {
                "sourceId": "source-companion", "fileName": "companion.md", "title": "组织实际",
                "genre": "secondary", "authorship": "secondary", "period": "1927",
            },
        ]}, ensure_ascii=False), encoding="utf-8")
        workspace = prepare_workspace_v2(
            [self.source, self.companion_source], self.root / name,
            agent_id="person.researcher", name="资料研究者", version=2,
            source_catalog_path=catalog,
        )
        chunks = self._chunks_path(workspace)
        rows = [json.loads(line) for line in chunks.read_text("utf-8").splitlines()]
        self.assertGreaterEqual(len(rows), 1)
        chunk_id = next(
            row["id"]
            for row in rows
            if row["sourceId"] == "source-research" and "调查" in row["keywords"]
        )
        companion_id = next(row["id"] for row in rows if row["sourceId"] == "source-companion")
        self._asset(workspace, "identity.json").write_text(json.dumps({
            "selfNames": ["我"], "timeHorizon": "1926", "roles": ["组织者"], "relationships": [],
        }, ensure_ascii=False), encoding="utf-8")
        self._asset(workspace, "voice.json").write_text(json.dumps({
            "defaultForm": "先判断", "sentenceRhythm": ["短句"], "rhetoricalMoves": ["对比"],
            "preferredTerms": ["调查"], "avoidPatterns": [], "evidence": [chunk_id],
        }, ensure_ascii=False), encoding="utf-8")
        self._asset(workspace, "worldview.jsonl").write_text(json.dumps({
            "id": "stance-001", "topic": "调查", "statement": "调查以后再下结论", "conditions": [],
            "period": "1926", "aliases": [], "confidence": 1.0, "evidence": [chunk_id],
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
                    "question": "调查" if category in {"diversity", "global"} else "以后",
                    "period": "1926",
                    "expectedEvidence": [chunk_id, companion_id]
                    if category in {"diversity", "global"}
                    else [chunk_id],
                    "corpusId": "unassigned",
                })
        self._asset(workspace, "eval.jsonl").write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8"
        )
        return workspace

    def _asset(self, workspace: Path, name: str) -> Path:
        return workspace / "agent" / name

    def _source_chunk_ids(self, workspace: Path) -> tuple[str, str]:
        rows = [json.loads(line) for line in self._chunks_path(workspace).read_text("utf-8").splitlines()]
        primary = next(row["id"] for row in rows if row["sourceId"] == "source-research")
        companion = next(row["id"] for row in rows if row["sourceId"] == "source-companion")
        return primary, companion

    def _chunks_path(self, workspace: Path) -> Path:
        return workspace / "corpora" / "index" / "chunks.jsonl"

    def _nodes_path(self, workspace: Path) -> Path:
        return workspace / "corpora" / "index" / "nodes.jsonl"


if __name__ == "__main__":
    unittest.main()
