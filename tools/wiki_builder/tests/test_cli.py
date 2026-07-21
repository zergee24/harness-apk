import json
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
)

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
        self.assertTrue((dist / "build-report.json").is_file())
        self.assertTrue((dist / "build-report.md").is_file())
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
        second = pack_workspace(self.workspace, self.root / "second", self.key)

        self.assertEqual(first.package.read_bytes(), second.package.read_bytes())
        self.assertEqual(first.report_json.read_bytes(), second.report_json.read_bytes())
        report = json.loads(first.report_json.read_bytes())
        self.assertEqual(first.package.stat().st_size, report["artifact"]["sizeBytes"])
        self.assertEqual("fixture.history-v1.hwiki", report["artifact"]["fileName"])

    def test_pack_refuses_nonpublishable_or_nonempty_output(self):
        self._prepare()
        with self.assertRaisesRegex(BuildError, "validate 未通过"):
            pack_workspace(self.workspace, self.root / "bad-dist", self.key)
        self.assertFalse((self.root / "bad-dist").exists())

        write_fixture_enrichment(self.workspace)
        main(["enrich", str(self.workspace)])
        occupied = self.root / "occupied"
        occupied.mkdir()
        (occupied / "keep").write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(BuildError, "非空"):
            pack_workspace(self.workspace, occupied, self.key)
        self.assertEqual("keep", (occupied / "keep").read_text(encoding="utf-8"))

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
        return main(
            [
                "prepare",
                str(self.source),
                "--wiki-id",
                "fixture.history",
                "--title",
                "史料测试库",
                "--version",
                "1",
                "--concept-namespace",
                "fixture-v1",
                "--output",
                str(self.workspace),
            ]
        )


if __name__ == "__main__":
    unittest.main()
