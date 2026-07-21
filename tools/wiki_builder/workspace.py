"""Workspace metadata contract used before a Wiki package is signed."""

from __future__ import annotations

from pathlib import Path

from tools.package_format import canonical_json_bytes

from .models import PreparedDocument
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


def initialize_workspace_files(
    root: Path,
    documents: tuple[PreparedDocument, ...],
    *,
    wiki_id: str,
    title: str,
    version: int,
    concept_namespace: str,
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
        "builder": {"name": "harness-wiki-builder", "version": "1"},
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


__all__ = [
    "ENRICHMENT_FILE_NAMES",
    "WORKSPACE_SCHEMA_VERSION",
    "initialize_workspace_files",
]
