from __future__ import annotations

import json
import tempfile
import zipfile
from pathlib import Path
from typing import Any

from .builder import load_workspace_v2, pack_workspace_v2
from .install_planner import CorpusPlanIndex
from .models import BuildError, InstallPackage, InstallPlan, InstallProfile


PROFILE_LABELS = {
    "lite": "轻量",
    "balanced": "推荐安装（默认）",
    "complete": "完整证据",
    "source": "包含原文",
}
PROFILE_ORDER = ("lite", "balanced", "complete", "source")
HUMAN_PROFILE_ORDER = ("balanced", "lite", "complete", "source")
EVIDENCE_LABELS = {
    "identity": "身份",
    "stance": "立场",
    "voice": "表达",
    "relationship": "关系",
    "episode": "经历",
    "eval": "评测",
}


def build_recommendation(workspace: Path, key_path: Path) -> dict[str, Any]:
    workspace = Path(workspace).expanduser().resolve()
    key_path = Path(key_path).expanduser().resolve()
    manifest = load_workspace_v2(workspace)
    with tempfile.TemporaryDirectory(prefix=".harness-recommend-") as temporary:
        root = Path(temporary)
        result = pack_workspace_v2(
            workspace,
            root / "signed",
            key_path,
            profile_id="source",
            emit_sources=True,
        )
        plan = _read_install_plan(result.agent_package)
        with CorpusPlanIndex(workspace) as planner:
            if planner.manifest != manifest:
                raise BuildError("workspace manifest 在精确预检期间发生变化")
            shard_values = planner.shards(materialize_ids=False)
            shards = {shard.package_id: shard for shard in shard_values}
            planned_corpora = {
                package.package_id
                for package in plan.packages
                if package.package_type == "hcorpus"
            }
            if set(shards) != planned_corpora:
                raise BuildError("签名安装计划与当前语料分片不一致")
            metadata = {
                shard.package_id: _shard_metadata(planner, shard)
                for shard in shard_values
            }
        source_bundle_name = result.bundle_package.name
        if not source_bundle_name.endswith("-source.hbundle"):
            raise BuildError("source 预检产物名称无效")
        bundle_prefix = source_bundle_name.removesuffix("-source.hbundle")
        agent_bytes = result.agent_package.stat().st_size
        profiles = [
            _profile_summary(
                profile_id,
                plan,
                f"{bundle_prefix}-{profile_id}.hbundle",
                result.agent_package.name,
                agent_bytes,
                shards,
                metadata,
                manifest,
            )
            for profile_id in PROFILE_ORDER
        ]
        return {
            "agentId": manifest.agent_id,
            "recommendedProfileId": "balanced",
            "schemaVersion": 2,
            "version": manifest.version,
            "profiles": profiles,
        }


def format_recommendation_summary(recommendation: dict[str, Any]) -> str:
    profiles = {row["id"]: row for row in recommendation["profiles"]}
    blocks = []
    for profile_id in HUMAN_PROFILE_ORDER:
        row = profiles[profile_id]
        package_text = "、".join(
            f"{package['fileName']}（{package['exactSignedBytes']} 字节）"
            for package in row["packages"]
        )
        blocks.append(
            "\n".join(
                (
                    f"{row['label']} [{profile_id}]",
                    f"Android 安装包：{row['bundleFileName']}",
                    f"准确已签名安装字节：{row['exactSignedBytes']} 字节",
                    (
                        "hagent："
                        f"{row['agentPackage']['fileName']}"
                        f"（{row['agentPackage']['exactSignedBytes']} 字节）"
                    ),
                    f"子包：{package_text}",
                    f"资料类型：{_join(row['evidenceTypes'])}",
                    f"时期：{_join(row['periods'])}",
                    f"体裁：{_join(row['genres'])}",
                    f"独特覆盖原因：{_join(row['uniqueCoverageReasons'])}",
                    f"原文：{'包含，仅供阅读核验' if row['includesOriginals'] else '不包含'}",
                    f"可立即运行：{'是' if row['runnableImmediately'] else '否，资料不完整'}",
                )
            )
        )
    return "\n\n".join(blocks)


