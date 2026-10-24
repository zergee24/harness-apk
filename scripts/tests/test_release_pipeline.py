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
    def test_invalid_compatibility_floor_keeps_existing_release(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "app.apk"
            apk.write_bytes(b"apk")
            output = root / "out"
            output.mkdir()
            marker = output / "update.json"
            marker.write_text("existing release")
            result = subprocess.run([
                sys.executable, "scripts/prepare_apk_release.py", "--apk", str(apk),
                "--output-dir", str(output), "--public-base-url", "https://example.com",
                "--version-code", "100", "--version-name", "test", "--min-supported-version-code", "101",
            ], cwd=REPO_ROOT, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("existing release", marker.read_text())


    def test_explicit_compatibility_floor_and_user_facing_notes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "app.apk"
            apk.write_bytes(b"apk")
            notes = root / "notes.txt"
            notes.write_text("修复连接状态\n改善横屏入口\n", encoding="utf-8")
            result = subprocess.run([
                sys.executable, "scripts/prepare_apk_release.py", "--apk", str(apk),
                "--output-dir", str(root / "out"), "--public-base-url", "https://example.com",
                "--version-code", "2000200", "--version-name", "0.5.0",
                "--min-supported-version-code", "2000186", "--release-notes-file", str(notes),
            ], cwd=REPO_ROOT, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            manifest = json.loads((root / "out/update.json").read_text())
            self.assertEqual(2000186, manifest["minSupportedVersionCode"])
            self.assertEqual(["修复连接状态", "改善横屏入口"], manifest["releaseNotes"])


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
            self.assertEqual(1, manifest["minSupportedVersionCode"])
            self.assertEqual(["更新至 0.2.0-debug"], manifest["releaseNotes"])


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


class VersionCodeResolutionTest(unittest.TestCase):
    def resolve(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "scripts/resolve_ci_version_code.py", *args],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )

    def test_test_channel_base_can_advance_past_manually_installed_builds(self):
        result = self.resolve(
            "--base-version-code", "2000000",
            "--run-number", "90",
            "--channel", "test",
            "--test-base", "2000004",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("2000094", result.stdout.strip())

    def test_explicit_version_code_still_wins_over_channel_base(self):
        result = self.resolve(
            "--base-version-code", "2000000",
            "--run-number", "90",
            "--channel", "test",
            "--test-base", "2000004",
            "--override", "3000001",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("3000001", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
