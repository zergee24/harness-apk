import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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

    def test_prepare_v2_round_trips_macos_display_file_names(self):
        for index, file_name in enumerate(("C:portable.md", r"notes\\draft.md")):
            with self.subTest(file_name=file_name):
                source = self.root / file_name
                source.write_text("# 资料\n\n可被读取。", encoding="utf-8")

                workspace = prepare_workspace_v2(
                    [source],
                    self.root / f"workspace-{index}",
                    agent_id="person.researcher",
                    name="资料研究者",
                    version=2,
                )
                loaded = load_workspace_v2(workspace)

                self.assertEqual(file_name, loaded.sources[0].file_name)
                self.assertEqual(1, len(Path(loaded.sources[0].stored_name).parts))
                self.assertTrue((workspace / "sources" / loaded.sources[0].stored_name).is_file())

    def test_prepare_v2_round_trips_near_limit_file_name_with_fixed_length_ids(self):
        file_name = "a" * 246 + ".md"
        source = self.root / file_name
        source.write_text("# 资料\n\n可被读取。", encoding="utf-8")

        workspace = prepare_workspace_v2(
            [source],
            self.root / "long-file-name",
            agent_id="person.researcher",
            name="资料研究者",
            version=2,
        )
        loaded = load_workspace_v2(workspace)
        record = loaded.sources[0]

        self.assertEqual(file_name, record.file_name)
        self.assertEqual(40, len(record.source_id))
        self.assertRegex(record.source_id, r"^source-[0-9a-f]{16}-[0-9a-f]{16}$")
        self.assertLessEqual(len(record.stored_name.encode("utf-8")), 200)
        self.assertTrue(record.stored_name.endswith(".md"))
        self.assertTrue((workspace / "sources" / record.stored_name).is_file())

    def test_prepare_v2_preserves_exact_macos_display_names_when_reusing_catalog(self):
        file_names = (
            " leading.md",
            "C:portable.md",
            r"notes\\draft.md",
            " .md",
        )
        for index, file_name in enumerate(file_names):
            with self.subTest(file_name=file_name):
                source = self.root / file_name
                source.write_text("# 资料\n\n可被读取。", encoding="utf-8")
                initial = prepare_workspace_v2(
                    [source],
                    self.root / f"initial-{index}",
                    agent_id="person.researcher",
                    name="资料研究者",
                    version=2,
                )
                catalog_path = initial / "source-catalog.json"
                catalog = json.loads(catalog_path.read_text("utf-8"))
                catalog_row = catalog["sources"][0]
                self.assertEqual(file_name, catalog_row["fileName"])
                self.assertTrue(catalog_row["title"].strip())
                catalog_row.update(
                    {
                        "genre": "speech",
                        "authorship": "direct",
                        "period": "1926",
                    }
                )
                catalog_path.write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")

                reused = prepare_workspace_v2(
                    [source],
                    self.root / f"reused-{index}",
                    agent_id="person.researcher",
                    name="资料研究者",
                    version=2,
                    source_catalog_path=catalog_path,
                )
                loaded = load_workspace_v2(reused)

                self.assertEqual(file_name, loaded.sources[0].file_name)
                self.assertEqual(catalog_row["title"], loaded.sources[0].title)

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

        manifest = json.loads(json.dumps(base_manifest))
        manifest["sources"][0]["storedName"] = "nested/escape.txt"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "单一文件名"):
            load_workspace_v2(workspace)

    def test_load_rejects_windows_asset_and_source_paths(self):
        workspace = self._prepare()
        manifest_path = workspace / "workspace.json"
        base_manifest = json.loads(manifest_path.read_text("utf-8"))
        windows_paths = ("C:foo", "C:/foo", r"C:\\foo", r"\\\\server\\share\\foo")

        for field, value in (
            ("assets.persona", path) for path in windows_paths
        ):
            with self.subTest(field=field, value=value):
                manifest = json.loads(json.dumps(base_manifest))
                manifest["assets"]["persona"] = value
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(BuildError, "不安全"):
                    load_workspace_v2(workspace)

        for value in windows_paths:
            with self.subTest(field="sources.storedName", value=value):
                manifest = json.loads(json.dumps(base_manifest))
                manifest["sources"][0]["storedName"] = value
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(BuildError, "不安全"):
                    load_workspace_v2(workspace)

    def test_load_rejects_nul_workspace_paths_before_validation_or_cli(self):
        workspace = self._prepare()
        manifest_path = workspace / "workspace.json"
        base_manifest = json.loads(manifest_path.read_text("utf-8"))

        for field in ("assets.persona", "sources.storedName"):
            with self.subTest(field=field):
                manifest = json.loads(json.dumps(base_manifest))
                if field == "assets.persona":
                    manifest["assets"]["persona"] = "agent/bad\u0000name.md"
                else:
                    manifest["sources"][0]["storedName"] = "bad\u0000name.md"
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

                with self.assertRaisesRegex(BuildError, "不安全"):
                    load_workspace_v2(workspace)
                report = validate_workspace_v2(workspace)

                self.assertFalse(report.publishable)
                self.assertTrue(any("不安全" in error for error in report.errors))
                self.assertEqual(2, main(["validate-v2", str(workspace)]))

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

    def test_prepare_v2_rejects_non_positive_integer_versions_before_creating_output(self):
        for index, version in enumerate((True, 2.0, "2", 0, -1)):
            with self.subTest(version=version):
                output = self.root / f"invalid-version-{index}"
                with self.assertRaisesRegex(BuildError, "版本必须是正整数"):
                    prepare_workspace_v2(
                        [self.source],
                        output,
                        agent_id="person.researcher",
                        name="资料研究者",
                        version=version,
                    )
                self.assertFalse(output.exists())

        workspace = prepare_workspace_v2(
            [self.source],
            self.root / "valid-version",
            agent_id="person.researcher",
            name="资料研究者",
            version=2,
        )
        self.assertEqual(2, load_workspace_v2(workspace).version)

    def test_prepare_v2_uses_shared_agent_identifier_rule_without_output(self):
        trimmed = prepare_workspace_v2(
            [self.source],
            self.root / "trimmed-agent-id",
            agent_id=" person.researcher ",
            name="资料研究者",
            version=2,
        )
        self.assertEqual("person.researcher", load_workspace_v2(trimmed).agent_id)

        for index, value in enumerate(("../invalid", "person/researcher", "person researcher", "a")):
            with self.subTest(value=value):
                output = self.root / f"invalid-agent-api-{index}"
                with self.assertRaisesRegex(BuildError, "agent id 只能包含"):
                    prepare_workspace_v2(
                        [self.source],
                        output,
                        agent_id=value,
                        name="资料研究者",
                        version=2,
                    )
                self.assertFalse(output.exists())

        valid_max = "a" + "x" * 127
        invalid_max = "a" + "x" * 128
        self.assertEqual(
            valid_max,
            load_workspace_v2(
                prepare_workspace_v2(
                    [self.source],
                    self.root / "max-agent-id",
                    agent_id=valid_max,
                    name="资料研究者",
                    version=2,
                )
            ).agent_id,
        )
        with self.assertRaisesRegex(BuildError, "agent id 只能包含"):
            prepare_workspace_v2(
                [self.source],
                self.root / "too-long-agent-id",
                agent_id=invalid_max,
                name="资料研究者",
                version=2,
            )

        cli_output = self.root / "invalid-agent-cli"
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(
                1,
                main(
                    [
                        "prepare-v2",
                        "--agent-id",
                        "../invalid",
                        "--name",
                        "资料研究者",
                        "--version",
                        "2",
                        "--output",
                        str(cli_output),
                        str(self.source),
                    ]
                ),
            )
        self.assertEqual("", stdout.getvalue())
        self.assertFalse(cli_output.exists())

    def test_prepare_v2_and_loader_share_source_identifier_rule(self):
        catalog = self.root / "invalid-source-id-catalog.json"
        catalog.write_text(
            json.dumps(
                {
                    "sources": [
                        {
                            "sourceId": "../invalid",
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
        api_output = self.root / "invalid-source-id-api"
        with self.assertRaisesRegex(BuildError, "来源目录 sourceId 只能包含"):
            prepare_workspace_v2(
                [self.source],
                api_output,
                agent_id="person.researcher",
                name="资料研究者",
                version=2,
                source_catalog_path=catalog,
            )
        self.assertFalse(api_output.exists())

        cli_output = self.root / "invalid-source-id-cli"
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(
                1,
                main(
                    [
                        "prepare-v2",
                        "--agent-id",
                        "person.researcher",
                        "--name",
                        "资料研究者",
                        "--version",
                        "2",
                        "--output",
                        str(cli_output),
                        "--source-catalog",
                        str(catalog),
                        str(self.source),
                    ]
                ),
            )
        self.assertEqual("", stdout.getvalue())
        self.assertFalse(cli_output.exists())

        catalog_data = json.loads(catalog.read_text("utf-8"))
        catalog_data["sources"][0]["sourceId"] = " source.research "
        catalog.write_text(json.dumps(catalog_data), encoding="utf-8")
        trimmed_workspace = prepare_workspace_v2(
            [self.source],
            self.root / "trimmed-source-id",
            agent_id="person.researcher",
            name="资料研究者",
            version=2,
            source_catalog_path=catalog,
        )
        self.assertEqual("source.research", load_workspace_v2(trimmed_workspace).sources[0].source_id)

        workspace = self._prepare_publishable("invalid-loaded-identifiers")
        manifest_path = workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        for field, label in (("agent", "agent.id"), ("source", "source.sourceId")):
            with self.subTest(field=field):
                changed = json.loads(json.dumps(manifest))
                if field == "agent":
                    changed["agent"]["id"] = "../invalid"
                else:
                    changed["sources"][0]["sourceId"] = "../invalid"
                manifest_path.write_text(json.dumps(changed), encoding="utf-8")

                with self.assertRaisesRegex(BuildError, label):
                    load_workspace_v2(workspace)
                report = validate_workspace_v2(workspace)
                self.assertFalse(report.publishable)
                self.assertTrue(any(label in error for error in report.errors))
                self.assertEqual(2, main(["validate-v2", str(workspace)]))

    def test_prepare_v2_cleans_output_after_copy_failure(self):
        output = self.root / "copy-failure"

        with mock.patch("tools.agent_builder.builder.shutil.copyfile", side_effect=OSError("copy failed")):
            with self.assertRaisesRegex(OSError, "copy failed"):
                self._prepare("copy-failure")

        self.assertFalse(output.exists())
        self.assertEqual(output.resolve(), self._prepare("copy-failure"))

    def test_prepare_v2_cleans_output_after_manifest_write_failure(self):
        output = self.root / "write-failure"

        with mock.patch("tools.agent_builder.builder._write_json", side_effect=OSError("write failed")):
            with self.assertRaisesRegex(OSError, "write failed"):
                self._prepare("write-failure")

        self.assertFalse(output.exists())
        self.assertEqual(output.resolve(), self._prepare("write-failure"))

    def test_validate_v2_rejects_missing_source_and_cli_exits_nonzero(self):
        workspace = self._prepare_publishable("missing-source")
        source = workspace / "sources" / load_workspace_v2(workspace).sources[0].stored_name
        source.unlink()

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("来源文件缺失" in error for error in report.errors))
        self.assertEqual(2, main(["validate-v2", str(workspace)]))

    def test_validate_v2_rejects_source_hash_mutation(self):
        workspace = self._prepare_publishable("source-hash-mutation")
        source = workspace / "sources" / load_workspace_v2(workspace).sources[0].stored_name
        data = source.read_bytes()
        source.write_bytes(bytes([data[0] ^ 1]) + data[1:])

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("SHA-256" in error for error in report.errors))

    def test_validate_v2_rejects_source_size_mutation(self):
        workspace = self._prepare_publishable("source-size-mutation")
        source = workspace / "sources" / load_workspace_v2(workspace).sources[0].stored_name
        source.write_bytes(source.read_bytes() + b"x")

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("字节数" in error for error in report.errors))

    def test_validate_v2_rejects_symlink_source(self):
        workspace = self._prepare_publishable("symlink-source")
        stored_source = workspace / "sources" / load_workspace_v2(workspace).sources[0].stored_name
        stored_source.unlink()
        stored_source.symlink_to(self.source)

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("普通文件" in error for error in report.errors))

    def test_validate_v2_rejects_unreadable_structured_assets(self):
        json_assets = ("identity.json", "voice.json", "concepts.json", "openers.json")
        jsonl_assets = ("worldview.jsonl", "episodes.jsonl", "examples.jsonl", "eval.jsonl")

        for asset in json_assets:
            with self.subTest(asset=asset):
                workspace = self._prepare_publishable(f"invalid-json-{asset}")
                (workspace / "agent" / asset).write_text("{", encoding="utf-8")

                report = validate_workspace_v2(workspace)

                self.assertFalse(report.publishable)
                self.assertTrue(any(f"agent/{asset}" in error for error in report.errors))

        for asset in jsonl_assets:
            with self.subTest(asset=asset):
                workspace = self._prepare_publishable(f"invalid-jsonl-{asset}")
                (workspace / "agent" / asset).write_text("not json\n", encoding="utf-8")

                report = validate_workspace_v2(workspace)

                self.assertFalse(report.publishable)
                self.assertTrue(any(f"agent/{asset}" in error for error in report.errors))

    def test_validate_v2_rejects_asset_leaf_symlink_or_non_regular_file(self):
        workspace = self._prepare_publishable("asset-leaf")
        identity = workspace / "agent" / "identity.json"
        outside = self.root / "outside-identity.json"
        outside.write_text('{"selfNames":["外部"]}', encoding="utf-8")
        identity.unlink()
        identity.symlink_to(outside)

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("符号链接" in error for error in report.errors))

        workspace = self._prepare_publishable("asset-directory")
        identity = workspace / "agent" / "identity.json"
        identity.unlink()
        identity.mkdir()

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("普通文件" in error for error in report.errors))

    def test_validate_v2_rejects_blank_persona(self):
        workspace = self._prepare_publishable("blank-persona")
        (workspace / "agent" / "persona.md").write_text(" \n\t", encoding="utf-8")

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("persona.md" in error and "不能为空" in error for error in report.errors))

    def test_validate_v2_rejects_asset_parent_symlink_outside_workspace(self):
        workspace = self._prepare_publishable("asset-parent")
        outside = self.root / "outside-agent"
        outside.mkdir()
        (outside / "identity.json").write_text('{"selfNames":["外部"]}', encoding="utf-8")
        linked_parent = workspace / "agent" / "linked"
        linked_parent.symlink_to(outside, target_is_directory=True)
        manifest_path = workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        manifest["assets"]["identity"] = "agent/linked/identity.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("符号链接" in error for error in report.errors))

    def test_validate_v2_rejects_symlinked_agent_root(self):
        workspace = self._prepare_publishable("agent-root")
        agent_root = workspace / "agent"
        outside = self.root / "outside-agent-root"
        agent_root.rename(outside)
        agent_root.symlink_to(outside, target_is_directory=True)

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("不安全" in error for error in report.errors))

    def test_validate_v2_requires_structured_assets_to_be_objects(self):
        json_assets = ("identity.json", "voice.json", "concepts.json", "openers.json")
        jsonl_assets = ("worldview.jsonl", "episodes.jsonl", "examples.jsonl", "eval.jsonl")

        for asset in json_assets:
            for payload in ('"scalar"', "[]"):
                with self.subTest(asset=asset, payload=payload):
                    workspace = self._prepare_publishable(f"json-shape-{asset}-{len(payload)}")
                    (workspace / "agent" / asset).write_text(payload, encoding="utf-8")

                    report = validate_workspace_v2(workspace)

                    self.assertFalse(report.publishable)
                    self.assertTrue(any("JSON 对象" in error for error in report.errors))

        for asset in jsonl_assets:
            for payload in ('"scalar"\n', "[]\n"):
                with self.subTest(asset=asset, payload=payload):
                    workspace = self._prepare_publishable(f"jsonl-shape-{asset}-{len(payload)}")
                    (workspace / "agent" / asset).write_text(payload, encoding="utf-8")

                    report = validate_workspace_v2(workspace)

                    self.assertFalse(report.publishable)
                    self.assertTrue(any("JSON 对象" in error for error in report.errors))

    def test_validate_v2_converts_deep_json_recursion_errors_to_reports_and_cli_exit_two(self):
        deep_value = "[" * 2000 + "0" + "]" * 2000
        cases = (
            ("deep-json", "identity.json", deep_value),
            ("deep-jsonl", "worldview.jsonl", deep_value + "\n"),
        )
        for name, asset, payload in cases:
            with self.subTest(name=name):
                workspace = self._prepare_publishable(name)
                (workspace / "agent" / asset).write_text(payload, encoding="utf-8")

                report = validate_workspace_v2(workspace)

                self.assertFalse(report.publishable)
                self.assertTrue(any(f"agent/{asset}" in error for error in report.errors))
                self.assertEqual(2, main(["validate-v2", str(workspace)]))

    def test_validate_v2_converts_deep_manifest_recursion_error_to_report_and_cli_exit_two(self):
        workspace = self._prepare_publishable("deep-manifest")
        (workspace / "workspace.json").write_text("[" * 2000 + "0" + "]" * 2000, encoding="utf-8")

        report = validate_workspace_v2(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("workspace.json" in error for error in report.errors))
        self.assertEqual(2, main(["validate-v2", str(workspace)]))

    def test_prepare_v2_converts_deep_source_catalog_recursion_to_build_error_without_output(self):
        catalog = self.root / "deep-source-catalog.json"
        catalog.write_text(
            "{\"sources\":[{\"sourceId\":\"source-research\",\"fileName\":\"source.md\","
            "\"title\":\"调查研究\",\"genre\":\"speech\",\"authorship\":\"direct\","
            "\"period\":\"1926\"}],\"ignored\":" + "[" * 10000 + "0" + "]" * 10000 + "}",
            encoding="utf-8",
        )
        api_output = self.root / "deep-catalog-api"
        cli_output = self.root / "deep-catalog-cli"

        with self.assertRaisesRegex(BuildError, "来源目录无法读取"):
            prepare_workspace_v2(
                [self.source],
                api_output,
                agent_id="person.researcher",
                name="资料研究者",
                version=2,
                source_catalog_path=catalog,
            )

        self.assertFalse(api_output.exists())
        self.assertEqual(
            1,
            main(
                [
                    "prepare-v2",
                    "--agent-id",
                    "person.researcher",
                    "--name",
                    "资料研究者",
                    "--version",
                    "2",
                    "--output",
                    str(cli_output),
                    "--source-catalog",
                    str(catalog),
                    str(self.source),
                ]
            ),
        )
        self.assertFalse(cli_output.exists())

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

    def _prepare_publishable(self, name: str) -> Path:
        catalog = self.root / f"{name}-catalog.json"
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
            self.root / name,
            agent_id="person.researcher",
            name="资料研究者",
            version=2,
            source_catalog_path=catalog,
        )
        (workspace / "agent" / "identity.json").write_text(
            json.dumps(
                {
                    "selfNames": ["我"],
                    "timeHorizon": "1926",
                    "roles": [],
                    "relationships": [],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        chunk_id = json.loads(
            (workspace / "corpora" / "index" / "chunks.jsonl").read_text("utf-8").splitlines()[0]
        )["id"]
        (workspace / "agent" / "voice.json").write_text(
            json.dumps(
                {
                    "defaultForm": "先判断",
                    "sentenceRhythm": ["短句"],
                    "rhetoricalMoves": ["对比"],
                    "preferredTerms": ["调查"],
                    "avoidPatterns": [],
                    "evidence": [chunk_id],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        (workspace / "agent" / "worldview.jsonl").write_text(
            json.dumps(
                {
                    "id": "stance-method-001",
                    "topic": "调查",
                    "statement": "认识来源于实践",
                    "period": "1926",
                    "evidence": [chunk_id],
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        (workspace / "agent" / "episodes.jsonl").write_text(
            json.dumps(
                {
                    "id": "episode-001",
                    "period": "1926",
                    "location": "湖南",
                    "participants": ["群众"],
                    "summary": "我在调查中形成判断。",
                    "meaning": "调查先于结论。",
                    "evidence": [chunk_id],
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        (workspace / "agent" / "examples.jsonl").write_text(
            json.dumps(
                {
                    "id": "example-001",
                    "intent": "方法",
                    "user": "如何判断",
                    "assistant": "先调查。",
                    "styleTags": ["判断优先"],
                    "generationType": "synthesized",
                    "evidence": [chunk_id],
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        minimums = {
            "grounding": 20,
            "stance": 30,
            "voice": 20,
            "temporal": 12,
            "diversity": 10,
            "global": 8,
        }
        evaluation_rows = [
            {
                "id": f"{category}-{index:03d}",
                "category": category,
                "question": "认识来源于实践",
                "period": "1926",
                "expectedEvidence": [chunk_id],
            }
            for category, total in minimums.items()
            for index in range(total)
        ]
        (workspace / "agent" / "eval.jsonl").write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in evaluation_rows) + "\n",
            encoding="utf-8",
        )
        self.assertTrue(validate_workspace_v2(workspace).publishable)
        return workspace


if __name__ == "__main__":
    unittest.main()
