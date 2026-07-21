import sqlite3
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.builder import prepare_workspace
from tools.wiki_builder.enrichment import import_enrichment


def build_publishable_workspace(root: Path) -> tuple[Path, dict[str, str]]:
    source = root / "source.md"
    source.write_text(
        "# 原文\n\n司馬光論禮制。\n\n"
        "# 摘要证据\n\n庫藏記錄甲。\n\n"
        "# 别名证据\n\n君實在此。",
        encoding="utf-8",
    )
    workspace = prepare_workspace(
        [source],
        root / "workspace",
        "fixture.history",
        "史料测试库",
        1,
        "fixture-v1",
    )
    ids = write_fixture_enrichment(workspace)
    import_enrichment(workspace)
    return workspace, ids


def write_fixture_enrichment(workspace: Path) -> dict[str, str]:
    with sqlite3.connect(workspace / "content.sqlite") as database:
        document_id = database.execute("SELECT document_id FROM documents").fetchone()[0]
        rows = database.execute(
            """
            SELECT sections.section_id, sections.title, chunks.chunk_id, chunks.original_text
            FROM sections JOIN chunks USING(section_id)
            ORDER BY sections.ordinal
            """
        ).fetchall()
    by_title = {
        title: {"section": section_id, "chunk": chunk_id, "text": text}
        for section_id, title, chunk_id, text in rows
    }
    concept_key = "fixture-v1:person:sima-guang"
    write_jsonl(
        workspace / "enrichment/concept-registry.jsonl",
        [{"conceptKey": concept_key, "kind": "person", "canonicalText": "司马光"}],
    )
    summaries = [
        {
            "id": "summary-document",
            "ownerType": "document",
            "ownerId": document_id,
            "level": "document",
            "text": "全书测试摘要。",
            "evidence": [by_title["原文"]["chunk"]],
        },
        {
            "id": "summary-section-alias",
            "ownerType": "section",
            "ownerId": by_title["别名证据"]["section"],
            "level": "volume",
            "text": "人物字号记录。",
            "evidence": [by_title["别名证据"]["chunk"]],
        },
        {
            "id": "summary-section-original",
            "ownerType": "section",
            "ownerId": by_title["原文"]["section"],
            "level": "volume",
            "text": "礼制讨论。",
            "evidence": [by_title["原文"]["chunk"]],
        },
        {
            "id": "summary-section-semantic",
            "ownerType": "section",
            "ownerId": by_title["摘要证据"]["section"],
            "level": "volume",
            "text": "财政制度专题。",
            "evidence": [by_title["摘要证据"]["chunk"]],
        },
    ]
    write_jsonl(workspace / "enrichment/summaries.jsonl", summaries)
    write_jsonl(
        workspace / "enrichment/terms.jsonl",
        [
            {
                "id": "term-sima-guang",
                "conceptKey": concept_key,
                "canonicalText": "司马光",
                "kind": "person",
                "confidence": 1.0,
                "evidence": [by_title["别名证据"]["chunk"]],
            }
        ],
    )
    write_jsonl(
        workspace / "enrichment/aliases.jsonl",
        [
            {
                "id": "alias-junshi",
                "termId": "term-sima-guang",
                "aliasText": "君实",
                "confidence": 0.9,
                "evidence": [by_title["别名证据"]["chunk"]],
            }
        ],
    )
    alias_text = by_title["别名证据"]["text"]
    start = alias_text.index("君實")
    write_jsonl(
        workspace / "enrichment/mentions.jsonl",
        [
            {
                "id": "mention-junshi",
                "termId": "term-sima-guang",
                "chunkId": by_title["别名证据"]["chunk"],
                "startOffset": start,
                "endOffset": start + 2,
                "text": "君實",
                "confidence": 1.0,
            }
        ],
    )
    evaluation = workspace / "evaluation"
    evaluation.mkdir()
    cases = [
        {
            "caseId": "alias",
            "category": "alias",
            "query": "君实",
            "expectedChunkIds": [by_title["别名证据"]["chunk"]],
        },
        {
            "caseId": "normalized",
            "category": "normalized",
            "query": "司马光礼制",
            "expectedChunkIds": [by_title["原文"]["chunk"]],
        },
        {
            "caseId": "original",
            "category": "original",
            "query": "司馬光禮制",
            "expectedChunkIds": [by_title["原文"]["chunk"]],
        },
        {
            "caseId": "summary",
            "category": "summary",
            "query": "财政制度",
            "expectedChunkIds": [by_title["摘要证据"]["chunk"]],
        },
    ]
    write_jsonl(evaluation / "retrieval-eval.jsonl", cases)
    return {
        "document": document_id,
        "original": by_title["原文"]["chunk"],
        "summary": by_title["摘要证据"]["chunk"],
        "alias": by_title["别名证据"]["chunk"],
    }


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.write_bytes(b"".join(canonical_json_bytes(row) + b"\n" for row in rows))