def _profile_summary(
    profile_id: str,
    plan: InstallPlan,
    bundle_file_name: str,
    agent_file_name: str,
    agent_bytes: int,
    shards: dict[str, Any],
    metadata: dict[str, dict[str, tuple[str, ...]]],
    manifest: Any,
) -> dict[str, Any]:
    profile = plan.profile(profile_id)
    packages = [_package(plan, package_id) for package_id in profile.package_ids]
    selected_shards = [
        shards[package_id]
        for package_id in profile.package_ids
        if package_id in shards
    ]
    coverage = (
        set().union(*(shard.coverage for shard in selected_shards))
        if selected_shards
        else set()
    )
    selected_metadata = [
        metadata[package_id]
        for package_id in profile.package_ids
        if package_id in metadata
    ]
    periods = sorted({
        value for row in selected_metadata for value in row["periods"]
    })
    genres = sorted({
        value for row in selected_metadata for value in row["genres"]
    })
    authorship = sorted({
        value for row in selected_metadata for value in row["authorship"]
    })
    evidence_types = sorted({
        EVIDENCE_LABELS[prefix]
        for feature in coverage
        for prefix in (feature.partition(":")[0],)
        if prefix in EVIDENCE_LABELS
    })
    evidence_types.extend(value for value in authorship if value not in evidence_types)
    if profile_id == "source":
        evidence_types.append("原始文件")
    reasons = _coverage_reasons(profile_id, coverage, periods, genres)
    known_sources = {source.source_id for source in manifest.sources}
    source_package_ids = {
        package.package_id.removeprefix("source-")
        for package in packages
        if package.package_type == "hsource"
    }
    if profile_id == "source" and source_package_ids != known_sources:
        raise BuildError("source profile 未包含全部原文包")
    return {
        "agentPackage": {
            "exactSignedBytes": agent_bytes,
            "fileName": agent_file_name,
            "type": "hagent",
        },
        "bundleFileName": bundle_file_name,
        "evidenceTypes": evidence_types or ["核心证据"],
        "exactSignedBytes": agent_bytes + sum(_package_bytes(package) for package in packages),
        "genres": genres or ["未单独扩展"],
        "id": profile_id,
        "includesOriginals": profile_id == "source",
        "label": PROFILE_LABELS[profile_id],
        "packages": [
            {
                "exactSignedBytes": package.size_bytes,
                "fileName": package.file_name,
                "id": package.package_id,
                "type": package.package_type,
            }
            for package in packages
        ],
        "periods": periods or ["核心证据覆盖时期"],
        "runnableImmediately": all(
            required in profile.package_ids for required in plan.required_corpus_ids
        ),
        "uniqueCoverageReasons": reasons,
    }


def _coverage_reasons(
    profile_id: str,
    coverage: set[str],
    periods: list[str],
    genres: list[str],
) -> list[str]:
    if profile_id == "lite":
        return ["核心身份、立场、关系与评测引用证据"]
    if profile_id == "source":
        return ["完整证据外附带本地原文，原文仅供阅读核验且不参与回答"]
    reasons = []
    if any(feature.startswith("voice:direct-material:") for feature in coverage):
        reasons.append("直接对话或直接作者材料")
    if "relationship" in {feature.partition(":")[0] for feature in coverage}:
        reasons.append("关系证据")
    if periods:
        reasons.append("时期覆盖：" + "、".join(periods))
    if genres:
        reasons.append("体裁覆盖：" + "、".join(genres))
    if any(feature.startswith("eval:") for feature in coverage):
        reasons.append("独特评测覆盖")
    if profile_id == "complete":
        reasons.append("全部可用证据分片")
    return reasons or ["核心证据之外的独特覆盖增益"]


def _read_install_plan(agent_package: Path) -> InstallPlan:
    try:
        with zipfile.ZipFile(agent_package) as archive:
            raw = json.loads(archive.read("install-plan.json"))
        plan = InstallPlan(
            packages=tuple(
                InstallPackage(
                    package_id=row["id"],
                    package_type=row["type"],
                    file_name=row["fileName"],
                    install_class=row["installClass"],
                    dependencies=tuple(row["dependencies"]),
                    size_bytes=row["sizeBytes"],
                    sha256=row["sha256"],
                )
                for row in raw["packages"]
            ),
            profiles=tuple(
                InstallProfile(
                    profile_id=row["id"],
                    package_ids=tuple(row["packageIds"]),
                    recommended=row["recommended"],
                )
                for row in raw["profiles"]
            ),
            required_corpus_ids=tuple(raw["requiredCorpusIds"]),
            recommended_profile_id=raw["recommendedProfileId"],
        )
        plan.to_dict(require_artifacts=True)
        return plan
    except (KeyError, OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise BuildError(f"签名安装计划无法读取：{error}") from error


def _package(plan: InstallPlan, package_id: str) -> InstallPackage:
    package = next(
        (item for item in plan.packages if item.package_id == package_id),
        None,
    )
    if package is None:
        raise BuildError(f"安装计划引用未知包：{package_id}")
    return package


def _package_bytes(package: InstallPackage) -> int:
    if (
        isinstance(package.size_bytes, bool)
        or not isinstance(package.size_bytes, int)
        or package.size_bytes <= 0
    ):
        raise BuildError(f"安装包缺少真实签名字节：{package.package_id}")
    return package.size_bytes


def _shard_metadata(
    planner: CorpusPlanIndex,
    shard: Any,
) -> dict[str, tuple[str, ...]]:
    query, params = planner._selection_query(shard)
    rows = tuple(
        planner._connection().execute(
            f"""
            SELECT DISTINCT period, genre, authorship
            FROM chunks {query}
            ORDER BY period, genre, authorship
            """,
            params,
        )
    )
    if not rows:
        raise BuildError(f"安装分片没有真实资料元数据：{shard.package_id}")
    return {
        "periods": tuple(sorted({row["period"] for row in rows})),
        "genres": tuple(sorted({row["genre"] for row in rows})),
        "authorship": tuple(sorted({row["authorship"] for row in rows})),
    }


def _join(values: list[str]) -> str:
    return "、".join(values) if values else "无"
