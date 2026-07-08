#!/usr/bin/env python3
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "release_apk.sh"


class ReleaseSigningEnvTest(unittest.TestCase):
    """Checks release scripts fail before building when fixed signing is missing."""

    def run_script(self, channel: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            android_home = os.environ.get("ANDROID_HOME") or "/Users/tony/Library/Android/sdk"
            env = {
                "HOME": temp_dir,
                "PATH": os.environ["PATH"],
                "ANDROID_HOME": android_home,
                "ANDROID_SDK_ROOT": os.environ.get("ANDROID_SDK_ROOT", android_home),
            }
            return subprocess.run(
                [str(SCRIPT), channel, "--upload"],
                cwd=ROOT,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=20,
            )

    def test_test_upload_requires_fixed_signing(self) -> None:
        result = self.run_script("test")

        self.assertEqual(result.returncode, 64)
        self.assertIn("ANDROID_TEST_STORE_FILE", result.stderr)
        self.assertNotIn("> Task :app:", result.stdout)

    def test_prod_upload_requires_fixed_signing(self) -> None:
        result = self.run_script("prod")

        self.assertEqual(result.returncode, 64)
        self.assertIn("ANDROID_RELEASE_STORE_FILE", result.stderr)
        self.assertNotIn("> Task :app:", result.stdout)


if __name__ == "__main__":
    unittest.main()
