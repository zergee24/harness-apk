from __future__ import annotations

import hashlib
import json
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Any

from .builder import (
    pack_workspace_v2,
    read_verified_agent_snapshot_v2,
)
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
    started_at = time.monotonic_ns()
    with tempfile.TemporaryDirectory(prefix=".harness-recommend-") as temporary:
        root = Path(temporary)
        result = pack_workspace_v2(
            workspace,
            root / "signed",
            key_path,
            profile_id="balanced",
            emit_sources=False,
        )
        signed_agent_manifest, signed_plan = read_verified_agent_snapshot_v2(
            result.agent_package,
            key_path,
        )
        agent_id, version = _agent_identity(signed_agent_manifest)
        source_plan = _read_install_plan(signed_plan)
        metadata = _read_signed_corpus_metadata(
            result.corpus_packages,
            source_plan,
            agent_id,
            version,
        )
        source_metadata = _read_signed_source_metadata_from_corpora(
            result.corpus_packages,
            source_plan,
            agent_id,
            version,
        )
        snapshot_bundle_name = result.bundle_package.name
        if not snapshot_bundle_name.endswith("-balanced.hbundle"):
            raise BuildError("推荐预检产物名称无效")
        bundle_prefix = snapshot_bundle_name.removesuffix("-balanced.hbundle")
        canonical_agent_sha256, canonical_agent_bytes = _hash_regular_file(
            result.agent_package
        )
        profiles = []
        previous_coverage: set[str] = set()
        previous_package_ids: set[str] = set()
        for profile_id in PROFILE_ORDER:
            summary = _profile_summary(
                profile_id,
                source_plan,
                f"{bundle_prefix}-{profile_id}.hbundle",
                result.agent_package.name,
                canonical_agent_bytes,
                canonical_agent_sha256,
                metadata,
                set(source_metadata),
                previous_coverage,
                previous_package_ids,
            )
            profiles.append(summary)
            previous_coverage.update(summary["incrementalCoverage"])
            previous_package_ids.update(summary["incrementalPackageIds"])
        elapsed_milliseconds = max(
            1,
            (time.monotonic_ns() - started_at + 999_999) // 1_000_000,
        )
        return {
            "agentId": agent_id,
            "preflight": {
                "elapsedMilliseconds": elapsed_milliseconds,
                "mode": "local-exact-signed",
                "sourceInputBytes": sum(
                    row["rawSizeBytes"] for row in source_metadata.values()
                ),
                "temporaryArtifactBytes": _temporary_regular_bytes(root),
            },
            "recommendedProfileId": "balanced",
            "schemaVersion": 2,
            "version": version,
            "profiles": profiles,
        }


def format_recommendation_summary(recommendation: dict[str, Any]) -> str:
    profiles = {row["id"]: row for row in recommendation["profiles"]}
    preflight = recommendation["preflight"]
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
    preflight_summary = (
        "本地精确预检："
        f"耗时 {preflight['elapsedMilliseconds']} 毫秒；"
        f"临时产物 {preflight['temporaryArtifactBytes']} 字节；"
        f"原始资料 {preflight['sourceInputBytes']} 字节。"
    )
    return preflight_summary + "\n\n" + "\n\n".join(blocks)


