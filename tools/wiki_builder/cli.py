"""Command-line state machine for the desktop Wiki builder."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from .builder import prepare_workspace
from .enrichment import import_enrichment
from .history.concept_registry import install_shared_registry, validate_pair_registry
from .history.enrichment_jobs import create_jobs, merge_jobs, validate_job
from .history.history_profile import PROFILE_ID
from .history.rights import RightsConfirmation, verify_build_rights
from .history.source_inventory import (
    inventory_history_sources,
    load_source_lock,
    write_source_lock,
)
from .history.twenty_four_histories import (
    CONCEPT_NAMESPACE as TWENTY_FOUR_CONCEPT_NAMESPACE,
    PACKAGE_ID as TWENTY_FOUR_PACKAGE_ID,
    PACKAGE_TITLE as TWENTY_FOUR_PACKAGE_TITLE,
    PACKAGE_VERSION as TWENTY_FOUR_PACKAGE_VERSION,
    prepare_twenty_four_histories,
)
from .history.zizhi_tongjian import (
    CONCEPT_NAMESPACE as ZIZHI_CONCEPT_NAMESPACE,
    PACKAGE_ID as ZIZHI_PACKAGE_ID,
    PACKAGE_TITLE as ZIZHI_PACKAGE_TITLE,
    PACKAGE_VERSION as ZIZHI_PACKAGE_VERSION,
    prepare_zizhi_tongjian,
)
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

    history = commands.add_parser("history", help="构建二十四史与资治通鉴 profile")
    history_commands = history.add_subparsers(dest="history_command", required=True)
    inventory = history_commands.add_parser("inventory", help="锁定两个史书 Git 来源")
    inventory.add_argument("--twenty-four", required=True, type=Path)
    inventory.add_argument("--zizhi-tongjian", required=True, type=Path)
    inventory.add_argument("--output", required=True, type=Path)
    inventory.add_argument("--expected-lock", type=Path)

    rights = history_commands.add_parser("verify-rights", help="验证用户提供的权利记录")
    rights.add_argument("--lock", required=True, type=Path)
    rights.add_argument("--rights", required=True, type=Path)
    rights.add_argument("--distribution", action="store_true")
    rights.add_argument("--semantic-processing", action="store_true")

    prepare_twenty_four = history_commands.add_parser(
        "prepare-twenty-four",
        help="将锁定的二十四史古文转换为 Wiki 工作区",
    )
    prepare_twenty_four.add_argument("source", type=Path)
    prepare_twenty_four.add_argument("--lock", required=True, type=Path)
    prepare_twenty_four.add_argument("--rights", required=True, type=Path)
    prepare_twenty_four.add_argument("--wiki-id", required=True)
    prepare_twenty_four.add_argument("--title", required=True)
    prepare_twenty_four.add_argument("--version", required=True, type=int)
    prepare_twenty_four.add_argument("--concept-namespace", required=True)
    prepare_twenty_four.add_argument("--output", required=True, type=Path)

    prepare_zizhi = history_commands.add_parser(
        "prepare-zizhi-tongjian",
        help="将锁定的资治通鉴文白对照稿转换为古文 Wiki 工作区",
    )
    prepare_zizhi.add_argument("source", type=Path)
    prepare_zizhi.add_argument("--lock", required=True, type=Path)
    prepare_zizhi.add_argument("--rights", required=True, type=Path)
    prepare_zizhi.add_argument("--wiki-id", required=True)
    prepare_zizhi.add_argument("--title", required=True)
    prepare_zizhi.add_argument("--version", required=True, type=int)
    prepare_zizhi.add_argument("--concept-namespace", required=True)
    prepare_zizhi.add_argument("--output", required=True, type=Path)
    prepare_zizhi.add_argument("--include-translation", action="store_true")

    create_history_jobs = history_commands.add_parser(
        "create-jobs",
        help="创建或恢复有界的史书语义任务",
    )
    create_history_jobs.add_argument("workspace", type=Path)
    create_history_jobs.add_argument("--profile", default=PROFILE_ID)

    validate_history_job = history_commands.add_parser(
        "validate-job",
        help="验证一个 Agent 语义任务输出",
    )
    validate_history_job.add_argument("workspace", type=Path)
    validate_history_job.add_argument("job_id")

    merge_history_jobs = history_commands.add_parser(
        "merge-jobs",
        help="事务合并全部已验证语义任务",
    )
    merge_history_jobs.add_argument("workspace", type=Path)

    validate_pair = history_commands.add_parser(
        "validate-pair",
        help="安装或验证双 Wiki 共享概念注册表",
    )
    validate_pair.add_argument("left", type=Path)
    validate_pair.add_argument("right", type=Path)
    validate_pair.add_argument("--registry", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "history":
            if args.history_command == "inventory":
                expected = (
                    load_source_lock(args.expected_lock) if args.expected_lock else None
                )
                lock = inventory_history_sources(
                    args.twenty_four,
                    args.zizhi_tongjian,
                    expected_lock=expected,
                )
                output = write_source_lock(args.output, lock)
                print(output)
                return 0
            if args.history_command == "verify-rights":
                result = verify_build_rights(
                    RightsConfirmation.from_path(args.rights),
                    load_source_lock(args.lock),
                    distribution=args.distribution,
                    semantic_processing=args.semantic_processing,
                )
                print(
                    json.dumps(
                        result,
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                )
                return 0
            if args.history_command == "create-jobs":
                plan = create_jobs(args.workspace, profile=args.profile)
                print(
                    json.dumps(
                        asdict(plan),
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                )
                return 0
            if args.history_command == "validate-job":
                validation = validate_job(args.workspace, args.job_id)
                print(
                    json.dumps(
                        asdict(validation),
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                )
                return 0
            if args.history_command == "merge-jobs":
                stats = merge_jobs(args.workspace)
                print(
                    json.dumps(
                        asdict(stats),
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                )
                return 0
            if args.history_command == "validate-pair":
                result = (
                    install_shared_registry(
                        args.registry,
                        (args.left, args.right),
                    )
                    if args.registry
                    else validate_pair_registry(args.left, args.right)
                )
                print(
                    json.dumps(
                        result,
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                )
                return 0
            if args.history_command == "prepare-twenty-four":
                _require_history_identity(
                    args,
                    wiki_id=TWENTY_FOUR_PACKAGE_ID,
                    title=TWENTY_FOUR_PACKAGE_TITLE,
                    version=TWENTY_FOUR_PACKAGE_VERSION,
                    concept_namespace=TWENTY_FOUR_CONCEPT_NAMESPACE,
                )
            else:
                _require_history_identity(
                    args,
                    wiki_id=ZIZHI_PACKAGE_ID,
                    title=ZIZHI_PACKAGE_TITLE,
                    version=ZIZHI_PACKAGE_VERSION,
                    concept_namespace=ZIZHI_CONCEPT_NAMESPACE,
                )
            source_lock = load_source_lock(args.lock)
            verify_build_rights(
                RightsConfirmation.from_path(args.rights),
                source_lock,
            )
            if args.history_command == "prepare-twenty-four":
                workspace = prepare_twenty_four_histories(
                    args.source,
                    args.output,
                    source_lock,
                )
            else:
                workspace = prepare_zizhi_tongjian(
                    args.source,
                    args.output,
                    source_lock,
                    include_translation=args.include_translation,
                )
            print(workspace)
            return 0
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


def _require_history_identity(
    args: argparse.Namespace,
    *,
    wiki_id: str,
    title: str,
    version: int,
    concept_namespace: str,
) -> None:
    expected = {
        "wiki-id": wiki_id,
        "title": title,
        "version": version,
        "concept-namespace": concept_namespace,
    }
    actual = {
        "wiki-id": args.wiki_id,
        "title": args.title,
        "version": args.version,
        "concept-namespace": args.concept_namespace,
    }
    mismatches = [
        f"--{name}={actual[name]!r}，期望 {value!r}"
        for name, value in expected.items()
        if actual[name] != value
    ]
    if mismatches:
        raise BuildError("史书 profile 包身份不可改写：" + "；".join(mismatches))


if __name__ == "__main__":
    raise SystemExit(main())
