"""Strict schema-v1 manifest models for offline Wiki packages."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal, Mapping

from .models import ManifestError

_IDENTIFIER_PATTERN = re.compile(r"[a-z0-9]+(?:[._-][a-z0-9]+)*\Z")
_HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
_LANGUAGE_PATTERN = re.compile(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\Z")
_GENERATED_PAGE_LEVELS = frozenset({"none", "partial", "complete"})


def _require_mapping(value: object, path: str) -> Mapping[str, object]:
    if not isinstance(value, Mapping):
        raise ManifestError(f"{path} 必须是对象")
    if not all(isinstance(key, str) for key in value):
        raise ManifestError(f"{path} 的字段名必须是字符串")
    return value


def _require_fields(
    value: Mapping[str, object],
    expected: frozenset[str],
    path: str,
) -> None:
    actual = set(value)
    unknown = sorted(actual - expected)
    if unknown:
        raise ManifestError(f"{path} 包含未知字段: {', '.join(unknown)}")
    missing = sorted(expected - actual)
    if missing:
        raise ManifestError(f"{path} 缺少字段: {', '.join(missing)}")


def _require_string(value: object, path: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ManifestError(f"{path} 必须是字符串")
    if not allow_empty and not value.strip():
        raise ManifestError(f"{path} 不能为空")
    return value


def _require_boolean(value: object, path: str) -> bool:
    if type(value) is not bool:
        raise ManifestError(f"{path} 必须是布尔值")
    return value


def _require_identifier(value: object, path: str) -> str:
    identifier = _require_string(value, path)
    if not _IDENTIFIER_PATTERN.fullmatch(identifier):
        raise ManifestError(f"{path} 不是规范标识符")
    return identifier


def validate_identifier(value: object, path: str = "identifier") -> str:
    """Validate and return a canonical schema-v1 identifier."""

    return _require_identifier(value, path)


def _require_hash(value: object, path: str) -> str:
    digest = _require_string(value, path)
    if not _HASH_PATTERN.fullmatch(digest):
        raise ManifestError(f"{path} 必须是 64 位小写 SHA-256")
    return digest


@dataclass(frozen=True)
class WikiCapabilities:
    source_hierarchy: bool
    source_search: bool
    hierarchical_summaries: bool
    term_index: bool
    temporal_annotations: bool
    cross_wiki_links: bool
    generated_pages: Literal["none", "partial", "complete"]
    claim_graph: bool
    vector_index: bool
    source_attachments: bool

    @classmethod
    def from_dict(cls, raw: object) -> "WikiCapabilities":
        value = _require_mapping(raw, "capabilities")
        fields = frozenset(
            {
                "sourceHierarchy",
                "sourceSearch",
                "hierarchicalSummaries",
                "termIndex",
                "temporalAnnotations",
                "crossWikiLinks",
                "generatedPages",
                "claimGraph",
                "vectorIndex",
                "sourceAttachments",
            }
        )
        _require_fields(value, fields, "capabilities")

        generated_pages = _require_string(
            value["generatedPages"], "capabilities.generatedPages"
        )
        if generated_pages not in _GENERATED_PAGE_LEVELS:
            raise ManifestError(
                "capabilities.generatedPages 必须是 none、partial 或 complete"
            )

        vector_index = _require_boolean(
            value["vectorIndex"], "capabilities.vectorIndex"
        )
        if vector_index:
            raise ManifestError("schema v1 不支持 capabilities.vectorIndex=true")

        source_attachments = _require_boolean(
            value["sourceAttachments"], "capabilities.sourceAttachments"
        )
        if source_attachments:
            raise ManifestError("schema v1 不支持 capabilities.sourceAttachments=true")

        return cls(
            source_hierarchy=_require_boolean(
                value["sourceHierarchy"], "capabilities.sourceHierarchy"
            ),
            source_search=_require_boolean(
                value["sourceSearch"], "capabilities.sourceSearch"
            ),
            hierarchical_summaries=_require_boolean(
                value["hierarchicalSummaries"],
                "capabilities.hierarchicalSummaries",
            ),
            term_index=_require_boolean(value["termIndex"], "capabilities.termIndex"),
            temporal_annotations=_require_boolean(
                value["temporalAnnotations"], "capabilities.temporalAnnotations"
            ),
            cross_wiki_links=_require_boolean(
                value["crossWikiLinks"], "capabilities.crossWikiLinks"
            ),
            generated_pages=generated_pages,  # type: ignore[arg-type]
            claim_graph=_require_boolean(
                value["claimGraph"], "capabilities.claimGraph"
            ),
            vector_index=vector_index,
            source_attachments=source_attachments,
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "sourceHierarchy": self.source_hierarchy,
            "sourceSearch": self.source_search,
            "hierarchicalSummaries": self.hierarchical_summaries,
            "termIndex": self.term_index,
            "temporalAnnotations": self.temporal_annotations,
            "crossWikiLinks": self.cross_wiki_links,
            "generatedPages": self.generated_pages,
            "claimGraph": self.claim_graph,
            "vectorIndex": self.vector_index,
            "sourceAttachments": self.source_attachments,
        }


@dataclass(frozen=True)
class WikiManifest:
    wiki_id: str
    version: int
    title: str
    languages: tuple[str, ...]
    description: str
    content_hash: str
    publisher_key_id: str
    publisher_name: str
    capabilities: WikiCapabilities
    concept_namespace: str
    concept_registry_hash: str
    builder_name: str
    builder_version: str
    builder_profile: str
    schema_version: int = 1

    @classmethod
    def from_dict(cls, raw: object) -> "WikiManifest":
        root = _require_mapping(raw, "manifest")
        _require_fields(
            root,
            frozenset(
                {
                    "type",
                    "schemaVersion",
                    "wiki",
                    "publisher",
                    "capabilities",
                    "conceptNamespace",
                    "conceptRegistryHash",
                    "builder",
                }
            ),
            "manifest",
        )
        if root["type"] != "hwiki":
            raise ManifestError("type 必须是 hwiki")
        if type(root["schemaVersion"]) is not int or root["schemaVersion"] != 1:
            raise ManifestError("schemaVersion 必须是 1")

        wiki = _require_mapping(root["wiki"], "wiki")
        _require_fields(
            wiki,
            frozenset(
                {
                    "id",
                    "version",
                    "title",
                    "language",
                    "description",
                    "contentHash",
                }
            ),
            "wiki",
        )
        version = wiki["version"]
        if type(version) is not int or version <= 0:
            raise ManifestError("wiki.version 必须是正整数")

        languages_value = wiki["language"]
        if not isinstance(languages_value, list) or not languages_value:
            raise ManifestError("wiki.language 必须是非空数组")
        languages: list[str] = []
        for index, language_value in enumerate(languages_value):
            language = _require_string(language_value, f"wiki.language[{index}]")
            if not _LANGUAGE_PATTERN.fullmatch(language):
                raise ManifestError(f"wiki.language[{index}] 不是规范语言标记")
            languages.append(language)
        if len(languages) != len(set(languages)):
            raise ManifestError("wiki.language 不能包含重复值")

        publisher = _require_mapping(root["publisher"], "publisher")
        _require_fields(publisher, frozenset({"keyId", "name"}), "publisher")

        builder = _require_mapping(root["builder"], "builder")
        _require_fields(
            builder, frozenset({"name", "version", "profile"}), "builder"
        )

        return cls(
            wiki_id=_require_identifier(wiki["id"], "wiki.id"),
            version=version,
            title=_require_string(wiki["title"], "wiki.title"),
            languages=tuple(languages),
            description=_require_string(
                wiki["description"], "wiki.description", allow_empty=True
            ),
            content_hash=_require_hash(wiki["contentHash"], "wiki.contentHash"),
            publisher_key_id=_require_string(
                publisher["keyId"], "publisher.keyId"
            ),
            publisher_name=_require_string(publisher["name"], "publisher.name"),
            capabilities=WikiCapabilities.from_dict(root["capabilities"]),
            concept_namespace=_require_identifier(
                root["conceptNamespace"], "conceptNamespace"
            ),
            concept_registry_hash=_require_hash(
                root["conceptRegistryHash"], "conceptRegistryHash"
            ),
            builder_name=_require_string(builder["name"], "builder.name"),
            builder_version=_require_string(builder["version"], "builder.version"),
            builder_profile=_require_identifier(builder["profile"], "builder.profile"),
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "type": "hwiki",
            "schemaVersion": self.schema_version,
            "wiki": {
                "id": self.wiki_id,
                "version": self.version,
                "title": self.title,
                "language": list(self.languages),
                "description": self.description,
                "contentHash": self.content_hash,
            },
            "publisher": {
                "keyId": self.publisher_key_id,
                "name": self.publisher_name,
            },
            "capabilities": self.capabilities.to_dict(),
            "conceptNamespace": self.concept_namespace,
            "conceptRegistryHash": self.concept_registry_hash,
            "builder": {
                "name": self.builder_name,
                "version": self.builder_version,
                "profile": self.builder_profile,
            },
        }


__all__ = [
    "ManifestError",
    "WikiCapabilities",
    "WikiManifest",
    "validate_identifier",
]
