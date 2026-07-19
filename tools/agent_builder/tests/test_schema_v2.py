import json
import tempfile
import unittest
from pathlib import Path

from tools.agent_builder.builder import (
    BuildError,
    load_workspace_v2,
    prepare_workspace_v2,
    validate_workspace_v2,
)
from tools.agent_builder.cli import main
from tools.agent_builder.schema_v2 import Authorship, SourceGenre


class WorkspaceV2Test(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.source = self.root / "source.md"
        self.source.write_text(
            "# 调查研究\n\n没有调查，没有发言权。\n\n# 实践\n\n认识来源于实践。",
            encoding="utf-8",
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_prepare_v2_creates_exact_runtime_assets(self):
        workspace = self._prepare()

        manifest = json.loads((workspace / "workspace.json").read_text("utf-8"))

        self.assertEqual(2, manifest["schemaVersion"])
        self.assertEqual(
            [
                "concepts.json",
                "episodes.jsonl",
                "eval.jsonl",
                "examples.jsonl",
                "identity.json",
                "openers.json",
                "persona.md",
                "voice.json",
                "worldview.jsonl",
            ],
            sorted(path.name for path in (workspace / "agent").iterdir()),
        )
        self.assertEqual(
            {"relationships": [], "roles": [], "selfNames": [], "timeHorizon": ""},
            json.loads((workspace / "agent" / "identity.json").read_text("utf-8")),
        )
        self.assertEqual(
            {
                "avoidPatterns": [],
                "defaultForm": "",
                "evidence": [],
                "preferredTerms": [],
                "rhetoricalMoves": [],
                "sentenceRhythm": [],
            },
            json.loads((workspace / "agent" / "voice.json").read_text("utf-8")),
        )

    def test_prepare_without_catalog_is_deterministic_and_blocks_publish(self):
        first = self._prepare("first")
        second = self._prepare("second")

        self.assertEqual(
            (first / "workspace.json").read_bytes(),
            (second / "workspace.json").read_bytes(),
        )
        self.assertEqual(
            (first / "source-catalog.json").read_bytes(),
            (second / "source-catalog.json").read_bytes(),
        )
        source = load_workspace_v2(first).sources[0]
        self.assertEqual(SourceGenre.UNKNOWN, source.genre)
        self.assertEqual(Authorship.UNKNOWN, source.authorship)
        self.assertEqual("unknown", source.period)

        report = validate_workspace_v2(first)

        self.assertFalse(report.publishable)
        self.assertIn("来源元数据仍有未确认项", report.errors)

    def test_complete_catalog_round_trips_with_camel_case_json(self):
        catalog = self.root / "catalog.json"
        catalog.write_text(
            json.dumps(
                {
                    "sources": [
                        {
                            "sourceId": "source-research",
                            "fileName": "source.md",
                            "title": "调查研究",
                            "genre": "speech",
                            "authorship": "direct",
                            "period": "1926",
                        }
                    ]
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )

        workspace = prepare_workspace_v2(
            [self.source],
            self.root / "workspace",
            agent_id="person.researcher",
            name="资料研究者",
            version=2,
            source_catalog_path=catalog,
        )
        manifest = json.loads((workspace / "workspace.json").read_text("utf-8"))
        source = manifest["sources"][0]
        loaded = load_workspace_v2(workspace)

        self.assertEqual(
            {"agent", "assets", "schemaVersion", "sources"},
            set(manifest),
        )
        self.assertIn("sourceId", source)
        self.assertIn("fileName", source)
        self.assertIn("storedName", source)
        self.assertIn("sourceHash", source)
        self.assertIn("rawSizeBytes", source)
        self.assertIn("extractedChars", source)
        self.assertNotIn("source_id", source)
        self.assertEqual("person.researcher", loaded.agent_id)
        self.assertEqual(SourceGenre.SPEECH, loaded.sources[0].genre)
        self.assertEqual(Authorship.DIRECT, loaded.sources[0].authorship)
        stored_source = workspace / "sources" / loaded.sources[0].stored_name
        self.assertEqual(self.source.read_bytes(), stored_source.read_bytes())
        self.assertEqual(len(self.source.read_bytes()), loaded.sources[0].raw_size_bytes)
        self.assertGreater(loaded.sources[0].extracted_chars, 0)

    def test_load_rejects_absolute_and_traversal_asset_or_source_paths(self):
        workspace = self._prepare()
        manifest_path = workspace / "workspace.json"
        base_manifest = json.loads(manifest_path.read_text("utf-8"))

        for value in ("/absolute.md", "../escape.md", "agent/../escape.md", "..\\escape.md", "agent\\..\\escape.md"):
            with self.subTest(value=value):
                manifest = json.loads(json.dumps(base_manifest))
                manifest["assets"]["persona"] = value
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(BuildError, "不安全"):
                    load_workspace_v2(workspace)

        manifest = json.loads(json.dumps(base_manifest))
        manifest["sources"][0]["storedName"] = "sources/../escape.txt"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "不安全"):
            load_workspace_v2(workspace)

    def test_load_rejects_duplicate_source_ids_and_stored_names(self):
        workspace = self._prepare()
        manifest_path = workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        duplicate = dict(manifest["sources"][0])
        duplicate["fileName"] = "other.md"
        duplicate["storedName"] = "other.md"
        manifest["sources"].append(duplicate)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        with self.assertRaisesRegex(BuildError, "重复的 sourceId"):
            load_workspace_v2(workspace)

        duplicate["sourceId"] = "source-other"
        duplicate["storedName"] = manifest["sources"][0]["storedName"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "重复的 storedName"):
            load_workspace_v2(workspace)

    def test_load_rejects_wrong_schema_version_and_malformed_enums(self):
        workspace = self._prepare()
        manifest_path = workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        for version in (1, "2", 2.0):
            with self.subTest(version=version):
                manifest["schemaVersion"] = version
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(BuildError, "schemaVersion"):
                    load_workspace_v2(workspace)

        manifest["schemaVersion"] = 2
        manifest["sources"][0]["genre"] = "fiction"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "genre"):
            load_workspace_v2(workspace)

    def test_cli_prepare_v2_writes_source_catalog_for_one_batch_clarification(self):
        output = self.root / "cli-workspace"

        exit_code = main(
            [
                "prepare-v2",
                "--agent-id",
                "person.researcher",
                "--name",
                "资料研究者",
                "--version",
                "2",
                "--output",
                str(output),
                str(self.source),
            ]
        )

        self.assertEqual(0, exit_code)
        catalog = json.loads((output / "source-catalog.json").read_text("utf-8"))
        self.assertEqual(2, catalog["schemaVersion"])
        self.assertEqual("unknown", catalog["sources"][0]["genre"])

    def _prepare(self, name: str = "workspace") -> Path:
        return prepare_workspace_v2(
            [self.source],
            self.root / name,
            agent_id="person.researcher",
            name="资料研究者",
            version=2,
        )


if __name__ == "__main__":
    unittest.main()
