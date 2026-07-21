"""Explicit, non-self-certifying rights records for history builds."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from tools.package_format import canonical_json_bytes

from ..models import BuildError
from .source_inventory import SourceLock

_REVISION_PATTERN = re.compile(r"[0-9a-f]{40}\Z")


class RightsError(BuildError):
    """Raised when explicit source rights do not authorize an operation."""


@dataclass(frozen=True)
class SourceRights:
    source_id: str
    git_revision: str
    user_confirmed: bool
    basis: str
    distribution_allowed: bool
    semantic_processing_approved: bool
    evidence: tuple[str, ...]

    @classmethod
    def from_dict(cls, raw: object) -> "SourceRights":
        value = _mapping(raw, "rights source")
        required = {"sourceId", "gitRevision", "userConfirmed", "basis"}
        optional = {"distributionAllowed", "semanticProcessingApproved", "evidence"}
        _fields(value, required, optional, "rights source")
        source_id = _text(value["sourceId"], "sourceId")
        revision = _text(value["gitRevision"], "gitRevision")
        if not _REVISION_PATTERN.fullmatch(revision):
            raise RightsError("gitRevision 必须是 40 位小写 Git revision")
        if type(value["userConfirmed"]) is not bool:
            raise RightsError("userConfirmed 必须由用户显式填写布尔值")
        distribution = value.get("distributionAllowed", False)
        semantic = value.get("semanticProcessingApproved", False)
        if type(distribution) is not bool:
            raise RightsError("source distributionAllowed 必须是布尔值")
        if type(semantic) is not bool:
            raise RightsError("semanticProcessingApproved 必须是布尔值")
        raw_evidence = value.get("evidence", [])
        if not isinstance(raw_evidence, list) or any(
            not isinstance(item, str) or not item.strip() for item in raw_evidence
        ):
            raise RightsError("source evidence 必须是非空字符串数组")
        return cls(
            source_id=source_id,
            git_revision=revision,
            user_confirmed=value["userConfirmed"],
            basis=_text(value["basis"], "basis", allow_blank=True),
            distribution_allowed=distribution,
            semantic_processing_approved=semantic,
            evidence=tuple(raw_evidence),
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "sourceId": self.source_id,
            "gitRevision": self.git_revision,
            "userConfirmed": self.user_confirmed,
            "basis": self.basis,
            "distributionAllowed": self.distribution_allowed,
            "semanticProcessingApproved": self.semantic_processing_approved,
            "evidence": list(self.evidence),
        }


@dataclass(frozen=True)
class RightsConfirmation:
    purpose: str
    distribution_allowed: bool
    sources: tuple[SourceRights, ...]
    schema_version: int = 1

    @classmethod
    def from_dict(cls, raw: object) -> "RightsConfirmation":
        value = _mapping(raw, "rights confirmation")
        _fields(
            value,
            {"type", "schemaVersion", "purpose", "distributionAllowed", "sources"},
            set(),
            "rights confirmation",
        )
        if value["type"] != "hwiki-rights-confirmation":
            raise RightsError("rights confirmation type 无效")
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            raise RightsError("rights confirmation schemaVersion 必须是 1")
        if type(value["distributionAllowed"]) is not bool:
            raise RightsError("distributionAllowed 必须是布尔值")
        raw_sources = value["sources"]
        if not isinstance(raw_sources, list):
            raise RightsError("rights confirmation sources 必须是数组")
        sources = tuple(SourceRights.from_dict(item) for item in raw_sources)
        ids = [source.source_id for source in sources]
        if ids != sorted(ids) or len(ids) != len(set(ids)):
            raise RightsError("rights sources 必须按 sourceId 排序且不能重复")
        return cls(
            purpose=_text(value["purpose"], "purpose"),
            distribution_allowed=value["distributionAllowed"],
            sources=sources,
        )

    @classmethod
    def from_path(cls, path: Path) -> "RightsConfirmation":
        source = Path(path)
        if source.is_symlink() or not source.is_file():
            raise RightsError(f"rights confirmation 不可安全读取：{source}")
        try:
            raw = source.read_bytes()
            value = json.loads(raw)
        except (OSError, UnicodeDecodeError, ValueError) as error:
            raise RightsError(f"rights confirmation 无法读取：{error}") from error
        if raw != canonical_json_bytes(value):
            raise RightsError("rights confirmation 必须是规范 JSON")
        return cls.from_dict(value)

    def to_dict(self) -> dict[str, object]:
        return {
            "type": "hwiki-rights-confirmation",
            "schemaVersion": self.schema_version,
            "purpose": self.purpose,
            "distributionAllowed": self.distribution_allowed,
            "sources": [source.to_dict() for source in self.sources],
        }


def verify_build_rights(
    confirmation: RightsConfirmation,
    source_lock: SourceLock,
    *,
    distribution: bool = False,
    semantic_processing: bool = False,
) -> dict[str, object]:
    if confirmation.purpose != "private-local-install":
        raise RightsError("purpose 必须是 private-local-install")
    dirty_sources = sorted(source.source_id for source in source_lock.sources if source.dirty)
    if dirty_sources:
        raise RightsError(f"source lock 包含 dirty 来源：{', '.join(dirty_sources)}")
    locked = {source.source_id: source for source in source_lock.sources}
    confirmed = {source.source_id: source for source in confirmation.sources}
    missing = sorted(set(locked) - set(confirmed))
    extra = sorted(set(confirmed) - set(locked))
    if missing:
        raise RightsError(f"缺少来源权利确认：{', '.join(missing)}")
    if extra:
        raise RightsError(f"权利确认包含锁外来源：{', '.join(extra)}")
    for source_id in sorted(locked):
        locked_source = locked[source_id]
        rights = confirmed[source_id]
        if rights.git_revision != locked_source.git_revision:
            raise RightsError(f"{source_id} rights revision 与 source lock 版本不一致")
        if rights.user_confirmed is not True:
            raise RightsError(f"{source_id} userConfirmed 必须由用户显式设为 true")
        if not rights.basis.strip():
            raise RightsError(f"{source_id} basis 不能为空")
        if semantic_processing and not rights.semantic_processing_approved:
            raise RightsError(f"{source_id} semanticProcessingApproved=false")
        if distribution:
            if not confirmation.distribution_allowed or not rights.distribution_allowed:
                raise RightsError(f"{source_id} distributionAllowed=false")
            if not rights.evidence:
                raise RightsError(f"{source_id} distribution 需要 evidence 路径或 URL")
    return {
        "purpose": confirmation.purpose,
        "distributionAllowed": distribution,
        "semanticProcessingApproved": semantic_processing,
        "verifiedSourceIds": sorted(locked),
    }


def _mapping(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise RightsError(f"{label} 必须是对象")
    return value


def _fields(
    value: dict[str, object],
    required: set[str],
    optional: set[str],
    label: str,
) -> None:
    unknown = sorted(set(value) - required - optional)
    missing = sorted(required - set(value))
    if unknown:
        raise RightsError(f"{label} 包含未知字段：{', '.join(unknown)}")
    if missing:
        raise RightsError(f"{label} 缺少字段：{', '.join(missing)}")


def _text(value: object, label: str, *, allow_blank: bool = False) -> str:
    if not isinstance(value, str) or (not allow_blank and not value.strip()):
        raise RightsError(f"{label} 必须是字符串")
    return value


__all__ = [
    "RightsConfirmation",
    "RightsError",
    "SourceRights",
    "verify_build_rights",
]
