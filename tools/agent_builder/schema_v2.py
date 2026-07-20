from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePosixPath, PureWindowsPath
import re
from typing import Any

from .models import BuildError


WORKSPACE_V2_SCHEMA_VERSION = 2
IDENTIFIER_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{1,127}")


class SourceGenre(StrEnum):
    ESSAY = "essay"
    SPEECH = "speech"
    CONVERSATION = "conversation"
    LETTER = "letter"
    INTERVIEW = "interview"
    MEMOIR = "memoir"
    SECONDARY = "secondary"
    UNKNOWN = "unknown"


class Authorship(StrEnum):
    DIRECT = "direct"
    EDITED_DIRECT = "edited_direct"
    SECONDARY = "secondary"
    UNKNOWN = "unknown"


class InstallClass(StrEnum):
    REQUIRED = "required"
    RECOMMENDED = "recommended"
    OPTIONAL = "optional"
    SOURCE = "source"


@dataclass(frozen=True)
class AgentAssetPaths:
    persona: str = "agent/persona.md"
    identity: str = "agent/identity.json"
    voice: str = "agent/voice.json"
    worldview: str = "agent/worldview.jsonl"
    episodes: str = "agent/episodes.jsonl"
    concepts: str = "agent/concepts.json"
    examples: str = "agent/examples.jsonl"
    openers: str = "agent/openers.json"
    eval: str = "agent/eval.jsonl"

    def to_dict(self) -> dict[str, str]:
        return {
            "persona": self.persona,
            "identity": self.identity,
            "voice": self.voice,
            "worldview": self.worldview,
            "episodes": self.episodes,
            "concepts": self.concepts,
            "examples": self.examples,
            "openers": self.openers,
            "eval": self.eval,
        }

    @classmethod
    def from_dict(cls, value: Any) -> "AgentAssetPaths":
        source = _object(value, "assets")
        names = (
            "persona",
            "identity",
            "voice",
            "worldview",
            "episodes",
            "concepts",
            "examples",
            "openers",
            "eval",
        )
        return cls(**{name: _workspace_path(source.get(name), f"assets.{name}") for name in names})


@dataclass(frozen=True)
class SourceRecord:
    source_id: str
    title: str
    file_name: str
    stored_name: str
    source_hash: str
    format: str
    genre: SourceGenre
    authorship: Authorship
    period: str
    raw_size_bytes: int
    extracted_chars: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "sourceId": self.source_id,
            "title": self.title,
            "fileName": self.file_name,
            "storedName": self.stored_name,
            "sourceHash": self.source_hash,
            "format": self.format,
            "genre": self.genre.value,
            "authorship": self.authorship.value,
            "period": self.period,
            "rawSizeBytes": self.raw_size_bytes,
            "extractedChars": self.extracted_chars,
        }

    @classmethod
    def from_dict(cls, value: Any) -> "SourceRecord":
        source = _object(value, "source")
        try:
            genre = SourceGenre(_string(source.get("genre"), "source.genre"))
        except ValueError as error:
            raise BuildError(f"source genre 无效：{source.get('genre')}") from error
        try:
            authorship = Authorship(_string(source.get("authorship"), "source.authorship"))
        except ValueError as error:
            raise BuildError(f"source authorship 无效：{source.get('authorship')}") from error
        return cls(
            source_id=identifier(source.get("sourceId"), "source.sourceId"),
            title=_string(source.get("title"), "source.title"),
            file_name=_display_file_name(source.get("fileName"), "source.fileName"),
            stored_name=_stored_file_name(source.get("storedName"), "source.storedName"),
            source_hash=_string(source.get("sourceHash"), "source.sourceHash"),
            format=_string(source.get("format"), "source.format"),
            genre=genre,
            authorship=authorship,
            period=_string(source.get("period"), "source.period"),
            raw_size_bytes=_non_negative_int(source.get("rawSizeBytes"), "source.rawSizeBytes"),
            extracted_chars=_non_negative_int(source.get("extractedChars"), "source.extractedChars"),
        )


@dataclass(frozen=True)
class WorkspaceV2:
    agent_id: str
    name: str
    version: int
    assets: AgentAssetPaths
    sources: tuple[SourceRecord, ...]
    schema_version: int = WORKSPACE_V2_SCHEMA_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "agent": {
                "id": self.agent_id,
                "name": self.name,
                "version": self.version,
            },
            "assets": self.assets.to_dict(),
            "sources": [source.to_dict() for source in self.sources],
        }

    @classmethod
    def from_dict(cls, value: Any) -> "WorkspaceV2":
        manifest = _object(value, "workspace.json")
        if (
            isinstance(manifest.get("schemaVersion"), bool)
            or not isinstance(manifest.get("schemaVersion"), int)
            or manifest.get("schemaVersion") != WORKSPACE_V2_SCHEMA_VERSION
        ):
            raise BuildError(f"不支持的 schemaVersion：{manifest.get('schemaVersion')}")
        agent = _object(manifest.get("agent"), "agent")
        source_values = manifest.get("sources")
        if not isinstance(source_values, list) or not source_values:
            raise BuildError("sources 必须是非空数组")
        sources = tuple(SourceRecord.from_dict(item) for item in source_values)
        _reject_duplicates((source.source_id for source in sources), "sourceId")
        _reject_duplicates((source.stored_name for source in sources), "storedName")
        return cls(
            agent_id=identifier(agent.get("id"), "agent.id"),
            name=_string(agent.get("name"), "agent.name"),
            version=_positive_int(agent.get("version"), "agent.version"),
            assets=AgentAssetPaths.from_dict(manifest.get("assets")),
            sources=sources,
        )


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise BuildError(f"{label} 必须是对象")
    return value


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BuildError(f"{label} 必须是非空字符串")
    return value.strip()


def identifier(value: Any, label: str) -> str:
    """Normalize and validate portable workspace identifiers."""
    if not isinstance(value, str):
        raise BuildError(f"{label} 只能包含字母、数字、点、下划线和连字符")
    normalized = value.strip()
    if not IDENTIFIER_PATTERN.fullmatch(normalized):
        raise BuildError(f"{label} 只能包含字母、数字、点、下划线和连字符")
    return normalized


def _workspace_path(value: Any, label: str) -> str:
    raw = _string(value, label)
    if "\x00" in raw:
        raise BuildError(f"不安全的工作区路径：{raw}")
    normalized = raw.replace("\\", "/")
    path = PurePosixPath(normalized)
    windows_path = PureWindowsPath(raw)
    if (
        path.is_absolute()
        or windows_path.drive
        or windows_path.root
        or ".." in path.parts
        or not path.parts
    ):
        raise BuildError(f"不安全的工作区路径：{raw}")
    return str(path)


def _display_file_name(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or value in {".", ".."} or "/" in value or "\x00" in value:
        raise BuildError(f"{label} 必须是非空文件名")
    return value


def _stored_file_name(value: Any, label: str) -> str:
    path = _workspace_path(value, label)
    if len(PurePosixPath(path).parts) != 1:
        raise BuildError(f"{label} 必须是单一文件名")
    return path


def _non_negative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise BuildError(f"{label} 必须是非负整数")
    return value


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise BuildError(f"{label} 必须是正整数")
    return value


def _reject_duplicates(values: Any, label: str) -> None:
    seen: set[str] = set()
    for value in values:
        if value in seen:
            raise BuildError(f"重复的 {label}：{value}")
        seen.add(value)
