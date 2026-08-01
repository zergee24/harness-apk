import json
import tempfile
import unittest
import warnings
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest import mock

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
)

from tools.wiki_builder import packaging
from tools.wiki_builder.builder import pack_workspace
from tools.wiki_builder.cli import main
from tools.wiki_builder.models import BuildError
from tools.wiki_builder.packaging import inspect_package
from tools.wiki_builder.tests.helpers import write_fixture_enrichment


class WikiBuilderCliTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.source = self.root / "source.md"
        self.source.write_text(
            "# 原文\n\n司馬光論禮制。\n\n"
            "# 摘要证据\n\n庫藏記錄甲。\n\n"
            "# 别名证据\n\n君實在此。",
            encoding="utf-8",
        )
        self.workspace = self.root / "workspace"
        self.key = self.root / "publisher.pem"
        self.key.write_bytes(
            Ed25519PrivateKey.from_private_bytes(bytes(range(32))).private_bytes(
                Encoding.PEM,
                PrivateFormat.PKCS8,
                NoEncryption(),
            )
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_prepare_enrich_validate_pack_and_inspect_flow(self):
        self.assertEqual(0, self._prepare())
        write_fixture_enrichment(self.workspace)
        self.assertEqual(0, main(["enrich", str(self.workspace)]))
        self.assertEqual(0, main(["validate", str(self.workspace)]))

        dist = self.root / "dist"
        self.assertEqual(
            0,
            main(
                [
                    "pack",
                    str(self.workspace),
                    "--output",
                    str(dist),
                    "--key",
                    str(self.key),
                ]
            ),
        )
        package = dist / "fixture.history-v1.hwiki"
        self.assertTrue(package.is_file())
        self.assertTrue((self.workspace / "reports" / "build-report.json").is_file())
        self.assertTrue((self.workspace / "reports" / "build-report.md").is_file())
        self.assertEqual({package.name}, {path.name for path in dist.iterdir()})
        self.assertEqual(0, main(["inspect", str(package)]))

        with zipfile.ZipFile(package) as archive:
            self.assertEqual(
                {"manifest.json", "content.sqlite", "checksums.json", "signature.json"},
                set(archive.namelist()),
            )
            manifest = json.loads(archive.read("manifest.json"))
        self.assertEqual("hwiki", manifest["type"])
        self.assertEqual("fixture.history", manifest["wiki"]["id"])
        self.assertEqual("none", manifest["capabilities"]["generatedPages"])

    def test_pack_is_deterministic_and_reports_exact_artifact(self):
        self._prepare()
        write_fixture_enrichment(self.workspace)
        main(["enrich", str(self.workspace)])

        first = pack_workspace(self.workspace, self.root / "first", self.key)
        first_package = first.package.read_bytes()
        first_report_json = first.report_json.read_bytes()
        first_report_markdown = first.report_markdown.read_bytes()
        second = pack_workspace(self.workspace, self.root / "second", self.key)

        self.assertEqual(first_package, second.package.read_bytes())
        self.assertEqual(first_report_json, second.report_json.read_bytes())
        self.assertEqual(first_report_markdown, second.report_markdown.read_bytes())
        report = json.loads(first.report_json.read_bytes())
        self.assertEqual(first.package.stat().st_size, report["artifact"]["sizeBytes"])
        self.assertEqual("fixture.history-v1.hwiki", report["artifact"]["fileName"])

    def test_pack_refuses_nonpublishable_or_unsafe_output(self):
        self._prepare()
        with self.assertRaisesRegex(BuildError, "validate 未通过"):
            pack_workspace(self.workspace, self.root / "bad-dist", self.key)
        self.assertFalse((self.root / "bad-dist").exists())

        write_fixture_enrichment(self.workspace)
        main(["enrich", str(self.workspace)])
        occupied = self.root / "occupied"
        occupied.mkdir()
        (occupied / "keep").write_text("keep", encoding="utf-8")
        result = pack_workspace(self.workspace, occupied, self.key)
        self.assertEqual("keep", (occupied / "keep").read_text(encoding="utf-8"))
        self.assertEqual(occupied / "fixture.history-v1.hwiki", result.package)

        not_a_directory = self.root / "not-a-directory"
        not_a_directory.write_text("occupied", encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "不是目录"):
            pack_workspace(self.workspace, not_a_directory, self.key)

    def test_pack_publishes_two_workspaces_into_shared_destination_in_either_order(self):
        self._prepare()
        write_fixture_enrichment(self.workspace)
        main(["enrich", str(self.workspace)])
        second_workspace = self.root / "second-workspace"
        self._prepare_workspace(
            second_workspace,
            wiki_id="fixture.zizhi",
            title="资治测试库",
        )
        write_fixture_enrichment(second_workspace)
        main(["enrich", str(second_workspace)])

        orders = (
            (self.workspace, second_workspace),
            (second_workspace, self.workspace),
        )
        for index, workspaces in enumerate(orders):
            with self.subTest(order=index):
                destination = self.root / f"shared-wikis-{index}"
                destination.mkdir()
                unrelated = destination / "keep.txt"
                unrelated.write_text("keep", encoding="utf-8")
                results = [
                    pack_workspace(workspace, destination, self.key)
                    for workspace in workspaces
                ]

                self.assertEqual(
                    {
                        "fixture.history-v1.hwiki",
                        "fixture.zizhi-v1.hwiki",
                        "keep.txt",
                    },
                    {path.name for path in destination.iterdir()},
                )
                self.assertEqual("keep", unrelated.read_text(encoding="utf-8"))
                self.assertEqual(2, len({result.package for result in results}))
                for workspace in workspaces:
                    self.assertTrue(
                        (workspace / "reports" / "build-report.json").is_file()
                    )
                    self.assertTrue(
                        (workspace / "reports" / "build-report.md").is_file()
                    )

    def test_pack_rejects_same_package_without_touching_existing_destination(self):
        self._prepare()
        write_fixture_enrichment(self.workspace)
        main(["enrich", str(self.workspace)])
        destination = self.root / "shared-wikis"
        destination.mkdir()
        unrelated = destination / "keep.txt"
        unrelated.write_text("keep", encoding="utf-8")
        first = pack_workspace(self.workspace, destination, self.key)
        package_before = first.package.read_bytes()
        report_before = first.report_json.read_bytes()

        with self.assertRaisesRegex(BuildError, "已存在"):
            pack_workspace(self.workspace, destination, self.key)

        self.assertEqual(package_before, first.package.read_bytes())
        self.assertEqual(report_before, first.report_json.read_bytes())
        self.assertEqual("keep", unrelated.read_text(encoding="utf-8"))

    def test_atomic_file_publish_is_create_only_and_leaves_complete_commit_marker(self):
        destination = self.root / "shared-wikis"
        destination.mkdir()
        staged = self.root / "staged.hwiki"
        staged.write_bytes(b"complete-package")
        final = destination / "fixture.history-v1.hwiki"

        with mock.patch.object(
            packaging.os,
            "link",
            side_effect=OSError("injected-before-link"),
        ):
            with self.assertRaisesRegex(BuildError, "原子发布失败"):
                packaging._publish_file_no_replace(staged, final)
        self.assertFalse(final.exists())
        self.assertEqual(b"complete-package", staged.read_bytes())

        packaging._publish_file_no_replace(staged, final)
        self.assertEqual(b"complete-package", final.read_bytes())
        self.assertEqual(b"complete-package", staged.read_bytes())

        replacement = self.root / "replacement.hwiki"
        replacement.write_bytes(b"replacement")
        with self.assertRaisesRegex(BuildError, "已存在"):
            packaging._publish_file_no_replace(replacement, final)
        self.assertEqual(b"complete-package", final.read_bytes())

    def test_atomic_file_publish_has_exactly_one_winner_under_race(self):
        destination = self.root / "shared-wikis"
        destination.mkdir()
        sources = (self.root / "first.hwiki", self.root / "second.hwiki")
        sources[0].write_bytes(b"first")
        sources[1].write_bytes(b"second")
        final = destination / "fixture.history-v1.hwiki"

        def publish(source):
            try:
                packaging._publish_file_no_replace(source, final)
            except BuildError as error:
                return str(error)
            return "published"

        with ThreadPoolExecutor(max_workers=2) as executor:
            outcomes = tuple(executor.map(publish, sources))

        self.assertEqual(1, outcomes.count("published"))
        self.assertEqual(1, sum("已存在" in outcome for outcome in outcomes))
        self.assertIn(final.read_bytes(), {b"first", b"second"})

    def test_atomic_file_publish_durably_syncs_payload_before_commit_marker(self):
        destination = self.root / "shared-wikis"
        destination.mkdir()
        staged = self.root / "staged.hwiki"
        staged.write_bytes(b"complete-package")
        final = destination / "fixture.history-v1.hwiki"
        events = []
        real_link = packaging.os.link

        def record_link(*args, **kwargs):
            events.append("link")
            return real_link(*args, **kwargs)

        def record_fsync(descriptor):
            mode = packaging.os.fstat(descriptor).st_mode
            events.append("fsync-file" if packaging.stat.S_ISREG(mode) else "fsync-dir")

        with mock.patch.object(packaging.os, "link", side_effect=record_link), mock.patch.object(
            packaging.os,
            "fsync",
            side_effect=record_fsync,
        ):
            packaging._publish_file_no_replace(staged, final)

        self.assertEqual(["fsync-file", "link", "fsync-dir"], events)

    def test_inspect_rejects_duplicate_or_tampered_entries(self):
        self._prepare()
        write_fixture_enrichment(self.workspace)
        main(["enrich", str(self.workspace)])
        result = pack_workspace(self.workspace, self.root / "dist", self.key)

        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(result.package, "a") as archive:
                archive.writestr("manifest.json", b"{}")
        with self.assertRaisesRegex(BuildError, "条目|重复"):
            inspect_package(result.package)

    def test_cli_requires_existing_private_key_and_returns_two_for_quality_failure(self):
        self._prepare()
        self.assertEqual(2, main(["validate", str(self.workspace)]))
        self.assertEqual(
            1,
            main(
                [
                    "pack",
                    str(self.workspace),
                    "--output",
                    str(self.root / "dist"),
                    "--key",
                    str(self.root / "missing.pem"),
                ]
            ),
        )
        self.assertFalse((self.root / "missing.pem").exists())

    def _prepare(self) -> int:
        return self._prepare_workspace(
            self.workspace,
            wiki_id="fixture.history",
            title="史料测试库",
        )

    def _prepare_workspace(
        self,
        workspace: Path,
        *,
        wiki_id: str,
        title: str,
    ) -> int:
        return main(
            [
                "prepare",
                str(self.source),
                "--wiki-id",
                wiki_id,
                "--title",
                title,
                "--version",
                "1",
                "--concept-namespace",
                "fixture-v1",
                "--output",
                str(workspace),
            ]
        )


if __name__ == "__main__":
    unittest.main()
