import argparse
import json
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

from .builder import BuildError, pack_workspace, prepare_workspace, validate_workspace


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="agent-builder", description="构建 Harness 可移植语料人格智能体")
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare", help="提取、分块并创建蒸馏工作区")
    prepare.add_argument("inputs", nargs="+", type=Path)
    prepare.add_argument("--agent-id", required=True)
    prepare.add_argument("--name", required=True)
    prepare.add_argument("--version", required=True, type=int)
    prepare.add_argument("--output", required=True, type=Path)

    validate = subparsers.add_parser("validate", help="验证引用、覆盖和检索门槛")
    validate.add_argument("workspace", type=Path)

    pack = subparsers.add_parser("pack", help="签名并输出 hagent/hcorpus/hsource/hbundle")
    pack.add_argument("workspace", type=Path)
    pack.add_argument("--output", required=True, type=Path)
    pack.add_argument("--key", type=Path)
    pack.add_argument("--include-sources", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "prepare":
            workspace = prepare_workspace(args.inputs, args.output, args.agent_id, args.name, args.version)
            print(workspace)
            return 0
        if args.command == "validate":
            report = validate_workspace(args.workspace)
            print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
            return 0 if report.publishable else 2
        key_path = args.key or args.workspace / "publisher-key.pem"
        if not key_path.exists():
            key_path.parent.mkdir(parents=True, exist_ok=True)
            key_path.write_bytes(
                Ed25519PrivateKey.generate().private_bytes(
                    Encoding.PEM,
                    PrivateFormat.PKCS8,
                    NoEncryption(),
                )
            )
            key_path.chmod(0o600)
            print(f"已生成发布者私钥：{key_path}", file=sys.stderr)
        result = pack_workspace(args.workspace, args.output, key_path, args.include_sources)
        print(result.bundle_package)
        return 0
    except BuildError as error:
        print(f"构建失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
