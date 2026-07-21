"""Command-line state machine for the desktop Wiki builder."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from .builder import prepare_workspace
from .enrichment import import_enrichment
from .models import BuildError
from .packaging import inspect_package, pack_workspace
from .validation import validate_workspace


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="wiki-builder",
        description="构建、验证并签名 Harness 离线 Wiki",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    prepare = commands.add_parser("prepare", help="提取原文并创建 Wiki 工作区")
    prepare.add_argument("inputs", nargs="+", type=Path)
    prepare.add_argument("--wiki-id", required=True)
    prepare.add_argument("--title", required=True)
    prepare.add_argument("--version", required=True, type=int)
    prepare.add_argument("--concept-namespace", required=True)
    prepare.add_argument("--output", required=True, type=Path)

    enrich = commands.add_parser("enrich", help="事务导入证据化语义资产")
    enrich.add_argument("workspace", type=Path)

    validate = commands.add_parser("validate", help="运行结构与检索发布闸门")
    validate.add_argument("workspace", type=Path)
    validate.add_argument("--eval", type=Path)

    pack = commands.add_parser("pack", help="签名并输出 .hwiki 与构建报告")
    pack.add_argument("workspace", type=Path)
    pack.add_argument("--output", required=True, type=Path)
    pack.add_argument("--key", required=True, type=Path)
    pack.add_argument("--eval", type=Path)

    inspect = commands.add_parser("inspect", help="独立检查签名 .hwiki")
    inspect.add_argument("package", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "prepare":
            workspace = prepare_workspace(
                args.inputs,
                args.output,
                args.wiki_id,
                args.title,
                args.version,
                args.concept_namespace,
            )
            print(workspace)
            return 0
        if args.command == "enrich":
            stats = import_enrichment(args.workspace)
            print(
                json.dumps(
                    asdict(stats),
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
            )
            return 0
        if args.command == "validate":
            report = validate_workspace(args.workspace, args.eval)
            print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
            return 0 if report.publishable else 2
        if args.command == "pack":
            if not args.key.is_file() or args.key.is_symlink():
                raise BuildError("pack 必须提供已存在的普通 Ed25519 --key")
            result = pack_workspace(
                args.workspace,
                args.output,
                args.key,
                args.eval,
            )
            print(result.package)
            return 0
        inspection = inspect_package(args.package)
        print(json.dumps(inspection.to_dict(), ensure_ascii=False, indent=2))
        return 0
    except (BuildError, FileExistsError) as error:
        print(f"构建失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
