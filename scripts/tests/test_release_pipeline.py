import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def load_script(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, REPO_ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class PrepareReleaseTest(unittest.TestCase):
    def test_outputs_versioned_immutable_urls(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "app-debug.apk"
            apk.write_bytes(b"apk-payload")
            output = root / "release"
            subprocess.run(
                [
                    sys.executable,
                    "scripts/prepare_apk_release.py",
                    "--apk",
                    str(apk),
                    "--output-dir",
                    str(output),
                    "--public-base-url",
                    "https://example.com/harness-apk/test",
                    "--version-code",
                    "2000001",
                    "--version-name",
                    "0.2.0-debug",
                    "--artifact-name",
                    "app-debug.apk",
                ],
                cwd=REPO_ROOT,
                check=True,
            )

            manifest = json.loads((output / "update.json").read_text())
            self.assertEqual(
                "https://example.com/harness-apk/test/releases/2000001/app-debug.apk",
                manifest["apkUrl"],
            )
            self.assertTrue(
                all("/releases/2000001/chunks/" in url for url in manifest["apkChunks"]),
            )
            self.assertTrue((output / "releases/2000001/app-debug.apk").is_file())


class UploadOrderTest(unittest.TestCase):
    def test_manifest_is_always_last(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "update.json").write_text("{}")
            asset = root / "releases/2/chunks/app.part-000"
            asset.parent.mkdir(parents=True)
            asset.write_bytes(b"part")

            module = load_script("upload_to_oss", "scripts/upload_to_oss.py")
            ordered = module.ordered_release_files(root)

            self.assertEqual(root / "update.json", ordered[-1])
            self.assertEqual([asset, root / "update.json"], ordered)


if __name__ == "__main__":
    unittest.main()