def _profile_summary(
    profile_id: str,
    plan: InstallPlan,
    bundle_file_name: str,
    agent_file_name: str,
    agent_bytes: int,
    agent_sha256: str,
    metadata: dict[str, dict[str, frozenset[str] | tuple[str, ...]]],
    known_source_ids: set[str],
    previous_coverage: set[str],
    previous_package_ids: set[str],
) -> dict[str, Any]:
    profile = plan.profile(profile_id)
    packages = [_package(plan, package_id) for package_id in profile.package_ids]
    selected_metadata = [
        metadata[package_id]
        for package_id in profile.package_ids
        if package_id in metadata
    ]
    coverage = (
        set().union(*(row["coverage"] for row in selected_metadata))
        if selected_metadata
        else set()
    )
    periods = sorted({
        value for row in selected_metadata for value in row["periods"]
    })
    genres = sorted({
        value for row in selected_metadata for value in row["genres"]
    })
    authorship = sorted({
        value for row in selected_metadata for value in row["authorship"]
    })
    coverage.update(f"period:{value}" for value in periods)
    coverage.update(f"genre:{value}" for value in genres)
    coverage.update(f"authorship:{value}" for value in authorship)
    incremental_coverage = coverage - previous_coverage
    incremental_package_ids = [
        package_id
        for package_id in profile.package_ids
        if package_id not in previous_package_ids
    ]
    evidence_types = sorted({
        EVIDENCE_LABELS[prefix]
        for feature in coverage
        for prefix in (feature.partition(":")[0],)
        if prefix in EVIDENCE_LABELS
    })
    evidence_types.extend(value for value in authorship if value not in evidence_types)
    if profile_id == "source":
        evidence_types.append("原始文件")
    reasons = _coverage_reasons(
        profile_id,
        incremental_coverage,
        incremental_package_ids,
    )
    source_package_ids = {
        package.package_id.removeprefix("source-")
        for package in packages
        if package.package_type == "hsource"
    }
    if profile_id == "source" and source_package_ids != known_source_ids:
        raise BuildError("source profile 未包含全部原文包")
    return {
        "agentPackage": {
            "exactSignedBytes": agent_bytes,
            "fileName": agent_file_name,
            "sha256": agent_sha256,
            "type": "hagent",
        },
        "bundleFileName": bundle_file_name,
        "evidenceTypes": evidence_types or ["核心证据"],
        "exactSignedBytes": agent_bytes + sum(_package_bytes(package) for package in packages),
        "genres": genres or ["未单独扩展"],
        "id": profile_id,
        "includesOriginals": profile_id == "source",
        "incrementalCoverage": sorted(incremental_coverage),
        "incrementalPackageIds": incremental_package_ids,
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
    incremental_package_ids: list[str],
) -> list[str]:
    if profile_id == "source":
        return [
            f"附带 {len(incremental_package_ids)} 个本地原文包，"
            "原文仅供阅读核验且不参与回答"
        ]
    reasons = []
    if any(feature.startswith("voice:direct-material:") for feature in coverage):
        reasons.append("直接对话或直接作者材料")
    prefixes = {feature.partition(":")[0] for feature in coverage}
    for prefix, label in (
        ("identity", "身份"),
        ("stance", "立场"),
        ("relationship", "关系"),
        ("episode", "经历"),
        ("voice", "表达"),
    ):
        if prefix in prefixes and not (prefix == "voice" and reasons):
            reasons.append(f"{label}证据")
    periods = sorted(
        feature.partition(":")[2]
        for feature in coverage
        if feature.startswith("period:")
    )
    genres = sorted(
        feature.partition(":")[2]
        for feature in coverage
        if feature.startswith("genre:")
    )
    authorship = sorted(
        feature.partition(":")[2]
        for feature in coverage
        if feature.startswith("authorship:")
    )
    if periods:
        reasons.append("时期覆盖：" + "、".join(periods))
    if genres:
        reasons.append("体裁覆盖：" + "、".join(genres))
    if authorship:
        reasons.append("材料归属：" + "、".join(authorship))
    if any(feature.startswith("eval:") for feature in coverage):
        reasons.append("独特评测覆盖")
    if profile_id == "lite":
        reasons = [f"核心必需覆盖：{reason}" for reason in reasons]
    if profile_id == "complete" and incremental_package_ids:
        reasons.append(
            f"补齐全部可用证据分片（新增 {len(incremental_package_ids)} 个）"
        )
    if reasons:
        return reasons
    if incremental_package_ids:
        return [f"新增 {len(incremental_package_ids)} 个证据分片，补充证据深度"]
    return ["相对上一档没有新增运行时证据"]


def _agent_identity(manifest: dict[str, Any]) -> tuple[str, int]:
    agent = manifest.get("agent")
    if (
        manifest.get("schemaVersion") != 2
        or manifest.get("type") != "hagent"
        or not isinstance(agent, dict)
        or not isinstance(agent.get("id"), str)
        or not agent["id"]
        or not isinstance(agent.get("name"), str)
        or not agent["name"]
        or type(agent.get("version")) is not int
        or agent["version"] <= 0
    ):
        raise BuildError("签名 hagent 身份无效")
    return agent["id"], agent["version"]


def _read_install_plan(raw: dict[str, Any]) -> InstallPlan:
    try:
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


def _read_signed_corpus_metadata(
    paths: list[Path],
    plan: InstallPlan,
    agent_id: str,
    version: int,
) -> dict[str, dict[str, frozenset[str] | tuple[str, ...]]]:
    declared = {
        package.file_name: package
        for package in plan.packages
        if package.package_type == "hcorpus"
    }
    if set(declared) != {path.name for path in paths}:
        raise BuildError("签名安装计划与已生成语料包集合不一致")
    result = {}
    for path in paths:
        package = declared[path.name]
        digest, size = _hash_regular_file(path)
        if digest != package.sha256 or size != package.size_bytes:
            raise BuildError(f"签名语料包大小或哈希不匹配：{package.package_id}")
        manifest = _read_zip_json_object(path, "manifest.json")
        if (
            manifest.get("schemaVersion") != 2
            or manifest.get("type") != "hcorpus"
            or manifest.get("id") != package.package_id
            or manifest.get("agentId") != agent_id
            or manifest.get("version") != version
            or manifest.get("installClass") != package.install_class
        ):
            raise BuildError(f"签名语料包身份不匹配：{package.package_id}")
        coverage = _string_tuple(manifest.get("coverage"), "coverage", package)
        periods = _string_tuple(manifest.get("periods"), "periods", package)
        genres = _string_tuple(manifest.get("genres"), "genres", package)
        authorship = _string_tuple(
            manifest.get("authorship"),
            "authorship",
            package,
        )
        if (
            tuple(sorted(coverage)) != coverage
            or not coverage
            or not periods
            or not genres
            or not authorship
        ):
            raise BuildError(f"签名语料包覆盖元数据无效：{package.package_id}")
        result[package.package_id] = {
            "authorship": authorship,
            "coverage": frozenset(coverage),
            "genres": genres,
            "periods": periods,
        }
    return result


