"""Deterministic, resumable semantic jobs for history Wiki workspaces."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from tools.package_format import canonical_json_bytes

from ..enrichment import EnrichmentStats, import_enrichment
from ..extractors import stable_id
from ..models import BuildError
from ..normalization import normalize_for_search
from ..workspace import ENRICHMENT_FILE_NAMES, WikiWorkspace, load_workspace
from .concept_registry import merge_concept_candidates
from .history_profile import (
    CONCEPT_KINDS,
    HIGH_CONFIDENCE_IDENTITY,
    LOW_CONFIDENCE_LINK,
    MAX_CONTEXT_HEADING_CHARS,
    MAX_SOURCE_CHARS,
    OUTPUT_SCHEMA_ID,
    PROFILE_ID,
    PROMPT_VERSION,
)

JOBS_DIRECTORY_NAME = "history-jobs"
_TOKEN = re.compile(r"[a-z0-9]+(?:[._-][a-z0-9]+)*\Z")
_REVIEW_STATES = {"auto-high-confidence", "reviewed", "unresolved"}


@dataclass(frozen=True)
class JobPlan:
    job_ids: tuple[str, ...]
    valid_job_ids: tuple[str, ...]
    pending_job_ids: tuple[str, ...]


@dataclass(frozen=True)
class JobValidation:
    job_id: str
    input_hash: str
    output_hash: str
    concept_count: int
    annotation_count: int
    link_count: int


def create_jobs(
    workspace: Path,
    *,
    profile: str = PROFILE_ID,
) -> JobPlan:
    loaded = load_workspace(workspace)
    _require_history_workspace(loaded, profile)
    jobs = _build_job_inputs(loaded)
    target = loaded.root / JOBS_DIRECTORY_NAME
    if target.is_symlink() or (target.exists() and not target.is_dir()):
        raise BuildError(f"{JOBS_DIRECTORY_NAME} 必须是普通目录")
    staging = Path(
        tempfile.mkdtemp(prefix=f".{JOBS_DIRECTORY_NAME}-", dir=loaded.root)
    )
    published = False
    try:
        for directory in ("inputs", "outputs", "valid"):
            (staging / directory).mkdir()
        manifest_jobs = []
        for job in jobs:
            job_id = str(job["jobId"])
            input_bytes = canonical_json_bytes(job)
            (staging / "inputs" / f"{job_id}.json").write_bytes(input_bytes)
            manifest_jobs.append(
                {
                    "jobId": job_id,
                    "inputHash": job["inputHash"],
                    "documentId": job["scope"]["documentId"],
                    "sectionId": job["scope"]["sectionId"],
                    "partNumber": job["scope"]["partNumber"],
                    "partCount": job["scope"]["partCount"],
                    "sourceChars": job["sourceChars"],
                }
            )
            _reuse_matching_job(target, staging, job_id, input_bytes, job)
        manifest = {
            "type": "hwiki-history-job-manifest",
            "schemaVersion": 1,
            "profile": PROFILE_ID,
            "promptVersion": PROMPT_VERSION,
            "outputSchema": OUTPUT_SCHEMA_ID,
            "maxSourceChars": MAX_SOURCE_CHARS,
            "wikiId": loaded.wiki_id,
            "wikiVersion": loaded.version,
            "jobs": manifest_jobs,
        }
        (staging / "manifest.json").write_bytes(canonical_json_bytes(manifest))
        _replace_directory(target, staging)
        published = True
    finally:
        if not published:
            shutil.rmtree(staging, ignore_errors=True)
    return _job_plan(target, tuple(str(job["jobId"]) for job in jobs))


def validate_job(workspace: Path, job_id: str) -> JobValidation:
    loaded = load_workspace(workspace)
    _require_history_workspace(loaded, PROFILE_ID)
    target = loaded.root / JOBS_DIRECTORY_NAME
    job = _load_current_input(target, job_id)
    output_path = target / "outputs" / f"{job_id}.jsonl"
    marker_path = target / "valid" / f"{job_id}.json"
    try:
        output, output_bytes = _read_output(output_path)
        _validate_output(job, output, loaded)
    except BaseException:
        marker_path.unlink(missing_ok=True)
        raise
    output_hash = hashlib.sha256(output_bytes).hexdigest()
    marker = {
        "type": "hwiki-history-valid-job",
        "schemaVersion": 1,
        "jobId": job_id,
        "inputHash": job["inputHash"],
        "outputHash": output_hash,
        "profile": PROFILE_ID,
        "promptVersion": PROMPT_VERSION,
    }
    _atomic_write(marker_path, canonical_json_bytes(marker))
    return JobValidation(
        job_id=job_id,
        input_hash=str(job["inputHash"]),
        output_hash=output_hash,
        concept_count=len(output["concepts"]),
        annotation_count=len(output["annotations"]),
        link_count=len(output["links"]),
    )


def merge_jobs(workspace: Path) -> EnrichmentStats:
    loaded = load_workspace(workspace)
    _require_history_workspace(loaded, PROFILE_ID)
    jobs_root = loaded.root / JOBS_DIRECTORY_NAME
    manifest = _load_manifest(jobs_root)
    validated: list[tuple[dict[str, object], dict[str, object]]] = []
    for row in manifest["jobs"]:
        job_id = str(row["jobId"])
        job = _load_current_input(jobs_root, job_id)
        output, output_bytes = _read_output(
            jobs_root / "outputs" / f"{job_id}.jsonl"
        )
        _validate_output(job, output, loaded)
        if not _valid_marker_matches(jobs_root, job, output_bytes):
            raise BuildError(f"job 尚未通过 validate-job：{job_id}")
        validated.append((job, output))
    assets = _merge_outputs(loaded, validated)
    return _preflight_and_swap(loaded, assets)


def _build_job_inputs(workspace: WikiWorkspace) -> tuple[dict[str, object], ...]:
    database = sqlite3.connect(workspace.database_path)
    try:
        leaves = database.execute(
            """
            SELECT
                documents.document_id,
                documents.title,
                documents.ordinal,
                sections.section_id,
                sections.parent_section_id,
                sections.title,
                sections.path,
                sections.ordinal
            FROM sections
            JOIN documents USING(document_id)
            WHERE EXISTS(
                SELECT 1 FROM chunks WHERE chunks.section_id=sections.section_id
            )
              AND NOT EXISTS(
                SELECT 1 FROM sections AS child
                WHERE child.parent_section_id=sections.section_id
            )
            ORDER BY documents.ordinal, sections.ordinal, sections.section_id
            """
        ).fetchall()
        if not leaves:
            raise BuildError("history workspace 没有包含原文 chunk 的叶级 section")
        result: list[dict[str, object]] = []
        for leaf_index, leaf in enumerate(leaves):
            (
                document_id,
                document_title,
                _document_ordinal,
                section_id,
                parent_section_id,
                section_title,
                section_path,
                _section_ordinal,
            ) = leaf
            chunk_rows = database.execute(
                """
                SELECT chunk_id, original_text, content_hash, locator_json, ordinal
                FROM chunks
                WHERE section_id=?
                ORDER BY ordinal, chunk_id
                """,
                (section_id,),
            ).fetchall()
            chunks = [
                {
                    "chunkId": row[0],
                    "text": row[1],
                    "contentHash": row[2],
                    "locator": json.loads(row[3]),
                    "ordinal": row[4],
                }
                for row in chunk_rows
            ]
            parts = _partition_chunks(chunks)
            previous_heading = (
                str(leaves[leaf_index - 1][5])[:MAX_CONTEXT_HEADING_CHARS]
                if leaf_index > 0 and leaves[leaf_index - 1][0] == document_id
                else None
            )
            next_heading = (
                str(leaves[leaf_index + 1][5])[:MAX_CONTEXT_HEADING_CHARS]
                if leaf_index + 1 < len(leaves)
                and leaves[leaf_index + 1][0] == document_id
                else None
            )
            for part_index, part in enumerate(parts, start=1):
                payload = {
                    "type": "hwiki-history-enrichment-job",
                    "schemaVersion": 1,
                    "profile": PROFILE_ID,
                    "promptVersion": PROMPT_VERSION,
                    "wiki": {
                        "id": workspace.wiki_id,
                        "version": workspace.version,
                        "conceptNamespace": workspace.concept_namespace,
                    },
                    "scope": {
                        "documentId": document_id,
                        "documentTitle": document_title,
                        "sectionId": section_id,
                        "parentSectionId": parent_section_id,
                        "sectionTitle": section_title,
                        "sectionPath": section_path,
                        "partNumber": part_index,
                        "partCount": len(parts),
                    },
                    "context": {
                        "parentSummary": None,
                        "previousHeading": previous_heading,
                        "nextHeading": next_heading,
                    },
                    "sourceChars": sum(len(chunk["text"]) for chunk in part),
                    "chunks": part,
                    "outputSchema": OUTPUT_SCHEMA_ID,
                }
                input_hash = hashlib.sha256(canonical_json_bytes(payload)).hexdigest()
                job_id = stable_id(
                    "history-job",
                    workspace.wiki_id,
                    PROFILE_ID,
                    PROMPT_VERSION,
                    section_id,
                    part_index,
                    input_hash,
                )
                result.append(
                    {
                        **payload,
                        "jobId": job_id,
                        "inputHash": input_hash,
                    }
                )
        return tuple(result)
    finally:
        database.close()


def _partition_chunks(
    chunks: list[dict[str, object]],
) -> tuple[list[dict[str, object]], ...]:
    parts: list[list[dict[str, object]]] = []
    current: list[dict[str, object]] = []
    current_chars = 0
    for chunk in chunks:
        length = len(str(chunk["text"]))
        if length > MAX_SOURCE_CHARS:
            raise BuildError(
                f"单个 chunk 超过 {MAX_SOURCE_CHARS} 字，需先调整原文分块：{chunk['chunkId']}"
            )
        if current and current_chars + length > MAX_SOURCE_CHARS:
            parts.append(current)
            current = []
            current_chars = 0
        current.append(chunk)
        current_chars += length
    if current:
        parts.append(current)
    if not parts:
        raise BuildError("叶级 section 不得生成空语义 job")
    return tuple(parts)


def _validate_output(
    job: dict[str, object],
    output: dict[str, object],
    workspace: WikiWorkspace,
) -> None:
    _fields(
        output,
        {
            "type",
            "schemaVersion",
            "jobId",
            "inputHash",
            "profile",
            "promptVersion",
            "sectionSummary",
            "concepts",
            "annotations",
            "links",
        },
        "job output",
    )
    expected = {
        "type": "hwiki-history-enrichment-output",
        "schemaVersion": 1,
        "jobId": job["jobId"],
        "inputHash": job["inputHash"],
        "profile": PROFILE_ID,
        "promptVersion": PROMPT_VERSION,
    }
    for field, value in expected.items():
        if output[field] != value:
            raise BuildError(f"job output {field} 与 input 不一致")
    chunk_by_id = {str(chunk["chunkId"]): chunk for chunk in job["chunks"]}
    summary = _mapping(output["sectionSummary"], "sectionSummary")
    _fields(summary, {"text", "evidence"}, "sectionSummary")
    _text(summary["text"], "sectionSummary.text")
    _evidence(summary["evidence"], chunk_by_id, "sectionSummary")

    concepts = _list(output["concepts"], "concepts")
    concept_keys: set[str] = set()
    for index, raw_concept in enumerate(concepts):
        label = f"concepts[{index}]"
        concept = _mapping(raw_concept, label)
        _fields(
            concept,
            {
                "conceptKey",
                "kind",
                "canonicalText",
                "confidence",
                "reviewState",
                "evidence",
                "aliases",
                "mentions",
            },
            label,
        )
        concept_key = _concept_key(
            concept["conceptKey"],
            concept["kind"],
            workspace.concept_namespace,
            label,
        )
        if concept_key in concept_keys:
            raise BuildError(f"job output conceptKey 重复：{concept_key}")
        concept_keys.add(concept_key)
        _text(concept["canonicalText"], f"{label}.canonicalText")
        confidence = _confidence(concept["confidence"], f"{label}.confidence")
        review_state = _text(concept["reviewState"], f"{label}.reviewState")
        if review_state not in _REVIEW_STATES:
            raise BuildError(f"{label}.reviewState 不受支持")
        if confidence < HIGH_CONFIDENCE_IDENTITY and review_state != "unresolved":
            raise BuildError(f"{label} 低置信 concept 必须 unresolved")
        _evidence(concept["evidence"], chunk_by_id, label)
        aliases = _list(concept["aliases"], f"{label}.aliases")
        for alias_index, raw_alias in enumerate(aliases):
            alias_label = f"{label}.aliases[{alias_index}]"
            alias = _mapping(raw_alias, alias_label)
            _fields(alias, {"text", "confidence", "evidence"}, alias_label)
            _text(alias["text"], f"{alias_label}.text")
            _confidence(alias["confidence"], f"{alias_label}.confidence")
            _evidence(alias["evidence"], chunk_by_id, alias_label)
        mentions = _list(concept["mentions"], f"{label}.mentions")
        for mention_index, raw_mention in enumerate(mentions):
            mention_label = f"{label}.mentions[{mention_index}]"
            mention = _mapping(raw_mention, mention_label)
            _fields(
                mention,
                {"chunkId", "startOffset", "endOffset", "text", "confidence"},
                mention_label,
            )
            chunk_id = _text(mention["chunkId"], f"{mention_label}.chunkId")
            if chunk_id not in chunk_by_id:
                raise BuildError(f"{mention_label} chunk 越界")
            start = _integer(mention["startOffset"], f"{mention_label}.startOffset")
            end = _integer(mention["endOffset"], f"{mention_label}.endOffset")
            mention_text = _text(mention["text"], f"{mention_label}.text")
            source_text = str(chunk_by_id[chunk_id]["text"])
            if start < 0 or end <= start or source_text[start:end] != mention_text:
                raise BuildError(f"{mention_label} offset 与 job 原文不一致")
            _confidence(mention["confidence"], f"{mention_label}.confidence")

    annotations = _list(output["annotations"], "annotations")
    for index, raw_annotation in enumerate(annotations):
        label = f"annotations[{index}]"
        annotation = _mapping(raw_annotation, label)
        _fields(
            annotation,
            {
                "ownerChunkId",
                "kind",
                "value",
                "confidence",
                "extractorVersion",
                "evidence",
            },
            label,
        )
        owner = _text(annotation["ownerChunkId"], f"{label}.ownerChunkId")
        if owner not in chunk_by_id:
            raise BuildError(f"{label} owner chunk 越界")
        _token(annotation["kind"], f"{label}.kind")
        if not isinstance(annotation["value"], dict):
            raise BuildError(f"{label}.value 必须是对象")
        _confidence(annotation["confidence"], f"{label}.confidence")
        _token(annotation["extractorVersion"], f"{label}.extractorVersion")
        _evidence(annotation["evidence"], chunk_by_id, label)

    links = _list(output["links"], "links")
    for index, raw_link in enumerate(links):
        label = f"links[{index}]"
        link = _mapping(raw_link, label)
        _fields(
            link,
            {
                "sourceConceptKey",
                "targetNamespace",
                "targetConceptKey",
                "kind",
                "confidence",
                "routingMode",
                "extractorVersion",
                "evidence",
            },
            label,
        )
        source_key = _text(link["sourceConceptKey"], f"{label}.sourceConceptKey")
        if source_key not in concept_keys:
            raise BuildError(f"{label} sourceConceptKey 不在当前 job concepts")
        _text(link["targetNamespace"], f"{label}.targetNamespace")
        _text(link["targetConceptKey"], f"{label}.targetConceptKey")
        _token(link["kind"], f"{label}.kind")
        confidence = _confidence(link["confidence"], f"{label}.confidence")
        routing_mode = _token(link["routingMode"], f"{label}.routingMode")
        if confidence < LOW_CONFIDENCE_LINK and routing_mode != "weak-only":
            raise BuildError("低置信 link 只能使用 weak-only，不能 hard-filter")
        _token(link["extractorVersion"], f"{label}.extractorVersion")
        _evidence(link["evidence"], chunk_by_id, label)


def _merge_outputs(
    workspace: WikiWorkspace,
    validated: list[tuple[dict[str, object], dict[str, object]]],
) -> dict[str, tuple[dict[str, object], ...]]:
    by_section: dict[str, list[tuple[dict[str, object], dict[str, object]]]] = defaultdict(list)
    concept_candidates = []
    for job, output in validated:
        by_section[str(job["scope"]["sectionId"])].append((job, output))
        for concept in output["concepts"]:
            concept_candidates.append(
                {
                    "wikiId": workspace.wiki_id,
                    "conceptKey": concept["conceptKey"],
                    "kind": concept["kind"],
                    "canonicalText": concept["canonicalText"],
                    "aliases": [alias["text"] for alias in concept["aliases"]],
                    "confidence": concept["confidence"],
                    "reviewState": concept["reviewState"],
                    "evidence": concept["evidence"],
                }
            )
    registry = merge_concept_candidates(
        concept_candidates,
        workspace.concept_namespace,
    )
    registry_by_key = {row["conceptKey"]: row for row in registry}

    summaries: list[dict[str, object]] = []
    section_rollups: dict[str, dict[str, object]] = {}
    for section_id in sorted(by_section):
        parts = sorted(
            by_section[section_id],
            key=lambda pair: int(pair[0]["scope"]["partNumber"]),
        )
        expected_parts = int(parts[0][0]["scope"]["partCount"])
        if len(parts) != expected_parts:
            raise BuildError(f"section job 未完整：{section_id}")
        texts = [str(output["sectionSummary"]["text"]) for _, output in parts]
        evidence = sorted(
            {
                chunk_id
                for _, output in parts
                for chunk_id in output["sectionSummary"]["evidence"]
            }
        )
        if expected_parts > 1:
            for job, output in parts:
                first_chunk = str(job["chunks"][0]["chunkId"])
                summaries.append(
                    {
                        "id": stable_id("summary", workspace.wiki_id, job["jobId"]),
                        "ownerType": "chunk",
                        "ownerId": first_chunk,
                        "level": "leaf-part",
                        "text": output["sectionSummary"]["text"],
                        "evidence": output["sectionSummary"]["evidence"],
                    }
                )
        scope = parts[0][0]["scope"]
        rollup = {
            "id": stable_id("summary", workspace.wiki_id, section_id, "leaf"),
            "ownerType": "section",
            "ownerId": section_id,
            "level": "leaf",
            "text": "\n".join(texts),
            "evidence": evidence,
        }
        summaries.append(rollup)
        section_rollups[section_id] = {
            **rollup,
            "documentId": scope["documentId"],
            "sectionTitle": scope["sectionTitle"],
        }
    by_document: dict[str, list[dict[str, object]]] = defaultdict(list)
    for rollup in section_rollups.values():
        by_document[str(rollup["documentId"])].append(rollup)
    for document_id in sorted(by_document):
        rollups = sorted(by_document[document_id], key=lambda row: str(row["ownerId"]))
        summaries.append(
            {
                "id": stable_id("summary", workspace.wiki_id, document_id, "document"),
                "ownerType": "document",
                "ownerId": document_id,
                "level": "document",
                "text": "\n".join(
                    f"{row['sectionTitle']}：{row['text']}" for row in rollups
                ),
                "evidence": sorted(
                    {chunk for row in rollups for chunk in row["evidence"]}
                ),
            }
        )

    concepts_by_key: dict[str, list[dict[str, object]]] = defaultdict(list)
    for _, output in validated:
        for concept in output["concepts"]:
            concepts_by_key[str(concept["conceptKey"])].append(concept)
    terms = []
    aliases = []
    mentions = []
    term_ids: dict[str, str] = {}
    for concept_key in sorted(concepts_by_key):
        group = concepts_by_key[concept_key]
        registry_row = registry_by_key[concept_key]
        term_id = stable_id("term", workspace.wiki_id, concept_key)
        term_ids[concept_key] = term_id
        terms.append(
            {
                "id": term_id,
                "conceptKey": concept_key,
                "canonicalText": registry_row["canonicalText"],
                "kind": registry_row["kind"],
                "confidence": max(float(item["confidence"]) for item in group),
                "metadata": {"reviewState": registry_row["reviewState"]},
                "evidence": sorted(
                    {chunk for item in group for chunk in item["evidence"]}
                ),
            }
        )
        alias_groups: dict[str, list[dict[str, object]]] = defaultdict(list)
        for item in group:
            for alias in item["aliases"]:
                alias_groups[normalize_for_search(str(alias["text"]))].append(alias)
            for mention in item["mentions"]:
                mentions.append(
                    {
                        "id": stable_id(
                            "mention",
                            workspace.wiki_id,
                            term_id,
                            mention["chunkId"],
                            mention["startOffset"],
                            mention["endOffset"],
                        ),
                        "termId": term_id,
                        **mention,
                    }
                )
        for normalized_alias in sorted(alias_groups):
            alias_group = alias_groups[normalized_alias]
            texts = {str(alias["text"]) for alias in alias_group}
            if len(texts) != 1:
                raise BuildError(f"规范化别名文本冲突：{concept_key}/{normalized_alias}")
            alias_text = next(iter(texts))
            aliases.append(
                {
                    "id": stable_id("alias", workspace.wiki_id, term_id, normalized_alias),
                    "termId": term_id,
                    "aliasText": alias_text,
                    "confidence": max(float(alias["confidence"]) for alias in alias_group),
                    "evidence": sorted(
                        {chunk for alias in alias_group for chunk in alias["evidence"]}
                    ),
                }
            )

    annotations = []
    links = []
    for job, output in validated:
        for annotation in output["annotations"]:
            annotations.append(
                {
                    "id": stable_id(
                        "annotation",
                        workspace.wiki_id,
                        annotation["ownerChunkId"],
                        annotation["kind"],
                        canonical_json_bytes(annotation["value"]).hex(),
                    ),
                    "ownerType": "chunk",
                    "ownerId": annotation["ownerChunkId"],
                    "kind": annotation["kind"],
                    "value": annotation["value"],
                    "confidence": annotation["confidence"],
                    "evidence": annotation["evidence"],
                }
            )
        for link in output["links"]:
            source_key = str(link["sourceConceptKey"])
            if source_key not in term_ids:
                raise BuildError(f"link source concept 未进入 registry：{source_key}")
            links.append(
                {
                    "id": stable_id(
                        "link",
                        workspace.wiki_id,
                        source_key,
                        link["targetNamespace"],
                        link["targetConceptKey"],
                        link["kind"],
                    ),
                    "sourceType": "term",
                    "sourceId": term_ids[source_key],
                    "targetNamespace": link["targetNamespace"],
                    "targetType": "concept",
                    "targetId": link["targetConceptKey"],
                    "kind": link["kind"],
                    "confidence": link["confidence"],
                    "metadata": {
                        "routingMode": link["routingMode"],
                        "extractorVersion": link["extractorVersion"],
                    },
                    "evidence": link["evidence"],
                }
            )

    return {
        "concept-registry.jsonl": tuple(registry),
        "summaries.jsonl": _dedupe_sorted(summaries),
        "terms.jsonl": _dedupe_sorted(terms),
        "aliases.jsonl": _dedupe_sorted(aliases),
        "mentions.jsonl": _dedupe_sorted(mentions),
        "annotations.jsonl": _dedupe_sorted(annotations),
        "links.jsonl": _dedupe_sorted(links),
    }


def _preflight_and_swap(
    workspace: WikiWorkspace,
    assets: dict[str, tuple[dict[str, object], ...]],
) -> EnrichmentStats:
    temporary_root = Path(
        tempfile.mkdtemp(prefix=".history-merge-", dir=workspace.root.parent)
    )
    backup_root = Path(
        tempfile.mkdtemp(prefix=".history-merge-backup-", dir=workspace.root.parent)
    )
    swapped_database = False
    swapped_enrichment = False
    try:
        shutil.copy2(workspace.root / "workspace.json", temporary_root / "workspace.json")
        shutil.copy2(workspace.database_path, temporary_root / "content.sqlite")
        staging_enrichment = temporary_root / "enrichment"
        staging_enrichment.mkdir()
        for file_name in ENRICHMENT_FILE_NAMES:
            rows = assets[file_name]
            with (staging_enrichment / file_name).open("xb") as stream:
                for row in rows:
                    stream.write(canonical_json_bytes(row) + b"\n")
        stats = import_enrichment(temporary_root)

        database_backup = backup_root / "content.sqlite"
        enrichment_backup = backup_root / "enrichment"
        os.replace(workspace.database_path, database_backup)
        swapped_database = True
        os.replace(workspace.enrichment_path, enrichment_backup)
        swapped_enrichment = True
        os.replace(temporary_root / "content.sqlite", workspace.database_path)
        os.replace(staging_enrichment, workspace.enrichment_path)
        return stats
    except BaseException:
        if swapped_enrichment:
            if workspace.enrichment_path.exists():
                shutil.rmtree(workspace.enrichment_path, ignore_errors=True)
            if (backup_root / "enrichment").exists():
                os.replace(backup_root / "enrichment", workspace.enrichment_path)
        if swapped_database:
            workspace.database_path.unlink(missing_ok=True)
            if (backup_root / "content.sqlite").exists():
                os.replace(backup_root / "content.sqlite", workspace.database_path)
        raise
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)
        shutil.rmtree(backup_root, ignore_errors=True)


def _reuse_matching_job(
    old_root: Path,
    staging: Path,
    job_id: str,
    input_bytes: bytes,
    job: dict[str, object],
) -> None:
    if not old_root.is_dir() or old_root.is_symlink():
        return
    old_input = old_root / "inputs" / f"{job_id}.json"
    old_output = old_root / "outputs" / f"{job_id}.jsonl"
    old_marker = old_root / "valid" / f"{job_id}.json"
    if not _regular_bytes_equal(old_input, input_bytes):
        return
    if old_output.is_file() and not old_output.is_symlink():
        shutil.copy2(old_output, staging / "outputs" / old_output.name)
    if old_marker.is_file() and not old_marker.is_symlink() and old_output.is_file():
        try:
            output, output_bytes = _read_output(old_output)
            loaded = load_workspace(old_root.parent)
            _validate_output(job, output, loaded)
            marker = json.loads(old_marker.read_bytes())
            if _marker_payload_matches(marker, job, output_bytes):
                shutil.copy2(old_marker, staging / "valid" / old_marker.name)
        except (BuildError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            pass


def _load_current_input(root: Path, job_id: str) -> dict[str, object]:
    if not _TOKEN.fullmatch(job_id):
        raise BuildError(f"jobId 无效：{job_id}")
    manifest = _load_manifest(root)
    known = {str(row["jobId"]) for row in manifest["jobs"]}
    if job_id not in known:
        raise BuildError(f"jobId 不在当前 manifest：{job_id}")
    path = root / "inputs" / f"{job_id}.json"
    if path.is_symlink() or not path.is_file():
        raise BuildError(f"job input 不可安全读取：{job_id}")
    raw = path.read_bytes()
    try:
        job = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BuildError(f"job input JSON 无效：{job_id}") from error
    if not isinstance(job, dict) or raw != canonical_json_bytes(job):
        raise BuildError(f"job input 不是规范 JSON：{job_id}")
    payload = {key: value for key, value in job.items() if key not in {"jobId", "inputHash"}}
    input_hash = hashlib.sha256(canonical_json_bytes(payload)).hexdigest()
    if job.get("jobId") != job_id or job.get("inputHash") != input_hash:
        raise BuildError(f"job input hash 无效：{job_id}")
    return job


def _load_manifest(root: Path) -> dict[str, object]:
    path = root / "manifest.json"
    if path.is_symlink() or not path.is_file():
        raise BuildError("缺少安全的 history job manifest")
    raw = path.read_bytes()
    try:
        manifest = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BuildError("history job manifest JSON 无效") from error
    if not isinstance(manifest, dict) or raw != canonical_json_bytes(manifest):
        raise BuildError("history job manifest 不是规范 JSON")
    if (
        manifest.get("type") != "hwiki-history-job-manifest"
        or manifest.get("schemaVersion") != 1
        or manifest.get("profile") != PROFILE_ID
        or manifest.get("promptVersion") != PROMPT_VERSION
        or not isinstance(manifest.get("jobs"), list)
    ):
        raise BuildError("history job manifest 协议无效")
    return manifest


def _read_output(path: Path) -> tuple[dict[str, object], bytes]:
    if path.is_symlink() or not path.is_file():
        raise BuildError(f"job output 不可安全读取：{path.name}")
    raw = path.read_bytes()
    if not raw.endswith(b"\n") or raw.count(b"\n") != 1:
        raise BuildError("job output 必须是单行且带换行的规范 JSONL")
    try:
        output = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BuildError("job output 不是有效 JSONL") from error
    if not isinstance(output, dict) or raw != canonical_json_bytes(output) + b"\n":
        raise BuildError("job output 不是规范 JSONL")
    return output, raw


def _job_plan(root: Path, job_ids: tuple[str, ...]) -> JobPlan:
    valid = []
    for job_id in job_ids:
        job = _load_current_input(root, job_id)
        output_path = root / "outputs" / f"{job_id}.jsonl"
        try:
            _, output_bytes = _read_output(output_path)
        except BuildError:
            continue
        if _valid_marker_matches(root, job, output_bytes):
            valid.append(job_id)
    valid_set = set(valid)
    return JobPlan(
        job_ids=job_ids,
        valid_job_ids=tuple(valid),
        pending_job_ids=tuple(job_id for job_id in job_ids if job_id not in valid_set),
    )


def _valid_marker_matches(
    root: Path,
    job: dict[str, object],
    output_bytes: bytes,
) -> bool:
    marker_path = root / "valid" / f"{job['jobId']}.json"
    if marker_path.is_symlink() or not marker_path.is_file():
        return False
    try:
        marker = json.loads(marker_path.read_bytes())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return False
    return _marker_payload_matches(marker, job, output_bytes)


def _marker_payload_matches(
    marker: object,
    job: dict[str, object],
    output_bytes: bytes,
) -> bool:
    return marker == {
        "type": "hwiki-history-valid-job",
        "schemaVersion": 1,
        "jobId": job["jobId"],
        "inputHash": job["inputHash"],
        "outputHash": hashlib.sha256(output_bytes).hexdigest(),
        "profile": PROFILE_ID,
        "promptVersion": PROMPT_VERSION,
    }


def _dedupe_sorted(rows: list[dict[str, object]]) -> tuple[dict[str, object], ...]:
    by_id: dict[str, dict[str, object]] = {}
    for row in rows:
        row_id = str(row["id"])
        previous = by_id.get(row_id)
        if previous is not None and previous != row:
            raise BuildError(f"语义资产稳定 ID 冲突：{row_id}")
        by_id[row_id] = row
    return tuple(by_id[row_id] for row_id in sorted(by_id))


def _replace_directory(target: Path, staging: Path) -> None:
    backup = target.with_name(f".{target.name}.backup-{os.getpid()}")
    if backup.exists():
        raise BuildError(f"残留 job backup：{backup}")
    moved_old = False
    try:
        if target.exists():
            os.replace(target, backup)
            moved_old = True
        os.replace(staging, target)
        if moved_old:
            shutil.rmtree(backup)
    except BaseException:
        if not target.exists() and moved_old and backup.exists():
            os.replace(backup, target)
        raise


def _regular_bytes_equal(path: Path, expected: bytes) -> bool:
    return path.is_file() and not path.is_symlink() and path.read_bytes() == expected


def _atomic_write(path: Path, payload: bytes) -> None:
    target = Path(path)
    temporary = target.with_name(f".{target.name}.tmp-{os.getpid()}")
    try:
        with temporary.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def _require_history_workspace(workspace: WikiWorkspace, profile: str) -> None:
    if profile != PROFILE_ID:
        raise BuildError(f"history profile 必须是 {PROFILE_ID}")
    if workspace.builder_profile != "history-v1":
        raise BuildError("语义 history jobs 只支持 history-v1 工作区")


def _fields(value: dict[str, object], required: set[str], label: str) -> None:
    unknown = sorted(set(value) - required)
    missing = sorted(required - set(value))
    if unknown or missing:
        raise BuildError(f"{label} 字段无效：{', '.join(unknown or missing)}")


def _mapping(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise BuildError(f"{label} 必须是对象")
    return value


def _list(value: object, label: str) -> list[object]:
    if not isinstance(value, list):
        raise BuildError(f"{label} 必须是数组")
    return value


def _text(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BuildError(f"{label} 必须是非空字符串")
    return value


def _token(value: object, label: str) -> str:
    result = _text(value, label)
    if not _TOKEN.fullmatch(result):
        raise BuildError(f"{label} 必须是规范 token")
    return result


def _integer(value: object, label: str) -> int:
    if type(value) is not int:
        raise BuildError(f"{label} 必须是整数")
    return value


def _confidence(value: object, label: str) -> float:
    if type(value) not in {int, float} or not math.isfinite(value) or not 0 <= value <= 1:
        raise BuildError(f"{label} 必须是 0 到 1 的有限数值")
    return float(value)


def _concept_key(value: object, kind_value: object, namespace: str, label: str) -> str:
    concept_key = _text(value, f"{label}.conceptKey")
    kind = _text(kind_value, f"{label}.kind")
    parts = concept_key.split(":")
    if (
        kind not in CONCEPT_KINDS
        or len(parts) != 3
        or parts[0] != namespace
        or parts[1] != kind
        or not _TOKEN.fullmatch(parts[2])
    ):
        raise BuildError(f"{label} conceptKey/kind 无效：{concept_key}")
    return concept_key


def _evidence(
    value: object,
    chunk_by_id: dict[str, dict[str, object]],
    label: str,
) -> tuple[str, ...]:
    if not isinstance(value, list) or not value or any(
        not isinstance(item, str) or not item.strip() for item in value
    ):
        raise BuildError(f"{label} evidence 必须是非空 chunkId 数组")
    if len(value) != len(set(value)):
        raise BuildError(f"{label} evidence 重复")
    outside = sorted(set(value) - set(chunk_by_id))
    if outside:
        raise BuildError(f"{label} evidence 越界，不属于当前 job chunk：{outside[0]}")
    return tuple(value)


__all__ = [
    "JOBS_DIRECTORY_NAME",
    "JobPlan",
    "JobValidation",
    "create_jobs",
    "merge_jobs",
    "validate_job",
]
