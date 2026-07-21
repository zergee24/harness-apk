"""Generate the checked Android-facing signed Wiki fixture."""

from __future__ import annotations

import argparse
import shutil
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
)

from tools.wiki_builder.builder import prepare_workspace
from tools.wiki_builder.enrichment import import_enrichment
from tools.wiki_builder.models import BuildError
from tools.wiki_builder.packaging import pack_workspace
from tools.wiki_builder.tests.helpers import write_fixture_enrichment


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="生成签名 hwiki 测试 fixture")
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--reset", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    output = args.output
    if output.is_symlink():
        raise BuildError("fixture 输出不能是符号链接")
    if output.exists():
        if not args.reset:
            raise BuildError("fixture 输出已存在；测试重建需显式 --reset")
        if output.is_dir():
            shutil.rmtree(output)
        else:
            output.unlink()

    with tempfile.TemporaryDirectory(prefix="harness-hwiki-fixture-") as temp:
        root = Path(temp)
        workspace = prepare_workspace(
            [args.source],
            root / "workspace",
            "fixture.history",
            "史料测试库",
            1,
            "fixture-v1",
        )
        write_fixture_enrichment(workspace)
        import_enrichment(workspace)
        key_path = root / "publisher.pem"
        key_path.write_bytes(
            Ed25519PrivateKey.from_private_bytes(bytes(range(32))).private_bytes(
                Encoding.PEM,
                PrivateFormat.PKCS8,
                NoEncryption(),
            )
        )
        result = pack_workspace(workspace, output, key_path)
    print(result.package)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
