import hashlib
import json
import os
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from io import StringIO
from pathlib import Path
from unittest import mock

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

from tools.agent_builder import builder, install_planner, recommendation
from tools.agent_builder.builder import BuildError, pack_workspace_v2
from tools.agent_builder.cli import main
from tools.agent_builder.install_planner import (
    CorpusPlanIndex,
    choose_install_profiles,
    plan_corpus_shards,
)
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
        self.assertEqual(("chunk-core", "chunk-dialogue", "chunk-secondary"), core.chunk_ids)
        self.assertEqual(("source-dialogue", "source-direct", "source-secondary"), core.source_ids)
        self.assertEqual(
            ("node-dialogue-root", "node-direct-root", "node-secondary-root"),
            core.top_level_ids,
        )
        self.assertEqual(
            (
                "node-dialogue-root",
                "node-dialogue-top",
                "node-direct-root",
                "node-direct-top",
                "node-secondary-root",
                "node-secondary-top",
            ),
            core.node_ids,
        )
        self.assertFalse({"chunk-background"} & set(core.chunk_ids))

    def test_streamed_small_package_entry_uses_android_compatible_local_header(self):
        payload = self.root / "payload.bin"
        payload.write_bytes(b"signed child package")
        package = self.root / "streamed.hbundle"

        builder._write_signed_package_v2(
            package,
            {"packages/child.hcorpus": payload},
            Ed25519PrivateKey.generate(),
        )

        with zipfile.ZipFile(package) as archive, package.open("rb") as stream:
            entry = archive.getinfo("packages/child.hcorpus")
            stream.seek(entry.header_offset)
            header = stream.read(30)
            compressed_size, uncompressed_size, name_length, extra_length = struct.unpack_from(
                "<IIHH", header, 18
            )
            stream.seek(name_length, os.SEEK_CUR)
            extra = stream.read(extra_length)
        self.assertNotEqual(0xFFFFFFFF, compressed_size)
        self.assertNotEqual(0xFFFFFFFF, uncompressed_size)
        self.assertNotIn(b"\x01\x00", extra)

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
                ("source-direct", "1926", "node-direct-root", "node-direct-top", ("chunk-core",)),
                (
                    "source-direct",
                    "1926",
                    "node-direct-root",
                    "node-direct-background",
                    ("chunk-background",),
                ),
                (
                    "source-dialogue",
                    "1930",
                    "node-dialogue-root",
                    "node-dialogue-top",
                    ("chunk-dialogue",),
                ),
                (
                    "source-secondary",
                    "1926",
                    "node-secondary-root",
                    "node-secondary-top",
                    ("chunk-secondary",),
                ),
            },
            {
                (
                    shard.source_ids[0],
                    shard.periods[0],
                    shard.top_level_ids[0],
                    shard.selection_top_id,
                    shard.chunk_ids,
                )
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
        self.assertIn(dialogue, balanced)
        self.assertEqual("core-evidence", balanced[0])
        self.assertNotIn(background, balanced)
        self.assertEqual(
            {"core-evidence", *(shard.package_id for shard in shards if shard.package_id != "core-evidence")},
            set(complete),
        )
        self.assertEqual(set(complete) | {shard.package_id for shard in source_shards}, set(source))
        self.assertEqual("balanced", plan.recommended_profile_id)
        for profile in plan.profiles:
            self.assertEqual(len(profile.package_ids), len(set(profile.package_ids)))

    def test_rejects_duplicate_rows_inconsistent_with_physical_chunk(self):
        rows = self._jsonl_rows("duplicates.jsonl")
        rows.append(
            {
                "duplicateChunkId": "duplicate-core",
                "physicalChunkId": "chunk-core",
                "primarySourceId": "source-dialogue",
                "duplicateSourceId": "source-dialogue",
                "period": "1930",
                "conflictKey": "different",
                "matchType": "unsafe",
            }
        )
        self._write_jsonl(self.workspace / "corpora" / "index" / "duplicates.jsonl", rows)

        with self.assertRaisesRegex(BuildError, "duplicates.jsonl"):
            plan_corpus_shards(self.workspace)

    def test_rejects_duplicate_that_reuses_a_physical_chunk_id(self):
        self._write_jsonl(
            self.workspace / "corpora" / "index" / "duplicates.jsonl",
            [{
                "duplicateChunkId": "chunk-core",
                "physicalChunkId": "chunk-core",
                "primarySourceId": "source-direct",
                "duplicateSourceId": "source-direct",
                "period": "1926",
                "conflictKey": "",
                "matchType": "exact",
            }],
        )

        with self.assertRaisesRegex(BuildError, "duplicates.jsonl"):
            plan_corpus_shards(self.workspace)

    def test_duplicate_package_includes_every_referenced_source_metadata(self):
        alias_payload = b"alias source\n"
        (self.workspace / "sources" / "alias.txt").write_bytes(alias_payload)
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
        chunks = self._jsonl_rows("chunks.jsonl")
        next(row for row in chunks if row["id"] == "chunk-core")["sourceAliases"].append("source-alias")
        self._write_jsonl(self.workspace / "corpora" / "index" / "chunks.jsonl", chunks)
        nodes = self._jsonl_rows("nodes.jsonl")
        nodes.append(self._node("node-alias-root", "source", "source-alias", None, ["同文异本"]))
        self._write_jsonl(self.workspace / "corpora" / "index" / "nodes.jsonl", nodes)
        self._write_jsonl(
            self.workspace / "corpora" / "index" / "duplicates.jsonl",
            [{
                "duplicateChunkId": "duplicate-core-alias",
                "physicalChunkId": "chunk-core",
                "primarySourceId": "source-direct",
                "duplicateSourceId": "source-alias",
                "period": "1926",
                "conflictKey": "",
                "matchType": "exact",
            }],
        )

        result = pack_workspace_v2(self.workspace, self.root / "dist-duplicate-alias", self.private_key_path)
        core = next(path for path in result.corpus_packages if path.name == "core-evidence.hcorpus")
        with zipfile.ZipFile(core) as archive:
            source_ids = {row["sourceId"] for row in json.loads(archive.read("sources.json"))}
            packaged_chunks = [
                json.loads(line)
                for line in archive.read("chunks.jsonl").decode("utf-8").splitlines()
            ]
        self.assertIn("source-alias", source_ids)
        core_chunk = next(row for row in packaged_chunks if row["id"] == "chunk-core")
        self.assertEqual(["source-alias"], core_chunk["sourceAliases"])

    def test_bundle_rejects_selected_paths_with_wrong_package_id_sequence(self):
        result = pack_workspace_v2(self.workspace, self.root / "dist-sequence", self.private_key_path)
        with zipfile.ZipFile(result.agent_package) as archive:
            raw_plan = json.loads(archive.read("install-plan.json"))
        packages = tuple(
            InstallPackage(
                package_id=row["id"],
                package_type=row["type"],
                file_name=row["fileName"],
                install_class=row["installClass"],
                dependencies=tuple(row["dependencies"]),
                size_bytes=row["sizeBytes"],
                sha256=row["sha256"],
            )
            for row in raw_plan["packages"]
        )
        plan = InstallPlan(
            packages=packages,
            profiles=tuple(
                InstallProfile(row["id"], tuple(row["packageIds"]), row["recommended"])
                for row in raw_plan["profiles"]
            ),
            required_corpus_ids=tuple(raw_plan["requiredCorpusIds"]),
        )
        paths_by_name = {path.name: path for path in result.corpus_packages}
        selected = [paths_by_name[next(row.file_name for row in packages if row.package_id == package_id)]
                    for package_id in plan.profile("balanced").package_ids]
        self.assertGreaterEqual(len(selected), 2)
        selected[0], selected[1] = selected[1], selected[0]
        agent_hash = self._sha256(result.agent_package)

        with self.assertRaisesRegex(BuildError, "package ID 序列"):
            builder._pack_bundle_v2(
                builder.load_workspace_v2(self.workspace),
                "balanced",
                result.agent_package,
                agent_hash,
                result.agent_package.stat().st_size,
                plan,
                selected,
                self.root / "wrong-order.hbundle",
                Ed25519PrivateKey.generate(),
            )

    def test_pack_rejects_manifest_drift_between_validation_and_planning(self):
        original_index = install_planner.CorpusPlanIndex

        class DriftedIndex:
            def __init__(self, workspace):
                self._delegate = original_index(workspace)

            def __enter__(self):
                self._delegate.__enter__()
                self.manifest = replace(self._delegate.manifest, version=self._delegate.manifest.version + 1)
                return self

            def __exit__(self, *args):
                return self._delegate.__exit__(*args)

            def __getattr__(self, name):
                return getattr(self._delegate, name)

        with mock.patch.object(install_planner, "CorpusPlanIndex", DriftedIndex):
            with self.assertRaisesRegex(BuildError, "manifest"):
                pack_workspace_v2(self.workspace, self.root / "dist-manifest-drift", self.private_key_path)

    def test_balanced_rejects_unknown_coverage_and_does_not_count_dialogue_chunks(self):
        shards = plan_corpus_shards(self.workspace)
        dialogue = next(
            shard for shard in shards if shard.chunk_ids == ("chunk-dialogue",)
        )
        self.assertTrue(any(feature.startswith("voice:") for feature in dialogue.coverage))
        self.assertFalse(any(feature.startswith("dialogue:") for feature in dialogue.coverage))
        with self.assertRaisesRegex(BuildError, "coverage"):
            choose_install_profiles([
                *shards,
                CorpusShard(
                    package_id="unknown-coverage",
                    package_type="hcorpus",
                    title="bad",
                    install_class="optional",
                    coverage=frozenset({"unknown:feature"}),
                    file_name="unknown.hcorpus",
                ),
            ])

    def test_large_dialogue_shard_uses_compact_coverage_when_ids_not_materialized(self):
        chunks = self._jsonl_rows("chunks.jsonl")
        dialogue = next(row for row in chunks if row["id"] == "chunk-dialogue")
        for index in range(5000):
            row = dict(dialogue)
            row["id"] = f"chunk-dialogue-large-{index:04d}"
            row["text"] = f"谈话材料 {index}"
            row["keywords"] = ["dialogueterm"]
            chunks.append(row)
        self._write_jsonl(self.workspace / "corpora" / "index" / "chunks.jsonl", chunks)

        with CorpusPlanIndex(self.workspace) as index:
            shards = index.shards(materialize_ids=False)
        dialogue_shard = next(shard for shard in shards if shard.source_ids == ("source-dialogue",))
        self.assertEqual((), dialogue_shard.chunk_ids)
        self.assertLess(len(dialogue_shard.coverage), 16)
        self.assertFalse(any("chunk-dialogue-large" in item for item in dialogue_shard.coverage))

    def test_planner_rejects_symlinked_intermediate_index_directory(self):
        outside = self.root / "outside-index"
        outside.mkdir()
        for name in ("nodes.jsonl", "chunks.jsonl", "duplicates.jsonl"):
            (outside / name).write_bytes((self.workspace / "corpora" / "index" / name).read_bytes())
        index = self.workspace / "corpora" / "index"
        for path in index.iterdir():
            path.unlink()
        index.rmdir()
        index.symlink_to(outside, target_is_directory=True)

        with self.assertRaisesRegex(BuildError, "索引目录|不安全"):
            plan_corpus_shards(self.workspace)

    def test_pack_rejects_unknown_or_wrong_corpus_question_attribution(self):
        rows = self._jsonl_rows("eval.jsonl")
        rows[0]["corpusId"] = "corpus-unknown"
        self._write_jsonl(self.workspace / "agent" / "eval.jsonl", rows)
        with self.assertRaisesRegex(BuildError, "评估题"):
            pack_workspace_v2(self.workspace, self.root / "dist-unknown-corpus", self.private_key_path)

        workspace = self._workspace("wrong-corpus")
        rows = self._jsonl_rows("eval.jsonl", workspace)
        dialogue_id = install_planner._shard_id("source-dialogue", "1930", "node-dialogue-top")
        rows[0]["corpusId"] = dialogue_id
        self._write_jsonl(workspace / "agent" / "eval.jsonl", rows)
        with self.assertRaisesRegex(BuildError, "不属于声明 corpus"):
            pack_workspace_v2(workspace, self.root / "dist-wrong-corpus", self.private_key_path)

    def test_pack_rejects_question_with_any_cross_shard_expected_evidence(self):
        rows = self._jsonl_rows("eval.jsonl")
        direct_id = install_planner._shard_id(
            "source-direct",
            "1926",
            "node-direct-top",
        )
        row = next(item for item in rows if item["corpusId"] == direct_id)
        row["expectedEvidence"].append("chunk-background")
        self._write_jsonl(self.workspace / "agent" / "eval.jsonl", rows)

        with self.assertRaisesRegex(BuildError, "不属于声明 corpus"):
            pack_workspace_v2(
                self.workspace,
                self.root / "dist-cross-shard-question",
                self.private_key_path,
            )

    def test_pack_requires_two_true_questions_for_required_and_recommended_corpora(self):
        rows = self._jsonl_rows("eval.jsonl")
        dialogue_id = install_planner._shard_id("source-dialogue", "1930", "node-dialogue-top")
        for row in rows:
            if row["corpusId"] == dialogue_id:
                row["corpusId"] = "core-evidence"
        self._write_jsonl(self.workspace / "agent" / "eval.jsonl", rows)

        with self.assertRaisesRegex(BuildError, "至少需要 2 道"):
            pack_workspace_v2(self.workspace, self.root / "dist-missing-corpus-questions", self.private_key_path)

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
            self.assertEqual(sorted(manifest["coverage"]), manifest["coverage"])
            self.assertTrue(manifest["coverage"])
            self.assertTrue(manifest["periods"])
            self.assertTrue(manifest["genres"])
            self.assertTrue(manifest["authorship"])

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
                payload_size = archive.getinfo(payload_names[0]).file_size
            self.assertEqual("person.fixture", manifest["agentId"])
            self.assertEqual(2, manifest["version"])
            self.assertEqual(1, len(payload_names))
            self.assertEqual(payload_size, manifest["rawSizeBytes"])

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

    def test_emit_sources_false_keeps_source_declarations_without_publishing_payloads(self):
        result = pack_workspace_v2(
            self.workspace,
            self.root / "dist-no-sources",
            self.private_key_path,
            emit_sources=False,
        )

        self.assertEqual([], result.source_packages)
        with zipfile.ZipFile(result.agent_package) as archive:
            plan = json.loads(archive.read("install-plan.json"))
        self.assertTrue(any(package["type"] == "hsource" for package in plan["packages"]))

    def test_source_profile_rejects_emit_sources_false_before_publishing_anything(self):
        output = self.root / "dist-source-without-sources"

        with self.assertRaisesRegex(BuildError, "source.*emit_sources"):
            pack_workspace_v2(
                self.workspace,
                output,
                self.private_key_path,
                profile_id="source",
                emit_sources=False,
            )

        self.assertFalse(output.exists())

        valid = pack_workspace_v2(
            self.workspace,
            self.root / "dist-source-explicit",
            self.private_key_path,
            profile_id="source",
            emit_sources=True,
        )
        report = json.loads(valid.report_path.read_text("utf-8"))
        with zipfile.ZipFile(valid.bundle_package) as archive:
            bundled_sources = [
                name for name in archive.namelist() if name.endswith(".hsource")
            ]
        self.assertTrue(report["bundleIncludesSources"])
        self.assertTrue(report["sourcesEmitted"])
        self.assertEqual(len(valid.source_packages), len(bundled_sources))

    def test_balanced_profile_does_not_materialize_source_packages(self):
        with mock.patch.object(
            builder,
            "_pack_source_v2",
            side_effect=AssertionError("balanced 不得物化 hsource"),
        ):
            result = pack_workspace_v2(
                self.workspace,
                self.root / "dist-balanced-bounded",
                self.private_key_path,
                profile_id="balanced",
                emit_sources=False,
            )

        self.assertTrue(result.bundle_package.is_file())
        self.assertEqual([], result.source_packages)

    def test_signed_source_descriptor_cache_avoids_repeat_measurement_and_matches_source_output(self):
        first = pack_workspace_v2(
            self.workspace,
            self.root / "dist-balanced-first",
            self.private_key_path,
            profile_id="balanced",
            emit_sources=False,
        )

        with mock.patch.object(
            builder,
            "_measure_source_v2",
            side_effect=AssertionError("相同 source/key/format 应复用 descriptor cache"),
        ):
            second = pack_workspace_v2(
                self.workspace,
                self.root / "dist-balanced-second",
                self.private_key_path,
                profile_id="balanced",
                emit_sources=False,
            )

        source = pack_workspace_v2(
            self.workspace,
            self.root / "dist-source-after-cache",
            self.private_key_path,
            profile_id="source",
            emit_sources=True,
        )
        with zipfile.ZipFile(first.agent_package) as archive:
            first_plan = json.loads(archive.read("install-plan.json"))
        with zipfile.ZipFile(second.agent_package) as archive:
            second_plan = json.loads(archive.read("install-plan.json"))
        with zipfile.ZipFile(source.agent_package) as archive:
            source_plan = json.loads(archive.read("install-plan.json"))

        self.assertEqual(first_plan, second_plan)
        self.assertEqual(first_plan, source_plan)
        declared = {
            row["fileName"]: row
            for row in first_plan["packages"]
            if row["type"] == "hsource"
        }
        self.assertEqual(set(declared), {path.name for path in source.source_packages})
        for path in source.source_packages:
            self.assertEqual(declared[path.name]["sha256"], self._sha256(path))
            self.assertEqual(declared[path.name]["sizeBytes"], path.stat().st_size)

        cache = json.loads(
            (
                self.workspace
                / builder.SOURCE_DESCRIPTOR_CACHE_DIRECTORY
                / builder.SOURCE_DESCRIPTOR_CACHE_FILE
            ).read_text("utf-8")
        )
        payloads = [entry["payload"] for entry in cache["entries"].values()]
        self.assertTrue(payloads)
        for payload in payloads:
            self.assertIn("pythonRuntime", payload)
            self.assertIn("zlibCompileVersion", payload)
            self.assertIn("zlibRuntimeVersion", payload)

    def test_source_pack_fails_closed_before_publish_when_cached_descriptor_mismatches(self):
        pack_workspace_v2(
            self.workspace,
            self.root / "dist-cache-prime",
            self.private_key_path,
            profile_id="balanced",
            emit_sources=False,
        )
        output = self.root / "dist-source-cache-mismatch"
        original_pack = builder._pack_source_v2

        def corrupt_after_pack(*args, **kwargs):
            original_pack(*args, **kwargs)
            target = args[4]
            with target.open("ab") as stream:
                stream.write(b"tampered")

        with (
            mock.patch.object(builder, "_pack_source_v2", side_effect=corrupt_after_pack),
            self.assertRaisesRegex(BuildError, "descriptor cache 不一致"),
        ):
            pack_workspace_v2(
                self.workspace,
                output,
                self.private_key_path,
                profile_id="source",
                emit_sources=True,
            )

        self.assertFalse(output.exists())

    def test_streaming_source_package_uses_readable_zip_data_descriptors(self):
        result = pack_workspace_v2(
            self.workspace,
            self.root / "dist-source-data-descriptor",
            self.private_key_path,
            profile_id="source",
            emit_sources=True,
        )

        for package in result.source_packages:
            with zipfile.ZipFile(package) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                payload = archive.read(f"files/{manifest['storedName']}")
                payload_info = archive.getinfo(f"files/{manifest['storedName']}")
            self.assertNotEqual(0, payload_info.flag_bits & 0x08)
            self.assertEqual(manifest["rawSizeBytes"], len(payload))
            self.assertEqual(manifest["sourceHash"], hashlib.sha256(payload).hexdigest())

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

    def test_cli_validate_dispatches_schema_and_retains_validate_v2_alias(self):
        for command in ("validate", "validate-v2"):
            with self.subTest(command=command), redirect_stdout(StringIO()):
                self.assertEqual(0, main([command, str(self.workspace)]))

        manifest_path = self.workspace / "workspace.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        manifest["schemaVersion"] = 99
        self._write_json(manifest_path, manifest)
        with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
            self.assertNotEqual(0, main(["validate", str(self.workspace)]))

    def test_cli_schema_dispatch_rejects_non_object_and_non_integer_without_traceback(self):
        manifest_path = self.workspace / "workspace.json"
        for raw in ("[]", '"workspace"', '{"schemaVersion":2.0}'):
            with self.subTest(raw=raw):
                manifest_path.write_text(raw, encoding="utf-8")
                stderr = StringIO()
                with redirect_stdout(StringIO()), redirect_stderr(stderr):
                    self.assertNotEqual(0, main(["validate", str(self.workspace)]))
                self.assertIn("构建失败", stderr.getvalue())
                self.assertNotIn("Traceback", stderr.getvalue())
        manifest_path.write_bytes(b"\xff\xfe\x00")
        stderr = StringIO()
        with redirect_stdout(StringIO()), redirect_stderr(stderr):
            self.assertNotEqual(0, main(["validate", str(self.workspace)]))
        self.assertIn("构建失败", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())
        manifest_path.write_text(
            '{"schemaVersion":' + ("9" * 5000) + "}",
            encoding="utf-8",
        )
        stderr = StringIO()
        with redirect_stdout(StringIO()), redirect_stderr(stderr):
            self.assertNotEqual(0, main(["validate", str(self.workspace)]))
        self.assertIn("构建失败", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())
        loop_a = self.root / "workspace-loop-a"
        loop_b = self.root / "workspace-loop-b"
        loop_a.symlink_to(loop_b, target_is_directory=True)
        loop_b.symlink_to(loop_a, target_is_directory=True)
        for command in ("validate", "validate-v2"):
            with self.subTest(command=command):
                stderr = StringIO()
                with redirect_stdout(StringIO()), redirect_stderr(stderr):
                    self.assertNotEqual(0, main([command, str(loop_a)]))
                self.assertNotIn("Traceback", stderr.getvalue())
        outside = self.root / "outside-workspace.json"
        outside.write_text('{"schemaVersion":2}', encoding="utf-8")
        manifest_path.unlink()
        manifest_path.symlink_to(outside)
        stderr = StringIO()
        with redirect_stdout(StringIO()), redirect_stderr(stderr):
            self.assertNotEqual(0, main(["validate", str(self.workspace)]))
        self.assertIn("构建失败", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())
        manifest_path.unlink()
        with manifest_path.open("wb") as stream:
            stream.seek(4 * 1024 * 1024)
            stream.write(b"x")
        stderr = StringIO()
        with redirect_stdout(StringIO()), redirect_stderr(stderr):
            self.assertNotEqual(0, main(["validate", str(self.workspace)]))
        self.assertIn("构建失败", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())

    def test_cli_pack_dispatches_v2_profiles_and_source_alone_reads_originals(self):
        for profile in ("lite", "balanced", "complete", "source"):
            output = self.root / f"cli-{profile}"
            with (
                mock.patch(
                    "tools.agent_builder.cli.pack_workspace_v2",
                    wraps=pack_workspace_v2,
                ) as pack_v2,
                redirect_stdout(StringIO()),
                redirect_stderr(StringIO()),
            ):
                exit_code = main(
                    [
                        "pack",
                        str(self.workspace),
                        "--output",
                        str(output),
                        "--key",
                        str(self.private_key_path),
                        "--profile",
                        profile,
                    ]
                )

            self.assertEqual(0, exit_code)
            self.assertEqual(profile == "source", pack_v2.call_args.kwargs["emit_sources"])
            self.assertEqual(profile, pack_v2.call_args.kwargs["profile_id"])

    def test_cli_recommend_human_and_json_use_real_signed_artifact_bytes(self):
        actual_runtime = pack_workspace_v2(
            self.workspace,
            self.root / "actual-runtime",
            self.private_key_path,
            profile_id="balanced",
            emit_sources=False,
        )
        actual_source = pack_workspace_v2(
            self.workspace,
            self.root / "actual-source",
            self.private_key_path,
            profile_id="source",
            emit_sources=True,
        )
        actuals = {}
        for profile_id, result in (
            ("runtime", actual_runtime),
            ("source", actual_source),
        ):
            with zipfile.ZipFile(result.agent_package) as archive:
                plan = json.loads(archive.read("install-plan.json"))
            actuals[profile_id] = {
                "agentBytes": result.agent_package.stat().st_size,
                "agentSha256": self._sha256(result.agent_package),
                "packages": {row["id"]: row for row in plan["packages"]},
                "profiles": {row["id"]: row for row in plan["profiles"]},
            }
        temp_before = set(Path(tempfile.gettempdir()).glob(".harness-recommend-*"))

        human = StringIO()
        with redirect_stdout(human), redirect_stderr(StringIO()):
            self.assertEqual(
                0,
                main(
                    [
                        "recommend",
                        str(self.workspace),
                        "--key",
                        str(self.private_key_path),
                    ]
                ),
            )
        output = human.getvalue()
        self.assertEqual(1, output.count("推荐安装（默认）"))
        for label in ("轻量", "完整证据", "包含原文"):
            self.assertIn(label, output)
        for detail in ("资料类型", "时期", "体裁", "独特覆盖原因", "原文", "可立即运行"):
            self.assertIn(detail, output)
        for detail in ("本地精确预检", "耗时", "临时产物"):
            self.assertIn(detail, output)
        self.assertRegex(output, r"\d+ 字节")

        machine = StringIO()
        with redirect_stdout(machine), redirect_stderr(StringIO()):
            self.assertEqual(
                0,
                main(
                    [
                        "recommend",
                        str(self.workspace),
                        "--key",
                        str(self.private_key_path),
                        "--json",
                    ]
                ),
            )
        raw = machine.getvalue()
        parsed = json.loads(raw)
        self.assertEqual(
            raw,
            json.dumps(
                parsed,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n",
        )
        self.assertEqual("balanced", parsed["recommendedProfileId"])
        self.assertEqual(
            ["lite", "balanced", "complete", "source"],
            [profile["id"] for profile in parsed["profiles"]],
        )
        self.assertGreater(parsed["preflight"]["elapsedMilliseconds"], 0)
        self.assertGreater(parsed["preflight"]["temporaryArtifactBytes"], 0)
        self.assertGreater(parsed["preflight"]["sourceInputBytes"], 0)
        for profile in parsed["profiles"]:
            actual = actuals["source" if profile["id"] == "source" else "runtime"]
            selected = actual["profiles"][profile["id"]]["packageIds"]
            self.assertEqual(
                actual["agentBytes"]
                + sum(actual["packages"][package_id]["sizeBytes"] for package_id in selected),
                profile["exactSignedBytes"],
            )
            self.assertEqual(
                actual["agentBytes"],
                profile["agentPackage"]["exactSignedBytes"],
            )
            self.assertEqual(
                actual["agentSha256"],
                profile["agentPackage"]["sha256"],
            )
            self.assertEqual(
                [
                    (
                        actual["packages"][package_id]["fileName"],
                        actual["packages"][package_id]["sizeBytes"],
                    )
                    for package_id in selected
                ],
                [
                    (package["fileName"], package["exactSignedBytes"])
                    for package in profile["packages"]
                ],
            )
            self.assertNotIn("bundleBytes", profile)
            self.assertNotIn("核心证据覆盖时期", profile["periods"])
            self.assertNotIn("未单独扩展", profile["genres"])
        lite = next(profile for profile in parsed["profiles"] if profile["id"] == "lite")
        self.assertEqual(["1926", "1930"], lite["periods"])
        self.assertEqual(["conversation", "secondary", "speech"], lite["genres"])
        self.assertTrue({"direct", "secondary"} <= set(lite["evidenceTypes"]))
        self.assertEqual(
            temp_before,
            set(Path(tempfile.gettempdir()).glob(".harness-recommend-*")),
        )

    def test_recommend_and_pack_share_one_canonical_agent_plan_for_all_profiles(self):
        recommendation_result = recommendation.build_recommendation(
            self.workspace,
            self.private_key_path,
        )
        balanced = pack_workspace_v2(
            self.workspace,
            self.root / "canonical-balanced",
            self.private_key_path,
            profile_id="balanced",
            emit_sources=False,
        )
        complete = pack_workspace_v2(
            self.workspace,
            self.root / "canonical-complete",
            self.private_key_path,
            profile_id="complete",
            emit_sources=False,
        )
        source = pack_workspace_v2(
            self.workspace,
            self.root / "canonical-source",
            self.private_key_path,
            profile_id="source",
            emit_sources=True,
        )

        agent_hashes = {
            self._sha256(result.agent_package)
            for result in (balanced, complete, source)
        }
        recommendation_hashes = {
            profile["agentPackage"]["sha256"]
            for profile in recommendation_result["profiles"]
        }
        self.assertEqual(1, len(agent_hashes))
        self.assertEqual(agent_hashes, recommendation_hashes)

        plans = []
        for result in (balanced, complete, source):
            with zipfile.ZipFile(result.agent_package) as archive:
                plans.append(archive.read("install-plan.json"))
        self.assertEqual(plans[0], plans[1])
        self.assertEqual(plans[0], plans[2])
        canonical_plan = json.loads(plans[0])
        source_ids = {
            package["id"]
            for package in canonical_plan["packages"]
            if package["type"] == "hsource"
        }
        self.assertTrue(source_ids)

        with zipfile.ZipFile(complete.bundle_package) as archive:
            manifest = json.loads(archive.read("bundle-manifest.json"))
            self.assertTrue(source_ids.isdisjoint(manifest["selectedPackageIds"]))
            self.assertTrue(
                all(
                    f"packages/{package['fileName']}" not in archive.namelist()
                    for package in canonical_plan["packages"]
                    if package["id"] in source_ids
                )
            )

    def test_recommendation_uses_signed_snapshot_and_true_incremental_coverage(self):
        chunks_path = self.workspace / "corpora" / "index" / "chunks.jsonl"
        original_chunks = chunks_path.read_bytes()
        original_pack = recommendation.pack_workspace_v2

        def pack_then_mutate(*args, **kwargs):
            result = original_pack(*args, **kwargs)
            chunks = [
                json.loads(line)
                for line in original_chunks.decode("utf-8").splitlines()
                if line.strip()
            ]
            for chunk in chunks:
                chunk["genre"] = "fabricated-after-signing"
            self._write_jsonl(chunks_path, chunks)
            return result

        try:
            with mock.patch.object(
                recommendation,
                "pack_workspace_v2",
                side_effect=pack_then_mutate,
            ):
                result = recommendation.build_recommendation(
                    self.workspace,
                    self.private_key_path,
                )
        finally:
            chunks_path.write_bytes(original_chunks)

        self.assertNotIn(
            "fabricated-after-signing",
            {
                genre
                for profile in result["profiles"]
                for genre in profile["genres"]
            },
        )
        profiles = {profile["id"]: profile for profile in result["profiles"]}
        for profile_id in ("lite", "balanced", "complete"):
            self.assertEqual(
                sorted(profiles[profile_id]["incrementalCoverage"]),
                profiles[profile_id]["incrementalCoverage"],
            )
        balanced_reasons = " ".join(profiles["balanced"]["uniqueCoverageReasons"])
        balanced_delta = set(profiles["balanced"]["incrementalCoverage"])
        if profiles["balanced"]["periods"] == profiles["lite"]["periods"]:
            self.assertNotIn("时期覆盖", balanced_reasons)
        if profiles["balanced"]["genres"] == profiles["lite"]["genres"]:
            self.assertNotIn("体裁覆盖", balanced_reasons)
        if not any(item.startswith("period:") for item in balanced_delta):
            self.assertNotIn("时期覆盖", balanced_reasons)
        if not any(item.startswith("genre:") for item in balanced_delta):
            self.assertNotIn("体裁覆盖", balanced_reasons)

    def test_recommendation_source_bytes_come_from_signed_snapshot(self):
        actual = builder.load_workspace_v2(self.workspace)
        stale_sources = list(actual.sources)
        stale_sources[0] = replace(
            stale_sources[0],
            raw_size_bytes=stale_sources[0].raw_size_bytes + 999,
        )
        stale = replace(actual, sources=tuple(stale_sources))

        with mock.patch.object(
            recommendation,
            "load_workspace_v2",
            return_value=stale,
            create=True,
        ):
            result = recommendation.build_recommendation(
                self.workspace,
                self.private_key_path,
            )

        self.assertEqual(
            sum(source.raw_size_bytes for source in actual.sources),
            result["preflight"]["sourceInputBytes"],
        )
        self.assertNotEqual(
            sum(source.raw_size_bytes for source in stale.sources),
            result["preflight"]["sourceInputBytes"],
        )

    def test_recommendation_rejects_tampered_install_plan_before_parsing(self):
        original_pack = recommendation.pack_workspace_v2

        def pack_then_tamper(*args, **kwargs):
            result = original_pack(*args, **kwargs)
            replacement = result.agent_package.with_suffix(".tampered")
            with (
                zipfile.ZipFile(result.agent_package) as source,
                zipfile.ZipFile(
                    replacement,
                    "w",
                    compression=zipfile.ZIP_DEFLATED,
                    compresslevel=9,
                ) as target,
            ):
                for info in source.infolist():
                    payload = source.read(info)
                    if info.filename == "install-plan.json":
                        plan = json.loads(payload)
                        source_package = next(
                            row for row in plan["packages"] if row["type"] == "hsource"
                        )
                        source_package["fileName"] = "tampered-" + source_package["fileName"]
                        payload = json.dumps(
                            plan,
                            ensure_ascii=False,
                            sort_keys=True,
                            separators=(",", ":"),
                        ).encode("utf-8")
                    target.writestr(info, payload)
            replacement.replace(result.agent_package)
            return result

        with (
            mock.patch.object(
                recommendation,
                "pack_workspace_v2",
                side_effect=pack_then_tamper,
            ),
            self.assertRaisesRegex(BuildError, "哈希"),
        ):
            recommendation.build_recommendation(
                self.workspace,
                self.private_key_path,
            )

    def test_cli_recommend_requires_existing_publisher_key_without_partial_output(self):
        temp_before = set(Path(tempfile.gettempdir()).glob(".harness-recommend-*"))
        for key_args in ([], ["--key", str(self.root / "missing.pem")]):
            with self.subTest(key_args=key_args), redirect_stdout(StringIO()), redirect_stderr(StringIO()):
                self.assertNotEqual(
                    0,
                    main(["recommend", str(self.workspace), *key_args]),
                )
        self.assertEqual(
            temp_before,
            set(Path(tempfile.gettempdir()).glob(".harness-recommend-*")),
        )

    def test_cli_v2_pack_errors_do_not_publish_partial_output(self):
        output = self.root / "cli-failure"
        with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
            self.assertNotEqual(
                0,
                main(
                    [
                        "pack",
                        str(self.workspace),
                        "--output",
                        str(output),
                        "--key",
                        str(self.root / "missing.pem"),
                    ]
                ),
            )
        self.assertFalse(output.exists())

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

    def _workspace(self, name: str = "workspace") -> Path:
        workspace = self.root / name
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
        for source_id, period, top_id, chunk_id, question in (
            ("source-direct", "1926", "node-direct-top", "chunk-core", "coreterm"),
            ("source-secondary", "1926", "node-secondary-top", "chunk-secondary", "secondaryterm"),
            ("source-dialogue", "1930", "node-dialogue-top", "chunk-dialogue", "dialogueterm"),
        ):
            corpus_id = install_planner._shard_id(source_id, period, top_id)
            for index in range(2):
                eval_rows.append(
                    {
                        "id": f"corpus-{source_id}-{index}",
                        "category": "grounding",
                        "question": question,
                        "period": period,
                        "expectedEvidence": [chunk_id],
                        "corpusId": corpus_id,
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

    def _jsonl_rows(self, name: str, workspace: Path | None = None):
        root = workspace or self.workspace
        if name == "eval.jsonl":
            path = root / "agent" / name
        else:
            path = root / "corpora" / "index" / name
        return [json.loads(line) for line in path.read_text("utf-8").splitlines() if line.strip()]

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
