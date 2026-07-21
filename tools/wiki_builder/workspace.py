"""Workspace metadata contract used before a Wiki package is signed."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from tools.package_format import canonical_json_bytes

from .models import BuildError, PreparedDocument
from .normalization import NORMALIZATION_MAP_HASH, NORMALIZATION_VERSION

WORKSPACE_SCHEMA_VERSION = 1
ENRICHMENT_FILE_NAMES = (
    "aliases.jsonl",
    "annotations.jsonl",
    "concept-registry.jsonl",
    "links.jsonl",
    "mentions.jsonl",
    "summaries.jsonl",
    "terms.jsonl",
)


@dataclass(frozen=True)
class WikiWorkspace:
    root: Path
    wiki_id: str
    title: str
    version: int
    concept_namespace: str
    builder_profile: str
    database_path: Path
    enrichment_path: Path


def initialize_workspace_files(
    root: Path,
    documents: tuple[PreparedDocument, ...],
    *,
    wiki_id: str,
    title: str,
    version: int,
    concept_namespace: str,
    builder_profile: str = "generic-v1",
) -> None:
    enrichment = root / "enrichment"
    enrichment.mkdir()
    for file_name in ENRICHMENT_FILE_NAMES:
        (enrichment / file_name).write_bytes(b"")

    workspace = {
        "type": "hwiki-workspace",
        "schemaVersion": WORKSPACE_SCHEMA_VERSION,
        "wiki": {"id": wiki_id, "version": version, "title": title},
        "conceptNamespace": concept_namespace,
        "database": "content.sqlite",
        "sourceCatalog": "source-catalog.json",
        "enrichmentDirectory": "enrichment",
        "normalization": {
            "version": NORMALIZATION_VERSION,
            "mapHash": NORMALIZATION_MAP_HASH,
        },
        "builder": {
            "name": "harness-wiki-builder",
            "version": "1",
            "profile": builder_profile,
        },
    }
    catalog = {
        "schemaVersion": 1,
        "sources": [
            {
                "sourceId": document.document_id,
                "documentId": document.document_id,
                "fileName": document.source_path.name,
                "format": document.source_path.suffix.lower().lstrip("."),
                "sha256": document.source_hash,
                "sizeBytes": document.size_bytes,
                "ordinal": document.ordinal,
                "rights": "unverified",
            }
            for document in documents
        ],
    }
    (root / "workspace.json").write_bytes(canonical_json_bytes(workspace))
    (root / "source-catalog.json").write_bytes(canonical_json_bytes(catalog))


def load_workspace(root: Path) -> WikiWorkspace:
    workspace_root = Path(root)
    manifest_path = workspace_root / "workspace.json"
    if workspace_root.is_symlink() or manifest_path.is_symlink():
        raise BuildError("工作区路径不能是符号链接")
    try:
        raw = json.loads(manifest_path.read_bytes())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BuildError(f"工作区清单无法读取：{error}") from error
    if not isinstance(raw, dict):
        raise BuildError("workspace.json 必须是对象")
    expected = {
        "type",
        "schemaVersion",
        "wiki",
        "conceptNamespace",
        "database",
        "sourceCatalog",
        "enrichmentDirectory",
        "normalization",
        "builder",
    }
    unknown = sorted(set(raw) - expected)
    missing = sorted(expected - set(raw))
    if unknown or missing:
        details = unknown or missing
        label = "未知字段" if unknown else "缺少字段"
        raise BuildError(f"workspace.json {label}：{', '.join(details)}")
    if (
        raw["type"] != "hwiki-workspace"
        or type(raw["schemaVersion"]) is not int
        or raw["schemaVersion"] != 1
    ):
        raise BuildError("workspace.json 协议版本不受支持")
    wiki = raw["wiki"]
    if not isinstance(wiki, dict) or set(wiki) != {"id", "version", "title"}:
        raise BuildError("workspace.json wiki 字段无效")
    if raw["database"] != "content.sqlite":
        raise BuildError("workspace.json database 路径无效")
    if raw["enrichmentDirectory"] != "enrichment":
        raise BuildError("workspace.json enrichmentDirectory 路径无效")
    builder = raw["builder"]
    if (
        not isinstance(builder, dict)
        or set(builder) != {"name", "version", "profile"}
        or builder["name"] != "harness-wiki-builder"
        or not isinstance(builder["version"], str)
        or not isinstance(builder["profile"], str)
        or not builder["profile"]
    ):
        raise BuildError("workspace.json builder 字段无效")
    if type(wiki["version"]) is not int or wiki["version"] <= 0:
        raise BuildError("workspace.json wiki.version 无效")
    for value, label in (
        (wiki["id"], "wiki.id"),
        (wiki["title"], "wiki.title"),
        (raw["conceptNamespace"], "conceptNamespace"),
    ):
        if not isinstance(value, str) or not value.strip():
            raise BuildError(f"workspace.json {label} 无效")
    database_path = workspace_root / "content.sqlite"
    enrichment_path = workspace_root / "enrichment"
    if not database_path.is_file() or database_path.is_symlink():
        raise BuildError("工作区缺少安全的 content.sqlite")
    if not enrichment_path.is_dir() or enrichment_path.is_symlink():
        raise BuildError("工作区缺少安全的 enrichment 目录")
    return WikiWorkspace(
        root=workspace_root,
        wiki_id=wiki["id"],
        title=wiki["title"],
        version=wiki["version"],
        concept_namespace=raw["conceptNamespace"],
        builder_profile=builder["profile"],
        database_path=database_path,
        enrichment_path=enrichment_path,
    )


__all__ = [
    "ENRICHMENT_FILE_NAMES",
    "WORKSPACE_SCHEMA_VERSION",
    "WikiWorkspace",
    "initialize_workspace_files",
    "load_workspace",
]
