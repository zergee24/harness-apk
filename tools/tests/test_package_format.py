import base64
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
)

from tools.package_format import (
    PackageFormatError,
    canonical_json_bytes,
    load_ed25519_private_key,
    measure_signed_package,
    write_signed_package,
    write_signed_package_streaming,
)
from tools.agent_builder.builder import (
    _measure_signed_package_v2,
    _write_signed_package_v2,
    _write_signed_package_v2_streaming,
)


class PackageFormatTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_signed_package_is_deterministic_and_verifiable(self):
        first = self.root / "first.hwiki"
        second = self.root / "second.hwiki"
        files = {
            "manifest.json": canonical_json_bytes(
                {"schemaVersion": 1, "type": "hwiki"}
            ),
            "content.sqlite": b"sqlite-fixture",
        }

        write_signed_package(first, files, self.key)
        write_signed_package(second, files, self.key)

        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            checksums_bytes = archive.read("checksums.json")
            checksums = json.loads(checksums_bytes)
            signature = json.loads(archive.read("signature.json"))
            self.key.public_key().verify(
                base64.b64decode(signature["signature"], validate=True),
                checksums_bytes,
            )
            self.assertEqual(
                hashlib.sha256(archive.read("content.sqlite")).hexdigest(),
                checksums["files"]["content.sqlite"],
            )
            for info in archive.infolist():
                self.assertEqual((2020, 1, 1, 0, 0, 0), info.date_time)
                self.assertEqual(3, info.create_system)
                self.assertEqual(0o100644, info.external_attr >> 16)

    def test_streaming_package_matches_measurement_and_signed_payloads(self):
        seekable = self.root / "seekable.hwiki"
        streamed = self.root / "streamed.hwiki"
        source = self.root / "content.sqlite"
        source.write_bytes(b"sqlite" * 1000)
        files = {
            "manifest.json": canonical_json_bytes({"type": "hwiki"}),
            "content.sqlite": source,
        }

        write_signed_package(seekable, files, self.key)
        write_signed_package_streaming(streamed, files, self.key)
        measured_hash, measured_size = measure_signed_package(files, self.key)

        self.assertEqual(hashlib.sha256(streamed.read_bytes()).hexdigest(), measured_hash)
        self.assertEqual(streamed.stat().st_size, measured_size)
        with zipfile.ZipFile(seekable) as first, zipfile.ZipFile(streamed) as second:
            self.assertEqual(first.namelist(), second.namelist())
            for name in first.namelist():
                self.assertEqual(first.read(name), second.read(name))

    def test_signed_package_rejects_reserved_or_unsafe_paths(self):
        for index, path in enumerate(
            (
                "checksums.json",
                "signature.json",
                "../escape",
                "/absolute",
                "a\\b",
                "a/./b",
                "a//b",
                "a/../b",
                "C:/absolute",
                "",
            )
        ):
            with self.subTest(path=path), self.assertRaises(PackageFormatError):
                write_signed_package(
                    self.root / f"unsafe-{index}.zip",
                    {path: b"x"},
                    self.key,
                )

    def test_expected_file_identity_is_checked_before_writing(self):
        source = self.root / "content.sqlite"
        source.write_bytes(b"sqlite")

        with self.assertRaisesRegex(PackageFormatError, "大小或哈希不匹配"):
            write_signed_package(
                self.root / "bad-identity.hwiki",
                {"content.sqlite": source},
                self.key,
                expected_files={"content.sqlite": ("0" * 64, source.stat().st_size)},
            )

        self.assertFalse((self.root / "bad-identity.hwiki").exists())

    def test_output_collision_preserves_existing_file(self):
        target = self.root / "existing.hwiki"
        target.write_bytes(b"keep-me")

        with self.assertRaises((FileExistsError, PackageFormatError)):
            write_signed_package(target, {"manifest.json": b"{}"}, self.key)

        self.assertEqual(b"keep-me", target.read_bytes())

    def test_private_key_loader_accepts_only_unencrypted_ed25519_pem(self):
        key_path = self.root / "publisher.pem"
        key_path.write_bytes(
            self.key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
        )

        loaded = load_ed25519_private_key(key_path)

        self.assertEqual(
            self.key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption()),
            loaded.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption()),
        )

    def test_shared_writer_matches_existing_agent_v2_containers(self):
        source = self.root / "source.bin"
        source.write_bytes(bytes(range(256)) * 16)
        files = {"a.json": b"{}", "nested/source.bin": source}
        expected = {
            "nested/source.bin": (
                hashlib.sha256(source.read_bytes()).hexdigest(),
                source.stat().st_size,
            )
        }

        old_seekable = _write_signed_package_v2(
            self.root / "old-seekable.zip",
            files,
            self.key,
            expected_files=expected,
        )
        new_seekable = write_signed_package(
            self.root / "new-seekable.zip",
            files,
            self.key,
            expected_files=expected,
        )
        old_streaming = _write_signed_package_v2_streaming(
            self.root / "old-streaming.zip",
            files,
            self.key,
            expected_files=expected,
        )
        new_streaming = write_signed_package_streaming(
            self.root / "new-streaming.zip",
            files,
            self.key,
            expected_files=expected,
        )

        self.assertEqual(old_seekable.read_bytes(), new_seekable.read_bytes())
        self.assertEqual(old_streaming.read_bytes(), new_streaming.read_bytes())
        self.assertEqual(
            _measure_signed_package_v2(files, self.key, expected_files=expected),
            measure_signed_package(files, self.key, expected_files=expected),
        )


if __name__ == "__main__":
    unittest.main()
