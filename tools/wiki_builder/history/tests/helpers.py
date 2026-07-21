import hashlib
from pathlib import Path

from tools.wiki_builder.extractors import stable_id
from tools.wiki_builder.history.models import (
    HistoryDocumentRecord,
    HistoryParagraphRecord,
    HistorySectionRecord,
)
from tools.wiki_builder.history.workspace_builder import populate_history_workspace


def build_history_workspace(
    root: Path,
    *,
    wiki_id: str = "fixture.history.jobs",
    leaf_texts: tuple[tuple[str, ...], ...] = (
        ("前403年，司马光记载甲事。",),
        ("赵氏在晋阳记载乙事。",),
    ),
) -> Path:
    document_id = stable_id("document", wiki_id, "fixture-revision")
    sections: list[HistorySectionRecord] = []
    section_ordinal = 0
    for leaf_number, texts in enumerate(leaf_texts, start=1):
        parent_id = stable_id("section", wiki_id, leaf_number, "parent")
        leaf_id = stable_id("section", wiki_id, leaf_number, "leaf")
        sections.append(
            HistorySectionRecord(
                section_id=parent_id,
                document_id=document_id,
                parent_section_id=None,
                title=f"第{leaf_number}卷",
                path=f"测试史/{leaf_number:03d}",
                ordinal=section_ordinal,
                source_path=None,
                source_hash=None,
            )
        )
        section_ordinal += 1
        paragraphs = []
        for paragraph_number, text in enumerate(texts, start=1):
            source_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
            paragraphs.append(
                HistoryParagraphRecord(
                    paragraph_id=stable_id(
                        "source-record",
                        wiki_id,
                        leaf_number,
                        paragraph_number,
                        source_hash,
                    ),
                    text=text,
                    ordinal=paragraph_number - 1,
                    locator={
                        "documentTitle": "测试史",
                        "chapterTitle": f"第{leaf_number}章",
                        "paragraphNumber": paragraph_number,
                        "sourcePath": f"chapters/{leaf_number:03d}.md",
                        "sourceHash": source_hash,
                    },
                    source_hash=source_hash,
                )
            )
        sections.append(
            HistorySectionRecord(
                section_id=leaf_id,
                document_id=document_id,
                parent_section_id=parent_id,
                title=f"第{leaf_number}章",
                path=f"测试史/{leaf_number:03d}/第{leaf_number}章",
                ordinal=section_ordinal,
                source_path=f"chapters/{leaf_number:03d}.md",
                source_hash=hashlib.sha256("".join(texts).encode("utf-8")).hexdigest(),
                paragraphs=tuple(paragraphs),
            )
        )
        section_ordinal += 1
    document = HistoryDocumentRecord(
        document_id=document_id,
        title="测试史",
        ordinal=0,
        source_path=Path("SUMMARY.md"),
        source_hash=hashlib.sha256(
            "".join(text for leaf in leaf_texts for text in leaf).encode("utf-8")
        ).hexdigest(),
        source_size_bytes=sum(
            len(text.encode("utf-8")) for leaf in leaf_texts for text in leaf
        ),
        source_format="md",
        sections=tuple(sections),
    )
    return populate_history_workspace(
        (document,),
        root,
        wiki_id=wiki_id,
        title="测试史",
        version=1,
        concept_namespace="cn-history-v1",
        source_id="fixture-history",
        source_revision="a" * 40,
        exclusions=("fixture",),
    )
