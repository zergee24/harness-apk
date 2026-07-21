import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.cli import main
from tools.wiki_builder.history.enrichment_jobs import (
    JOBS_DIRECTORY_NAME,
    create_jobs,
    merge_jobs,
    validate_job,
)
from tools.wiki_builder.history.history_profile import (
    MAX_SOURCE_CHARS,
    PROFILE_ID,
    PROMPT_VERSION,
)
from tools.wiki_builder.history.tests.helpers import build_history_workspace
from tools.wiki_builder.models import BuildError
from tools.wiki_builder.workspace import ENRICHMENT_FILE_NAMES


class EnrichmentJobsTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.workspace = build_history_workspace(self.root / "workspace")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_job_ids_are_stable_bounded_and_change_with_source_hash(self):
        first = create_jobs(self.workspace, profile=PROFILE_ID)
        second = create_jobs(self.workspace, profile=PROFILE_ID)
        clone = build_history_workspace(self.root / "clone")
        clone_plan = create_jobs(clone, profile=PROFILE_ID)
        changed = build_history_workspace(
            self.root / "changed",
            leaf_texts=(
                ("前403年，司马光记载甲事已改。",),
                ("赵氏在晋阳记载乙事。",),
            ),
        )
        changed_plan = create_jobs(changed, profile=PROFILE_ID)

        self.assertEqual(first.job_ids, second.job_ids)
        self.assertEqual(first.job_ids, clone_plan.job_ids)
        self.assertNotEqual(first.job_ids, changed_plan.job_ids)

        long_workspace = build_history_workspace(
            self.root / "long",
            leaf_texts=(tuple(f"{index}" + "史" * 1998 for index in range(10)),),
        )
        long_plan = create_jobs(long_workspace, profile=PROFILE_ID)
        seen_chunks = []
        for job_id in long_plan.job_ids:
            job = self._input(long_workspace, job_id)
            self.assertLessEqual(job["sourceChars"], MAX_SOURCE_CHARS)
            seen_chunks.extend(chunk["chunkId"] for chunk in job["chunks"])
        with sqlite3.connect(long_workspace / "content.sqlite") as database:
            expected_chunks = [
                row[0]
                for row in database.execute(
                    "SELECT chunk_id FROM chunks ORDER BY chunk_id"
                )
            ]
        self.assertEqual(sorted(expected_chunks), sorted(seen_chunks))
        self.assertEqual(len(seen_chunks), len(set(seen_chunks)))

    def test_valid_output_is_reused_and_only_incomplete_jobs_remain_pending(self):
        plan = create_jobs(self.workspace, profile=PROFILE_ID)
        completed = plan.job_ids[0]
        self._write_output(completed, self._valid_output(completed))
        validation = validate_job(self.workspace, completed)
        output_before = self._output_path(completed).read_bytes()

        resumed = create_jobs(self.workspace, profile=PROFILE_ID)

        self.assertEqual(completed, validation.job_id)
        self.assertEqual((completed,), resumed.valid_job_ids)
        self.assertEqual(tuple(plan.job_ids[1:]), resumed.pending_job_ids)
        self.assertEqual(output_before, self._output_path(completed).read_bytes())

    def test_job_validation_rejects_malformed_ungrounded_and_hard_low_confidence(self):
        plan = create_jobs(self.workspace, profile=PROFILE_ID)
        job_id = plan.job_ids[0]
        self._output_path(job_id).parent.mkdir(parents=True, exist_ok=True)
        self._output_path(job_id).write_bytes(b'{"broken":true}')
        with self.assertRaisesRegex(BuildError, "JSONL|换行|output"):
            validate_job(self.workspace, job_id)

        output = self._valid_output(job_id)
        output["sectionSummary"]["evidence"] = ["outside-job"]
        self._write_output(job_id, output)
        with self.assertRaisesRegex(BuildError, "evidence|job.*chunk|越界"):
            validate_job(self.workspace, job_id)

        output = self._valid_output(job_id, concept_key="cn-history-v1:person:sima-guang")
        output["links"] = [
            {
                "sourceConceptKey": "cn-history-v1:person:sima-guang",
                "targetNamespace": "cn-history-v1",
                "targetConceptKey": "cn-history-v1:place:jin-yang",
                "kind": "associated-with",
                "confidence": 0.4,
                "routingMode": "hard-filter",
                "extractorVersion": PROMPT_VERSION,
                "evidence": [self._input(self.workspace, job_id)["chunks"][0]["chunkId"]],
            }
        ]
        self._write_output(job_id, output)
        with self.assertRaisesRegex(BuildError, "低置信|weak|hard"):
            validate_job(self.workspace, job_id)

    def test_cross_job_conflict_rolls_back_then_valid_merge_is_transactional(self):
        plan = create_jobs(self.workspace, profile=PROFILE_ID)
        for index, job_id in enumerate(plan.job_ids):
            key = f"cn-history-v1:person:person-{index}"
            output = self._valid_output(job_id, concept_key=key, alias="同名")
            self._write_output(job_id, output)
            validate_job(self.workspace, job_id)
        enrichment_before = {
            name: (self.workspace / "enrichment" / name).read_bytes()
            for name in ENRICHMENT_FILE_NAMES
        }
        database_before = hashlib.sha256(
            (self.workspace / "content.sqlite").read_bytes()
        ).hexdigest()

        with self.assertRaisesRegex(BuildError, "别名|alias|冲突"):
            merge_jobs(self.workspace)

        self.assertEqual(
            enrichment_before,
            {
                name: (self.workspace / "enrichment" / name).read_bytes()
                for name in ENRICHMENT_FILE_NAMES
            },
        )
        self.assertEqual(
            database_before,
            hashlib.sha256((self.workspace / "content.sqlite").read_bytes()).hexdigest(),
        )

        for job_id in plan.job_ids:
            self._write_output(job_id, self._valid_output(job_id))
            validate_job(self.workspace, job_id)
        stats = merge_jobs(self.workspace)
        self.assertEqual(3, stats.summaries)
        with sqlite3.connect(self.workspace / "content.sqlite") as database:
            self.assertEqual(3, database.execute("SELECT COUNT(*) FROM summaries").fetchone()[0])

    def test_weak_link_keeps_routing_mode_and_extractor_version(self):
        plan = create_jobs(self.workspace, profile=PROFILE_ID)
        source_key = "cn-history-v1:person:sima-guang"
        for index, job_id in enumerate(plan.job_ids):
            output = self._valid_output(
                job_id,
                concept_key=source_key if index == 0 else None,
            )
            if index == 0:
                output["links"] = [
                    {
                        "sourceConceptKey": source_key,
                        "targetNamespace": "cn-history-v1",
                        "targetConceptKey": "cn-history-v1:place:jin-yang",
                        "kind": "associated-with",
                        "confidence": 0.4,
                        "routingMode": "weak-only",
                        "extractorVersion": PROMPT_VERSION,
                        "evidence": [
                            self._input(self.workspace, job_id)["chunks"][0]["chunkId"]
                        ],
                    }
                ]
            self._write_output(job_id, output)
            validate_job(self.workspace, job_id)

        stats = merge_jobs(self.workspace)

        self.assertEqual(1, stats.links)
        link = json.loads(
            (self.workspace / "enrichment" / "links.jsonl")
            .read_text(encoding="utf-8")
            .splitlines()[0]
        )
        self.assertEqual("weak-only", link["metadata"]["routingMode"])
        self.assertEqual(PROMPT_VERSION, link["metadata"]["extractorVersion"])
        with sqlite3.connect(self.workspace / "content.sqlite") as database:
            metadata = json.loads(
                database.execute("SELECT metadata_json FROM links").fetchone()[0]
            )
        self.assertEqual(link["metadata"], metadata)

    def test_cli_exposes_create_validate_and_merge_state_machine(self):
        self.assertEqual(
            0,
            main(
                [
                    "history",
                    "create-jobs",
                    str(self.workspace),
                    "--profile",
                    PROFILE_ID,
                ]
            ),
        )
        plan = create_jobs(self.workspace, profile=PROFILE_ID)
        for job_id in plan.job_ids:
            self._write_output(job_id, self._valid_output(job_id))
            self.assertEqual(
                0,
                main(["history", "validate-job", str(self.workspace), job_id]),
            )
        self.assertEqual(
            0,
            main(["history", "merge-jobs", str(self.workspace)]),
        )

    def _valid_output(
        self,
        job_id: str,
        *,
        concept_key: str | None = None,
        alias: str | None = None,
    ) -> dict[str, object]:
        job = self._input(self.workspace, job_id)
        chunk = job["chunks"][0]
        concepts = []
        if concept_key is not None:
            concepts.append(
                {
                    "conceptKey": concept_key,
                    "kind": "person",
                    "canonicalText": concept_key.rsplit(":", 1)[-1],
                    "confidence": 0.96,
                    "reviewState": "auto-high-confidence",
                    "evidence": [chunk["chunkId"]],
                    "aliases": (
                        [
                            {
                                "text": alias,
                                "confidence": 0.95,
                                "evidence": [chunk["chunkId"]],
                            }
                        ]
                        if alias is not None
                        else []
                    ),
                    "mentions": [],
                }
            )
        return {
            "type": "hwiki-history-enrichment-output",
            "schemaVersion": 1,
            "jobId": job_id,
            "inputHash": job["inputHash"],
            "profile": PROFILE_ID,
            "promptVersion": PROMPT_VERSION,
            "sectionSummary": {
                "text": f"{job['scope']['sectionTitle']}摘要。",
                "evidence": [chunk["chunkId"]],
            },
            "concepts": concepts,
            "annotations": [],
            "links": [],
        }

    def _input(self, workspace: Path, job_id: str) -> dict[str, object]:
        return json.loads(
            (
                workspace
                / JOBS_DIRECTORY_NAME
                / "inputs"
                / f"{job_id}.json"
            ).read_bytes()
        )

    def _output_path(self, job_id: str) -> Path:
        return (
            self.workspace
            / JOBS_DIRECTORY_NAME
            / "outputs"
            / f"{job_id}.jsonl"
        )

    def _write_output(self, job_id: str, output: dict[str, object]) -> None:
        path = self._output_path(job_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(canonical_json_bytes(output) + b"\n")


if __name__ == "__main__":
    unittest.main()