def _read_signed_source_metadata_from_corpora(
    paths: list[Path],
    plan: InstallPlan,
    agent_id: str,
    version: int,
) -> dict[str, dict[str, Any]]:
    declared_corpora = {
        package.file_name: package
        for package in plan.packages
        if package.package_type == "hcorpus"
    }
    if set(declared_corpora) != {path.name for path in paths}:
        raise BuildError("签名安装计划与已生成语料包集合不一致")
    expected_source_ids = {
        package.package_id.removeprefix("source-")
        for package in plan.packages
        if package.package_type == "hsource"
    }
    result: dict[str, dict[str, Any]] = {}
    signed_rows: dict[str, bytes] = {}
    for path in paths:
        package = declared_corpora[path.name]
        digest, size = _hash_regular_file(path)
        if digest != package.sha256 or size != package.size_bytes:
            raise BuildError(f"签名语料包大小或哈希不匹配：{package.package_id}")
        rows = _read_zip_json_array(path, "sources.json")
        for row in rows:
            if not isinstance(row, dict):
                raise BuildError(f"签名语料包来源元数据无效：{package.package_id}")
            source_id = row.get("sourceId")
            source_hash = row.get("sourceHash")
            raw_size = row.get("rawSizeBytes")
            file_name = row.get("fileName")
            stored_name = row.get("storedName")
            if (
                not isinstance(source_id, str)
                or not source_id
                or source_id not in expected_source_ids
                or not isinstance(source_hash, str)
                or len(source_hash) != 64
                or any(character not in "0123456789abcdef" for character in source_hash)
                or type(raw_size) is not int
                or raw_size < 0
                or not isinstance(file_name, str)
                or not file_name
                or not isinstance(stored_name, str)
                or not stored_name
            ):
                raise BuildError(f"签名语料包来源元数据无效：{package.package_id}")
            canonical_row = json.dumps(
                row,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
            previous = signed_rows.get(source_id)
            if previous is not None and previous != canonical_row:
                raise BuildError(f"签名语料包来源元数据冲突：{source_id}")
            signed_rows[source_id] = canonical_row
            result[source_id] = {
                "rawSizeBytes": raw_size,
                "sourceHash": source_hash,
            }
    if set(result) != expected_source_ids:
        raise BuildError("签名语料包未覆盖安装计划中的全部原文来源")
    return result


def _read_zip_json_object(path: Path, name: str) -> dict[str, Any]:
    try:
        with zipfile.ZipFile(path) as archive:
            info = archive.getinfo(name)
            if info.is_dir() or info.file_size > 4 * 1024 * 1024:
                raise BuildError(f"签名包 JSON 条目过大：{path.name} / {name}")
            with archive.open(info) as stream:
                raw = stream.read(4 * 1024 * 1024 + 1)
        if len(raw) != info.file_size or len(raw) > 4 * 1024 * 1024:
            raise BuildError(f"签名包 JSON 条目大小不一致：{path.name} / {name}")
        value = json.loads(raw)
    except BuildError:
        raise
    except (KeyError, OSError, ValueError, zipfile.BadZipFile) as error:
        raise BuildError(f"签名包 JSON 无法读取：{path.name} / {name}：{error}") from error
    if not isinstance(value, dict):
        raise BuildError(f"签名包 JSON 必须是对象：{path.name} / {name}")
    return value


def _read_zip_json_array(path: Path, name: str) -> list[Any]:
    try:
        with zipfile.ZipFile(path) as archive:
            info = archive.getinfo(name)
            if info.is_dir() or info.file_size > 4 * 1024 * 1024:
                raise BuildError(f"签名包 JSON 条目过大：{path.name} / {name}")
            with archive.open(info) as stream:
                raw = stream.read(4 * 1024 * 1024 + 1)
        if len(raw) != info.file_size or len(raw) > 4 * 1024 * 1024:
            raise BuildError(f"签名包 JSON 条目大小不一致：{path.name} / {name}")
        value = json.loads(raw)
    except BuildError:
        raise
    except (KeyError, OSError, ValueError, zipfile.BadZipFile) as error:
        raise BuildError(f"签名包 JSON 无法读取：{path.name} / {name}：{error}") from error
    if not isinstance(value, list):
        raise BuildError(f"签名包 JSON 必须是数组：{path.name} / {name}")
    return value


def _string_tuple(
    value: Any,
    field: str,
    package: InstallPackage,
) -> tuple[str, ...]:
    if (
        not isinstance(value, list)
        or any(not isinstance(item, str) or not item for item in value)
        or len(value) != len(set(value))
    ):
        raise BuildError(f"签名语料包 {field} 无效：{package.package_id}")
    return tuple(value)


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


def _hash_regular_file(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with Path(path).open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
            size += len(block)
    return digest.hexdigest(), size


def _temporary_regular_bytes(root: Path) -> int:
    return sum(
        path.stat().st_size
        for path in root.rglob("*")
        if path.is_file() and not path.is_symlink()
    )


def _join(values: list[str]) -> str:
    return "、".join(values) if values else "无"
