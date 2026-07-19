import hashlib
import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

from tools.agent_builder import builder, install_planner
from tools.agent_builder.builder import BuildError, pack_workspace_v2
from tools.agent_builder.install_planner import choose_install_profiles, plan_corpus_shards
from tools.agent_builder.models import (
    CorpusShard,
    InstallPackage,
    InstallPlan,
    InstallProfile,
)


class InstallPlannerTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.workspace = self._workspace()
        self.private_key_path = self.root / "publisher-key.pem"
        self.private_key_path.write_bytes(
            Ed25519PrivateKey.generate().private_bytes(
                Encoding.PEM,
                PrivateFormat.PKCS8,
                NoEncryption(),
            )
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_core_contains_referenced_chunks_parent_nodes_and_sources_only(self):
        shards = plan_corpus_shards(self.workspace)
        core = next(shard for shard in shards if shard.package_id == "core-evidence")

        self.assertEqual("required", core.install_class)
        self.assertEqual(("chunk-core", "chunk-secondary"), core.chunk_ids)
        self.assertEqual(("source-direct", "source-secondary"), core.source_ids)
        self.assertEqual(
            (
                "node-direct-root",
                "node-direct-top",
                "node-secondary-root",
                "node-secondary-top",
            ),
            core.node_ids,
        )
        self.assertFalse({"chunk-background", "chunk-dialogue"} & set(core.chunk_ids))

    def test_core_source_metadata_includes_deduplicated_source_aliases(self):
        alias_payload = b"alias source\n"
        alias_path = self.workspace / "sources" / "alias.txt"
        alias_path.write_bytes(alias_payload)
        manifest_path = self.workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        manifest["sources"].append(
            {
                "sourceId": "source-alias",
                "title": "同文异本",
                "fileName": "alias.txt",
                "storedName": "alias.txt",
                "sourceHash": hashlib.sha256(alias_payload).hexdigest(),
                "format": "txt",
                "genre": "speech",
                "authorship": "direct",
                "period": "1926",
                "rawSizeBytes": len(alias_payload),
                "extractedChars": 10,
            }
        )
        self._write_json(manifest_path, manifest)
        chunks_path = self.workspace / "corpora" / "index" / "chunks.jsonl"
        chunks = [
            json.loads(line)
            for line in chunks_path.read_text("utf-8").splitlines()
            if line.strip()
        ]
        next(row for row in chunks if row["id"] == "chunk-core")["sourceAliases"].append(
            "source-alias"
        )
        self._write_jsonl(chunks_path, chunks)

        core = next(
            shard for shard in plan_corpus_shards(self.workspace)
            if shard.package_id == "core-evidence"
        )

        self.assertIn("source-alias", core.source_ids)

    def test_non_core_shards_follow_source_period_and_top_level_boundaries(self):
        first = plan_corpus_shards(self.workspace)
        second = plan_corpus_shards(self.workspace)
        non_core = [shard for shard in first if shard.package_id != "core-evidence"]

        self.assertEqual(first, second)
        self.assertEqual(4, len(non_core))
        self.assertEqual(
            {
                ("source-direct", "1926", "node-direct-top", ("chunk-core",)),
                ("source-direct", "1926", "node-direct-background", ("chunk-background",)),
                ("source-dialogue", "1930", "node-dialogue-top", ("chunk-dialogue",)),
                ("source-secondary", "1926", "node-secondary-top", ("chunk-secondary",)),
            },
            {
                (shard.source_ids[0], shard.periods[0], shard.top_level_ids[0], shard.chunk_ids)
                for shard in non_core
            },
        )
        self.assertEqual(
            {"chunk-core", "chunk-background", "chunk-dialogue", "chunk-secondary"},
            {chunk_id for shard in non_core for chunk_id in shard.chunk_ids},
        )

    def test_profiles_use_unique_coverage_gain_and_exact_membership_contracts(self):
        shards = plan_corpus_shards(self.workspace)
        source_shards = [
            CorpusShard.source(
                package_id=f"source-{source_id}",
                source_ids=(source_id,),
                source_hashes=(source_hash,),
            )
            for source_id, source_hash in (
                ("source-dialogue", self._source_hash("dialogue.txt")),
                ("source-direct", self._source_hash("direct.txt")),
                ("source-secondary", self._source_hash("secondary.txt")),
            )
        ]
        plan = choose_install_profiles([*shards, *source_shards])

        lite = plan.profile("lite").package_ids
        balanced = plan.profile("balanced").package_ids
        complete = plan.profile("complete").package_ids
        source = plan.profile("source").package_ids
        dialogue = next(
            shard.package_id
            for shard in shards
            if shard.chunk_ids == ("chunk-dialogue",)
        )
        background = next(
            shard.package_id
            for shard in shards
            if shard.chunk_ids == ("chunk-background",)
        )

        self.assertEqual(("core-evidence",), lite)
        self.assertEqual(("core-evidence", dialogue), balanced)
        self.assertNotIn(background, balanced)
        self.assertEqual(
            {"core-evidence", *(shard.package_id for shard in shards if shard.package_id != "core-evidence")},
            set(complete),
        )
        self.assertEqual(set(complete) | {shard.package_id for shard in source_shards}, set(source))
        self.assertEqual("balanced", plan.recommended_profile_id)
        for profile in plan.profiles:
            self.assertEqual(len(profile.package_ids), len(set(profile.package_ids)))

    def test_pack_records_real_child_hashes_and_nests_exact_signed_bytes(self):
        result = pack_workspace_v2(
            self.workspace,
            self.root / "dist",
            self.private_key_path,
            profile_id="balanced",
            emit_sources=False,
        )

        with zipfile.ZipFile(result.agent_package) as archive:
            install_plan = json.loads(archive.read("install-plan.json"))
            manifest = json.loads(archive.read("manifest.json"))
        with zipfile.ZipFile(result.bundle_package) as bundle:
            names = set(bundle.namelist())
            bundle_manifest = json.loads(bundle.read("bundle-manifest.json"))
            for package in install_plan["packages"]:
                if package["id"] not in bundle_manifest["selectedPackageIds"]:
                    continue
                nested_name = f"packages/{package['fileName']}"
                self.assertIn(nested_name, names)
                nested = bundle.read(nested_name)
                self.assertEqual(package["sizeBytes"], len(nested))
                self.assertEqual(package["sha256"], hashlib.sha256(nested).hexdigest())

        self.assertFalse(manifest["runnableWithoutCorpora"])
        self.assertEqual(["core-evidence"], manifest["requiredCorpora"])
        self.assertTrue(result.report_path.is_file())
        report = json.loads(result.report_path.read_text("utf-8"))
        self.assertEqual("balanced", report["profile"])
        self.assertFalse(report["sourcesEmitted"])
        self.assertGreater(report["bundleSizeBytes"], 0)

    def test_recommended_install_class_matches_corpus_manifest(self):
        result = pack_workspace_v2(
            self.workspace,
            self.root / "dist-classes",
            self.private_key_path,
        )
        with zipfile.ZipFile(result.agent_package) as archive:
            install_plan = json.loads(archive.read("install-plan.json"))
        recommended = {
            package["id"]: package
            for package in install_plan["packages"]
            if package["installClass"] == "recommended"
        }

        self.assertTrue(recommended)
        by_name = {path.name: path for path in result.corpus_packages}
        for package in recommended.values():
            with zipfile.ZipFile(by_name[package["fileName"]]) as archive:
                manifest = json.loads(archive.read("manifest.json"))
            self.assertEqual("recommended", manifest["installClass"])

    def test_source_package_declares_owning_agent_and_exact_source(self):
        result = pack_workspace_v2(
            self.workspace,
            self.root / "dist-sources",
            self.private_key_path,
            profile_id="source",
            emit_sources=True,
        )

        self.assertEqual(3, len(result.source_packages))
        for path in result.source_packages:
            with zipfile.ZipFile(path) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                payload_names = [
                    name for name in archive.namelist() if name.startswith("files/")
                ]
            self.assertEqual("person.fixture", manifest["agentId"])
            self.assertEqual(2, manifest["version"])
            self.assertEqual(1, len(payload_names))

    def test_install_plan_rejects_invalid_artifacts_and_dependency_incomplete_profiles(self):
        valid_hash = "a" * 64
        required = InstallPackage(
            "core-evidence",
            "hcorpus",
            "core-evidence.hcorpus",
            "required",
            (),
            10,
            valid_hash,
        )
        dependent = InstallPackage(
            "dependent",
            "hcorpus",
            "dependent.hcorpus",
            "optional",
            ("core-evidence",),
            10,
            valid_hash,
        )

        for broken in (
            InstallPackage("bad", "hcorpus", "bad.hcorpus", "optional", (), 0, valid_hash),
            InstallPackage("bad", "hcorpus", "bad.hcorpus", "optional", (), -1, valid_hash),
            InstallPackage("bad", "hcorpus", "bad.hcorpus", "optional", (), 10, "not-a-hash"),
        ):
            with self.subTest(broken=broken):
                plan = InstallPlan(
                    (required, broken),
                    tuple(
                        InstallProfile(profile_id, ("core-evidence",))
                        for profile_id in ("lite", "balanced", "complete", "source")
                    ),
                    ("core-evidence",),
                )
                with self.assertRaises(BuildError):
                    plan.to_dict(require_artifacts=True)

        required_with_dependency = InstallPackage(
            "core-evidence",
            "hcorpus",
            "core-evidence.hcorpus",
            "required",
            ("dependent",),
            10,
            valid_hash,
        )
        dependency_incomplete = InstallPlan(
            (required_with_dependency, dependent),
            (
                InstallProfile("lite", ("core-evidence",)),
                InstallProfile("balanced", ("core-evidence", "dependent"), recommended=True),
                InstallProfile("complete", ("core-evidence", "dependent")),
                InstallProfile("source", ("core-evidence", "dependent")),
            ),
            ("core-evidence",),
        )
        with self.assertRaisesRegex(BuildError, "依赖"):
            dependency_incomplete.to_dict(require_artifacts=True)

        optional = InstallPackage(
            "optional",
            "hcorpus",
            "optional.hcorpus",
            "optional",
            (),
            10,
            valid_hash,
        )
        source = InstallPackage(
            "source-one",
            "hsource",
            "source-one.hsource",
            "source",
            (),
            10,
            valid_hash,
        )
        for profiles in (
            (
                InstallProfile("lite", ("core-evidence", "optional")),
                InstallProfile("balanced", ("core-evidence",), recommended=True),
                InstallProfile("complete", ("core-evidence", "optional")),
                InstallProfile("source", ("core-evidence", "optional", "source-one")),
            ),
            (
                InstallProfile("lite", ("core-evidence",)),
                InstallProfile("balanced", ("core-evidence",), recommended=True),
                InstallProfile("complete", ("core-evidence", "optional")),
                InstallProfile("source", ("core-evidence", "optional")),
            ),
        ):
            with self.subTest(profiles=profiles):
                invalid_membership = InstallPlan(
                    (required, optional, source),
                    profiles,
                    ("core-evidence",),
                )
                with self.assertRaisesRegex(BuildError, "集合"):
                    invalid_membership.to_dict(require_artifacts=True)

    def test_signed_package_rejects_unsafe_or_reserved_paths(self):
        key = Ed25519PrivateKey.generate()
        for unsafe in (
            "../escape",
            "/absolute",
            "a//b",
            r"a\b",
            "C:drive",
            "checksums.json",
            "signature.json",
        ):
            with self.subTest(unsafe=unsafe):
                with self.assertRaises(BuildError):
                    builder._write_signed_package_v2(
                        self.root / f"unsafe-{hashlib.sha256(unsafe.encode()).hexdigest()}.zip",
                        {unsafe: b"x"},
                        key,
                    )
        payload = self.root / "payload.bin"
        payload.write_bytes(b"payload")
        with self.assertRaises(BuildError):
            builder._write_signed_package_v2(
                self.root / "wrong-expected.zip",
                {"payload.bin": payload},
                key,
                expected_files={"payload.bin": ("0" * 64, len(b"payload"))},
            )

    def test_sparse_large_source_is_streamed_into_source_package(self):
        source_path = self.workspace / "sources" / "dialogue.txt"
        with source_path.open("wb") as stream:
            stream.seek(32 * 1024 * 1024 - 1)
            stream.write(b"\0")
        source_hash = self._sha256(source_path)
        manifest_path = self.workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        source_row = next(
            row for row in manifest["sources"] if row["sourceId"] == "source-dialogue"
        )
        source_row["sourceHash"] = source_hash
        source_row["rawSizeBytes"] = source_path.stat().st_size
        self._write_json(manifest_path, manifest)
        chunks_path = self.workspace / "corpora" / "index" / "chunks.jsonl"
        chunks = [
            json.loads(line)
            for line in chunks_path.read_text("utf-8").splitlines()
            if line.strip()
        ]
        next(
            row for row in chunks if row["sourceId"] == "source-dialogue"
        )["sourceHash"] = source_hash
        self._write_jsonl(chunks_path, chunks)

        result = pack_workspace_v2(
            self.workspace,
            self.root / "dist-sparse",
            self.private_key_path,
            profile_id="source",
            emit_sources=True,
        )

        dialogue_package = next(
            path for path in result.source_packages if "source-dialogue" in path.name
        )
        with zipfile.ZipFile(dialogue_package) as archive:
            info = next(
                info for info in archive.infolist() if info.filename.startswith("files/")
            )
        self.assertEqual(32 * 1024 * 1024, info.file_size)

    def test_repeated_v2_builds_are_byte_identical(self):
        first = pack_workspace_v2(
            self.workspace,
            self.root / "dist-first",
            self.private_key_path,
        )
        second = pack_workspace_v2(
            self.workspace,
            self.root / "dist-second",
            self.private_key_path,
        )

        first_files = sorted(
            [first.agent_package, *first.corpus_packages, first.bundle_package],
            key=lambda path: path.name,
        )
        second_files = sorted(
            [second.agent_package, *second.corpus_packages, second.bundle_package],
            key=lambda path: path.name,
        )
        self.assertEqual([path.name for path in first_files], [path.name for path in second_files])
        self.assertEqual(
            [self._sha256(path) for path in first_files],
            [self._sha256(path) for path in second_files],
        )

    def test_v2_pack_does_not_use_path_whole_file_reads(self):
        protected = {
            self.workspace / "corpora" / "index" / "chunks.jsonl",
            self.workspace / "corpora" / "index" / "nodes.jsonl",
            self.workspace / "corpora" / "index" / "duplicates.jsonl",
            *(self.workspace / "sources").iterdir(),
        }
        original_read_bytes = Path.read_bytes
        original_read_text = Path.read_text

        def reject_read_bytes(path, *args, **kwargs):
            if path in protected or path.suffix in {".hcorpus", ".hsource", ".hagent"}:
                raise AssertionError(f"whole-file read_bytes: {path}")
            return original_read_bytes(path, *args, **kwargs)

        def reject_read_text(path, *args, **kwargs):
            if path in protected:
                raise AssertionError(f"whole-file read_text: {path}")
            return original_read_text(path, *args, **kwargs)

        with (
            mock.patch.object(Path, "read_bytes", reject_read_bytes),
            mock.patch.object(Path, "read_text", reject_read_text),
        ):
            result = pack_workspace_v2(
                self.workspace,
                self.root / "dist-streamed",
                self.private_key_path,
                profile_id="source",
                emit_sources=True,
            )

        self.assertEqual(3, len(result.source_packages))

    def test_emit_sources_false_never_opens_original_source_payload(self):
        original_open = os.open
        source_names = {source.name for source in (self.workspace / "sources").iterdir()}

        def protected_open(path, flags, *args, **kwargs):
            if str(path) in source_names:
                raise AssertionError(f"source payload opened: {path}")
            return original_open(path, flags, *args, **kwargs)

        with mock.patch.object(builder.os, "open", protected_open):
            result = pack_workspace_v2(
                self.workspace,
                self.root / "dist-no-sources",
                self.private_key_path,
                emit_sources=False,
            )

        self.assertEqual([], result.source_packages)

    def test_emit_sources_false_still_rejects_symlink_source_metadata(self):
        outside = self.root / "outside.txt"
        outside.write_text("outside", encoding="utf-8")
        source = self.workspace / "sources" / "direct.txt"
        source.unlink()
        source.symlink_to(outside)

        with self.assertRaisesRegex(BuildError, "普通文件"):
            pack_workspace_v2(
                self.workspace,
                self.root / "dist-symlink",
                self.private_key_path,
                emit_sources=False,
            )

    def test_invalid_profile_and_injected_write_failure_publish_nothing(self):
        with self.assertRaisesRegex(BuildError, "profile"):
            pack_workspace_v2(
                self.workspace,
                self.root / "bad-profile",
                self.private_key_path,
                profile_id="missing",
            )
        output = self.root / "dist-failure"
        output.mkdir()
        unrelated = output / "keep.txt"
        unrelated.write_text("keep", encoding="utf-8")

        with (
            mock.patch.object(
                builder,
                "_stream_file_into_zip",
                side_effect=OSError("injected zip failure"),
            ),
            self.assertRaises(BuildError),
        ):
            pack_workspace_v2(
                self.workspace,
                output,
                self.private_key_path,
                profile_id="source",
                emit_sources=True,
            )

        self.assertEqual(["keep.txt"], sorted(path.name for path in output.iterdir()))
        self.assertFalse(any(".staging-" in path.name for path in output.parent.iterdir()))

    def test_planner_cleans_disk_index_when_enter_fails(self):
        before = set(Path(tempfile.gettempdir()).glob(".harness-install-plan-*"))
        with (
            mock.patch.object(
                install_planner,
                "_iter_jsonl_regular",
                side_effect=BuildError("injected planner failure"),
            ),
            self.assertRaises(BuildError),
        ):
            plan_corpus_shards(self.workspace)
        after = set(Path(tempfile.gettempdir()).glob(".harness-install-plan-*"))

        self.assertEqual(before, after)

    def _workspace(self) -> Path:
        workspace = self.root / "workspace"
        (workspace / "agent").mkdir(parents=True)
        (workspace / "corpora" / "index").mkdir(parents=True)
        (workspace / "sources").mkdir()

        source_specs = (
            ("source-direct", "direct.txt", "演讲", "speech", "direct", "1926", b"direct source\n"),
            (
                "source-dialogue",
                "dialogue.txt",
                "谈话",
                "conversation",
                "direct",
                "1930",
                b"dialogue source\n",
            ),
            (
                "source-secondary",
                "secondary.txt",
                "旁证",
                "secondary",
                "secondary",
                "1926",
                b"secondary source\n",
            ),
        )
        sources = []
        for source_id, stored_name, title, genre, authorship, period, payload in source_specs:
            path = workspace / "sources" / stored_name
            path.write_bytes(payload)
            sources.append(
                {
                    "sourceId": source_id,
                    "title": title,
                    "fileName": stored_name,
                    "storedName": stored_name,
                    "sourceHash": hashlib.sha256(payload).hexdigest(),
                    "format": "txt",
                    "genre": genre,
                    "authorship": authorship,
                    "period": period,
                    "rawSizeBytes": len(payload),
                    "extractedChars": 100,
                }
            )
        assets = {
            "persona": "agent/persona.md",
            "identity": "agent/identity.json",
            "voice": "agent/voice.json",
            "worldview": "agent/worldview.jsonl",
            "episodes": "agent/episodes.jsonl",
            "concepts": "agent/concepts.json",
            "examples": "agent/examples.jsonl",
            "openers": "agent/openers.json",
            "eval": "agent/eval.jsonl",
        }
        self._write_json(
            workspace / "workspace.json",
            {
                "schemaVersion": 2,
                "agent": {"id": "person.fixture", "name": "测试人物", "version": 2},
                "assets": assets,
                "sources": sources,
            },
        )
        nodes = [
            self._node("node-direct-root", "source", "source-direct", None, ["演讲"]),
            self._node(
                "node-direct-background",
                "chapter",
                "source-direct",
                "node-direct-root",
                ["演讲", "背景"],
            ),
            self._node(
                "node-direct-top",
                "chapter",
                "source-direct",
                "node-direct-root",
                ["演讲", "调查"],
            ),
            self._node("node-dialogue-root", "source", "source-dialogue", None, ["谈话"]),
            self._node(
                "node-dialogue-top",
                "chapter",
                "source-dialogue",
                "node-dialogue-root",
                ["谈话", "交谈"],
            ),
            self._node("node-secondary-root", "source", "source-secondary", None, ["旁证"]),
            self._node(
                "node-secondary-top",
                "chapter",
                "source-secondary",
                "node-secondary-root",
                ["旁证", "调查"],
            ),
        ]
        source_hashes = {source["sourceId"]: source["sourceHash"] for source in sources}
        chunks = [
            self._chunk(
                "chunk-background",
                "source-direct",
                source_hashes["source-direct"],
                "speech",
                "direct",
                "1926",
                ["node-direct-root", "node-direct-background"],
                "背景资料重复已有时期与体裁。",
                "background",
            ),
            self._chunk(
                "chunk-core",
                "source-direct",
                source_hashes["source-direct"],
                "speech",
                "direct",
                "1926",
                ["node-direct-root", "node-direct-top"],
                "调查以后再下结论。",
                "core-direct",
            ),
            self._chunk(
                "chunk-dialogue",
                "source-dialogue",
                source_hashes["source-dialogue"],
                "conversation",
                "direct",
                "1930",
                ["node-dialogue-root", "node-dialogue-top"],
                "我在谈话中直接回答群众的问题。",
                "dialogue",
            ),
            self._chunk(
                "chunk-secondary",
                "source-secondary",
                source_hashes["source-secondary"],
                "secondary",
                "secondary",
                "1926",
                ["node-secondary-root", "node-secondary-top"],
                "旁证也记录调查方法。",
                "core-secondary",
            ),
        ]
        self._write_jsonl(workspace / "corpora" / "index" / "nodes.jsonl", nodes)
        self._write_jsonl(workspace / "corpora" / "index" / "chunks.jsonl", chunks)
        self._write_jsonl(workspace / "corpora" / "index" / "duplicates.jsonl", [])
        self._write_json(
            workspace / "corpora" / "index" / "report.json",
            {
                "sourceCount": 3,
                "rawBytes": sum(source["rawSizeBytes"] for source in sources),
                "extractedCharacters": 400,
                "estimatedTokens": 100,
                "chunksBeforeDeduplication": 4,
                "chunksAfterDeduplication": 4,
                "exactDuplicateCount": 0,
                "nearDuplicateCount": 0,
                "extractionFailures": [],
                "metadataCoverage": 1.0,
                "deduplicationRatio": 0.0,
            },
        )
        (workspace / "agent" / "persona.md").write_text(
            "我是测试人物，属于基于资料构建的模拟代理。",
            encoding="utf-8",
        )
        self._write_json(
            workspace / "agent" / "identity.json",
            {
                "selfNames": ["我"],
                "timeHorizon": "1926-1930",
                "roles": ["调查者"],
                "relationships": [
                    {
                        "subject": "群众",
                        "relation": "调查对象",
                        "period": "1926",
                        "evidence": ["chunk-core"],
                    }
                ],
            },
        )
        self._write_json(
            workspace / "agent" / "voice.json",
            {
                "defaultForm": "先问事实",
                "sentenceRhythm": ["短句"],
                "rhetoricalMoves": ["反问"],
                "preferredTerms": ["调查"],
                "avoidPatterns": [],
                "evidence": ["chunk-core"],
            },
        )
        self._write_jsonl(
            workspace / "agent" / "worldview.jsonl",
            [
                {
                    "id": "stance-1",
                    "topic": "调查",
                    "statement": "调查先于结论",
                    "conditions": [],
                    "period": "1926",
                    "aliases": [],
                    "confidence": 1.0,
                    "evidence": ["chunk-core"],
                }
            ],
        )
        self._write_jsonl(
            workspace / "agent" / "episodes.jsonl",
            [
                {
                    "id": "episode-1",
                    "period": "1926",
                    "location": "现场",
                    "participants": ["群众"],
                    "summary": "我进行了调查。",
                    "meaning": "先掌握事实。",
                    "evidence": ["chunk-core"],
                }
            ],
        )
        self._write_json(
            workspace / "agent" / "concepts.json",
            {
                "concepts": [
                    {
                        "id": "concept-1",
                        "name": "调查",
                        "aliases": [],
                        "keywords": ["事实"],
                        "evidence": ["chunk-core"],
                    }
                ]
            },
        )
        self._write_jsonl(
            workspace / "agent" / "examples.jsonl",
            [
                {
                    "id": "example-1",
                    "intent": "方法",
                    "user": "如何判断",
                    "assistant": "先调查。",
                    "styleTags": ["直接"],
                    "generationType": "synthesized",
                    "evidence": ["chunk-core"],
                }
            ],
        )
        self._write_json(workspace / "agent" / "openers.json", {"default": "", "alternatives": []})
        minimums = {
            "grounding": 20,
            "stance": 30,
            "voice": 20,
            "temporal": 12,
            "diversity": 10,
            "global": 8,
        }
        eval_rows = []
        for category, count in minimums.items():
            for index in range(count):
                eval_rows.append(
                    {
                        "id": f"{category}-{index:03d}",
                        "category": category,
                        "question": (
                            "coreterm secondaryterm"
                            if category in {"diversity", "global"}
                            else "coreterm"
                        ),
                        "period": "1926",
                        "expectedEvidence": (
                            ["chunk-core", "chunk-secondary"]
                            if category in {"diversity", "global"}
                            else ["chunk-core"]
                        ),
                        "corpusId": "core-evidence",
                    }
                )
        self._write_jsonl(workspace / "agent" / "eval.jsonl", eval_rows)
        return workspace

    @staticmethod
    def _node(node_id, kind, source_id, parent_id, path):
        return {
            "id": node_id,
            "kind": kind,
            "parentId": parent_id,
            "path": path,
            "sourceId": source_id,
            "summary": "层级摘要",
            "title": path[-1],
        }

    @staticmethod
    def _chunk(
        chunk_id,
        source_id,
        source_hash,
        genre,
        authorship,
        period,
        parent_ids,
        text,
        duplicate_group,
    ):
        return {
            "authorship": authorship,
            "conflictKey": "",
            "context": text,
            "duplicateGroup": duplicate_group,
            "genre": genre,
            "id": chunk_id,
            "keywords": [
                {
                    "core-direct": "coreterm",
                    "core-secondary": "secondaryterm",
                    "dialogue": "dialogueterm",
                    "background": "backgroundterm",
                }[duplicate_group]
            ],
            "location": "fixture",
            "ngrams": [],
            "parentIds": parent_ids,
            "period": period,
            "sourceAliases": [],
            "sourceHash": source_hash,
            "sourceId": source_id,
            "sourceTitle": source_id,
            "text": text,
            "simHash": "0000000000000001",
        }

    def _source_hash(self, name: str) -> str:
        return self._sha256(self.workspace / "sources" / name)

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
        return digest.hexdigest()

    @staticmethod
    def _write_json(path: Path, value):
        path.write_text(
            json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            encoding="utf-8",
        )

    @classmethod
    def _write_jsonl(cls, path: Path, rows):
        path.write_text(
            "".join(
                json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
                for row in rows
            ),
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
