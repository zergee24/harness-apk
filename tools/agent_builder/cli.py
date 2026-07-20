import argparse
import json
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

from .builder import (
    BuildError,
    pack_workspace,
    pack_workspace_v2,
    prepare_workspace,
    prepare_workspace_v2,
    read_workspace_schema_version,
    validate_workspace,
    validate_workspace_v2,
)
from .recommendation import build_recommendation, format_recommendation_summary
from .persona_benchmark import (
    load_benchmark_cases,
    load_benchmark_responses,
    load_blind_bundle,
    load_blind_choices,
    load_blind_responses,
    prepare_blind_bundle,
    publish_new_canonical_json,
    score_benchmark,
    score_blind_choices,
)


class _ProfileAction(argparse.Action):
    def __call__(self, parser, namespace, values, option_string=None):
        setattr(namespace, self.dest, values)
        setattr(namespace, "profile_explicit", True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="agent-builder", description="构建 Harness 可移植语料人格智能体")
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare", help="提取、分块并创建蒸馏工作区")
    prepare.add_argument("inputs", nargs="+", type=Path)
    prepare.add_argument("--agent-id", required=True)
    prepare.add_argument("--name", required=True)
    prepare.add_argument("--version", required=True, type=int)
    prepare.add_argument("--output", required=True, type=Path)

    prepare_v2 = subparsers.add_parser("prepare-v2", help="创建 V2 人物资产工作区")
    prepare_v2.add_argument("inputs", nargs="+", type=Path)
    prepare_v2.add_argument("--agent-id", required=True)
    prepare_v2.add_argument("--name", required=True)
    prepare_v2.add_argument("--version", required=True, type=int)
    prepare_v2.add_argument("--output", required=True, type=Path)
    prepare_v2.add_argument("--source-catalog", type=Path)

    validate = subparsers.add_parser("validate", help="验证引用、覆盖和检索门槛")
    validate.add_argument("workspace", type=Path)

    validate_v2 = subparsers.add_parser("validate-v2", help="验证 V2 人物资产工作区")
    validate_v2.add_argument("workspace", type=Path)

    recommend = subparsers.add_parser("recommend", help="生成四种安装方案的准确签名体积")
    recommend.add_argument("workspace", type=Path)
    recommend.add_argument("--key", type=Path)
    recommend.add_argument("--json", action="store_true")

    pack = subparsers.add_parser("pack", help="签名并输出 hagent/hcorpus/hsource/hbundle")
    pack.add_argument("workspace", type=Path)
    pack.add_argument("--output", required=True, type=Path)
    pack.add_argument("--key", type=Path)
    pack.add_argument("--include-sources", action="store_true")
    pack.add_argument(
        "--profile",
        choices=("lite", "balanced", "complete", "source"),
        default="balanced",
        action=_ProfileAction,
    )
    pack.set_defaults(profile_explicit=False)

    benchmark_score = subparsers.add_parser(
        "benchmark-score",
        help="离线评分人格回归响应",
    )
    benchmark_score.add_argument("cases", type=Path)
    benchmark_score.add_argument("--responses", required=True, type=Path)
    benchmark_score.add_argument("--output", type=Path)

    benchmark_blind = subparsers.add_parser(
        "benchmark-blind",
        help="准备或评分隐藏版本映射的盲测",
    )
    blind_subparsers = benchmark_blind.add_subparsers(
        dest="blind_command",
        required=True,
    )
    blind_prepare = blind_subparsers.add_parser(
        "prepare",
        help="生成公开 A/B 对和私有 answer key",
    )
    blind_prepare.add_argument("--v1", required=True, type=Path)
    blind_prepare.add_argument("--v2", required=True, type=Path)
    blind_prepare.add_argument("--output", required=True, type=Path)
    blind_prepare.add_argument("--seed", type=int, default=20260718)

    blind_score = blind_subparsers.add_parser(
        "score",
        help="使用私有 answer key 汇总人工 choices",
    )
    blind_score.add_argument("--pairs", required=True, type=Path)
    blind_score.add_argument("--answer-key", required=True, type=Path)
    blind_score.add_argument("--choices", required=True, type=Path)
    blind_score.add_argument("--output", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "prepare":
            workspace = prepare_workspace(args.inputs, args.output, args.agent_id, args.name, args.version)
            print(workspace)
            return 0
        if args.command == "prepare-v2":
            workspace = prepare_workspace_v2(
                args.inputs,
                args.output,
                args.agent_id,
                args.name,
                args.version,
                args.source_catalog,
            )
            print(workspace)
            return 0
        if args.command == "validate":
            report = (
                validate_workspace_v2(args.workspace)
                if _workspace_schema_version(args.workspace) == 2
                else validate_workspace(args.workspace)
            )
            print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
            return 0 if report.publishable else 2
        if args.command == "validate-v2":
            report = validate_workspace_v2(args.workspace)
            print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
            return 0 if report.publishable else 2
        if args.command == "recommend":
            if _workspace_schema_version(args.workspace) != 2:
                raise BuildError("recommend 仅支持 schemaVersion 2 工作区")
            if args.key is None or not args.key.is_file():
                raise BuildError("recommend 必须提供已存在的 publisher --key")
            recommendation = build_recommendation(args.workspace, args.key)
            if args.json:
                print(
                    json.dumps(
                        recommendation,
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                )
            else:
                print(format_recommendation_summary(recommendation))
            return 0
        if args.command == "benchmark-score":
            report = score_benchmark(
                load_benchmark_cases(args.cases),
                load_benchmark_responses(args.responses),
            )
            if args.output is None:
                print(
                    json.dumps(
                        report.to_dict(),
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                )
            else:
                publish_new_canonical_json(args.output, report.to_dict())
                print(args.output)
            return 0 if report.passed else 2
        if args.command == "benchmark-blind":
            if args.blind_command == "prepare":
                bundle = prepare_blind_bundle(
                    load_blind_responses(args.v1),
                    load_blind_responses(args.v2),
                    args.output,
                    seed=args.seed,
                )
                print(bundle.output_dir)
                return 0
            pairs = load_blind_bundle(args.pairs, args.answer_key)
            report = score_blind_choices(
                pairs,
                load_blind_choices(args.choices),
            )
            publish_new_canonical_json(args.output, report.to_dict())
            print(args.output)
            return 0 if report.passed else 2
        schema_version = _workspace_schema_version(args.workspace)
        if schema_version == 2:
            if args.include_sources:
                raise BuildError("V2 不接受 V1 --include-sources；请使用 --profile source")
            if args.key is None or not args.key.is_file():
                raise BuildError("V2 pack 必须提供已存在的 --key")
            result = pack_workspace_v2(
                args.workspace,
                args.output,
                args.key,
                profile_id=args.profile,
                emit_sources=args.profile == "source",
            )
            print(result.bundle_package)
            return 0
        if args.profile_explicit:
            raise BuildError("V1 pack 不接受 V2 --profile")
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


def _workspace_schema_version(workspace: Path) -> int:
    return read_workspace_schema_version(workspace)


if __name__ == "__main__":
    raise SystemExit(main())
