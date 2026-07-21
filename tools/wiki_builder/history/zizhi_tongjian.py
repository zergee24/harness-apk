"""Strict source-only adapter for the paired Zizhi Tongjian Markdown."""

from __future__ import annotations

import hashlib
import os
import re
import stat
import unicodedata
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit

from tools.package_format import canonical_json_bytes

from ..extractors import stable_id
from ..models import BuildError
from .models import (
    HistoryDocumentRecord,
    HistoryExclusionAudit,
    HistoryParagraphRecord,
    HistorySectionRecord,
)
from .source_inventory import (
    SourceInventory,
    SourceLock,
    ZIZHI_TONGJIAN_SOURCE_ID,
    inventory_history_repository,
)
from .workspace_builder import populate_history_workspace

PACKAGE_ID = "cn.history.zizhi-tongjian"
PACKAGE_TITLE = "资治通鉴"
PACKAGE_VERSION = 1
CONCEPT_NAMESPACE = "cn-history-v1"
VOLUME_COUNT = 294
TRANSLATION_EXCLUSION_REASON = "modern-translation-not-packaged-v1"
_BUFFER_BYTES = 1024 * 1024
_SUMMARY_LINK = re.compile(r"\[([^\]]+)\]\((.+)\)")
_CHAPTER_NAME = re.compile(r"([0-9]{3})_(.+)\.md\Z")
_VOLUME_NUMBER = re.compile(r"第([零〇一二三四五六七八九十百两0-9]+)卷")
_PAIR_MARKER = re.compile(r"\[([0-9]+)\]")
_YEAR = re.compile(r"(?:(公元)\s*)?(前)?\s*([0-9]{1,4})\s*年?")
_KNOWN_TITLE_MISMATCHES = {
    "chapters/007_资治通鉴第七卷(秦纪).md": (
        "资治通鉴第七卷(秦纪)",
        "资治通鉴第七卷(秦记)",
    ),
    "chapters/008_资治通鉴第八卷(秦纪).md": (
        "资治通鉴第八卷(秦纪)",
        "资治通鉴第八卷(秦记)",
    ),
}
_KNOWN_CROSSED_FIRST_PAIR_PATHS = {
    "chapters/017_资治通鉴第十七卷(汉纪).md",
}
_KNOWN_HEADING_WRAPPED_PAIR_PATHS = {
    "chapters/018_资治通鉴第十八卷(汉纪).md",
}
_KNOWN_UNINDENTED_TRANSLATION_PATHS = {
    "chapters/088_资治通鉴第八十八卷(晋纪).md",
    "chapters/104_资治通鉴第一百零四卷(晋纪).md",
    "chapters/251_资治通鉴第二百五十一卷(唐纪).md",
}
_KNOWN_YEAR_MISMATCHES = {
    (
        "chapters/233_资治通鉴第二百三十三卷(唐纪).md",
        "四年（戊辰、778",
        "四年（戊辰，公元788年）",
    ): 788,
    (
        "chapters/252_资治通鉴第二百五十二卷(唐纪).md",
        "十二年（辛卯、817）",
        "十二年（辛，公元871年）",
    ): 871,
    (
        "chapters/256_资治通鉴第二百五十六卷(唐纪).md",
        "三年（丁未、87）",
        "三年（丁未，公元887年）",
    ): 887,
    (
        "chapters/257_资治通鉴第二百五十七卷(唐纪).md",
        "文德元年（戊申、808）",
        "文德元年（戊申，公元888年）",
    ): 888,
}


class ZizhiTongjianAdapterError(BuildError):
    """Raised when paired Markdown cannot be classified without ambiguity."""


@dataclass(frozen=True)
class _SummaryEntry:
    volume_number: int
    label: str
    relative_path: str


@dataclass(frozen=True)
class _PairedLine:
    original: str
    translation: str
    original_line: int
    translation_line: int


@dataclass(frozen=True)
class _TimeSegment:
    heading: _PairedLine
    year: int
    paragraphs: tuple[_PairedLine, ...]
    pairing_mode: str = "standard"


def prepare_zizhi_tongjian(
    source: Path,
    workspace: Path,
    source_lock: SourceLock,
    include_translation: bool = False,
) -> Path:
    if include_translation:
        raise ZizhiTongjianAdapterError(
            "version 1 不允许打包译文；需新包版本与独立权利记录"
        )
    expected = _locked_source(source_lock)
    if expected.relevant_file_count != VOLUME_COUNT:
        raise ZizhiTongjianAdapterError(
            f"资治通鉴 source lock 必须恰好包含 {VOLUME_COUNT} 卷，"
            f"实际 {expected.relevant_file_count}"
        )
    inventory_history_repository(
        ZIZHI_TONGJIAN_SOURCE_ID,
        source,
        expected=expected,
    )
    root = Path(source).resolve()
    document = _build_document(root, expected)

    def verify_unchanged() -> None:
        inventory_history_repository(
            ZIZHI_TONGJIAN_SOURCE_ID,
            root,
            expected=expected,
        )

    return populate_history_workspace(
        (document,),
        workspace,
        wiki_id=PACKAGE_ID,
        title=PACKAGE_TITLE,
        version=PACKAGE_VERSION,
        concept_namespace=CONCEPT_NAMESPACE,
        source_id=ZIZHI_TONGJIAN_SOURCE_ID,
        source_revision=expected.git_revision,
        exclusions=(
            "现代白话译文正文",
            "译文仅保留 SHA-256 配对审计",
            "README、衍生数据与已有知识图谱",
        ),
        before_publish=verify_unchanged,
    )


def _build_document(root: Path, expected: SourceInventory) -> HistoryDocumentRecord:
    summary_path = root / "SUMMARY.md"
    summary_text, summary_hash, summary_size = _read_utf8(summary_path, root)
    entries = _parse_summary(summary_text)
    sections: list[HistorySectionRecord] = []
    source_descriptors: list[dict[str, object]] = [
        {"path": "SUMMARY.md", "sha256": summary_hash, "sizeBytes": summary_size}
    ]
    section_ordinal = 0
    paragraph_count = 0
    source_size = summary_size
    document_id = stable_id(
        "document",
        PACKAGE_ID,
        expected.git_revision,
        PACKAGE_TITLE,
    )
    for entry in entries:
        chapter_path = root.joinpath(*PurePosixPath(entry.relative_path).parts)
        chapter_text, chapter_hash, chapter_size = _read_utf8(chapter_path, root)
        volume_title, time_segments = _parse_chapter(chapter_text, entry.relative_path)
        _validate_volume_identity(entry, chapter_path.name, volume_title)
        source_descriptors.append(
            {
                "path": entry.relative_path,
                "sha256": chapter_hash,
                "sizeBytes": chapter_size,
            }
        )
        source_size += chapter_size
        volume_id = stable_id(
            "section",
            PACKAGE_ID,
            expected.git_revision,
            entry.relative_path,
            "volume",
        )
        volume_path = f"资治通鉴/{entry.volume_number:03d}/{volume_title}"
        sections.append(
            HistorySectionRecord(
                section_id=volume_id,
                document_id=document_id,
                parent_section_id=None,
                title=volume_title,
                path=volume_path,
                ordinal=section_ordinal,
                source_path=None,
                source_hash=None,
                metadata={
                    "volumeNumber": entry.volume_number,
                    "sourcePath": entry.relative_path,
                    "sourceHash": chapter_hash,
                },
            )
        )
        section_ordinal += 1
        volume_paragraph_number = 0
        for segment_number, segment in enumerate(time_segments, start=1):
            heading_hash = _text_hash(segment.heading.original)
            translation_heading_hash = _text_hash(segment.heading.translation)
            section_id = stable_id(
                "section",
                PACKAGE_ID,
                expected.git_revision,
                entry.relative_path,
                "time",
                segment_number,
                heading_hash,
            )
            heading_record_id = stable_id(
                "source-pair",
                PACKAGE_ID,
                expected.git_revision,
                entry.relative_path,
                segment.heading.original_line,
                "time-heading",
                heading_hash,
            )
            heading_locator = {
                "documentTitle": PACKAGE_TITLE,
                "chapterTitle": volume_title,
                "volumeNumber": entry.volume_number,
                "eraHeading": segment.heading.original,
                "year": segment.year,
                "blockType": "heading",
                "headingLevel": 2,
                "sourcePath": entry.relative_path,
                "sourceLine": segment.heading.original_line,
                "translationLine": segment.heading.translation_line,
                "sourceHash": heading_hash,
                "translationHash": translation_heading_hash,
                "translationExclusionReason": TRANSLATION_EXCLUSION_REASON,
            }
            paragraphs: list[HistoryParagraphRecord] = [
                HistoryParagraphRecord(
                    paragraph_id=heading_record_id,
                    text=segment.heading.original,
                    ordinal=0,
                    locator=heading_locator,
                    source_hash=heading_hash,
                    exclusion_audit=_exclusion_audit(
                        heading_record_id,
                        "time-heading",
                        segment.heading,
                    ),
                )
            ]
            for section_paragraph_number, pair in enumerate(
                segment.paragraphs,
                start=1,
            ):
                volume_paragraph_number += 1
                paragraph_count += 1
                source_hash = _text_hash(pair.original)
                translation_hash = _text_hash(pair.translation)
                paragraph_id = stable_id(
                    "source-record",
                    PACKAGE_ID,
                    expected.git_revision,
                    entry.relative_path,
                    pair.original_line,
                    volume_paragraph_number,
                    source_hash,
                )
                locator = {
                    "documentTitle": PACKAGE_TITLE,
                    "chapterTitle": volume_title,
                    "volumeNumber": entry.volume_number,
                    "eraHeading": segment.heading.original,
                    "year": segment.year,
                    "paragraphNumber": volume_paragraph_number,
                    "sectionParagraphNumber": section_paragraph_number,
                    "blockType": "paragraph",
                    "sourcePath": entry.relative_path,
                    "sourceLine": pair.original_line,
                    "translationLine": pair.translation_line,
                    "sourceHash": source_hash,
                    "translationHash": translation_hash,
                    "translationExclusionReason": TRANSLATION_EXCLUSION_REASON,
                }
                paragraphs.append(
                    HistoryParagraphRecord(
                        paragraph_id=paragraph_id,
                        text=pair.original,
                        ordinal=section_paragraph_number,
                        locator=locator,
                        source_hash=source_hash,
                        exclusion_audit=_exclusion_audit(
                            paragraph_id,
                            "paragraph",
                            pair,
                        ),
                    )
                )
            sections.append(
                HistorySectionRecord(
                    section_id=section_id,
                    document_id=document_id,
                    parent_section_id=volume_id,
                    title=segment.heading.original,
                    path=f"{volume_path}/{segment_number:03d}-{segment.heading.original}",
                    ordinal=section_ordinal,
                    source_path=entry.relative_path,
                    source_hash=chapter_hash,
                    paragraphs=tuple(paragraphs),
                    metadata={
                        "volumeNumber": entry.volume_number,
                        "segmentNumber": segment_number,
                        "year": segment.year,
                        "sourceHeadingHash": heading_hash,
                        "translationHeadingHash": translation_heading_hash,
                        "pairingMode": segment.pairing_mode,
                    },
                )
            )
            section_ordinal += 1
        if not volume_paragraph_number:
            raise ZizhiTongjianAdapterError(
                f"第 {entry.volume_number} 卷没有可打包古文段落"
            )
    if paragraph_count == 0:
        raise ZizhiTongjianAdapterError("资治通鉴没有可打包古文段落")
    document_hash = hashlib.sha256(
        canonical_json_bytes(source_descriptors)
    ).hexdigest()
    return HistoryDocumentRecord(
        document_id=document_id,
        title=PACKAGE_TITLE,
        ordinal=0,
        source_path=summary_path,
        source_hash=document_hash,
        source_size_bytes=source_size,
        source_format="md",
        sections=tuple(sections),
        metadata={
            "volumeCount": len(entries),
            "paragraphCount": paragraph_count,
            "translationPolicy": TRANSLATION_EXCLUSION_REASON,
        },
    )


def _parse_summary(text: str) -> tuple[_SummaryEntry, ...]:
    entries: list[_SummaryEntry] = []
    seen_paths: set[str] = set()
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    for line_number, raw_line in enumerate(normalized.split("\n"), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = _SUMMARY_LINK.fullmatch(line)
        if match is None:
            if "chapters" in line.casefold():
                raise ZizhiTongjianAdapterError(
                    f"SUMMARY.md 第 {line_number} 行章节链接格式无效"
                )
            continue
        label, raw_href = match.groups()
        if not raw_href.startswith("chapters"):
            continue
        relative_path, volume_number = _resolve_summary_link(raw_href)
        folded = relative_path.casefold()
        if folded in seen_paths:
            raise ZizhiTongjianAdapterError(
                f"SUMMARY.md 包含重复章节链接：{relative_path}"
            )
        seen_paths.add(folded)
        label_volume = _volume_number_from_title(label)
        if label_volume != volume_number:
            raise ZizhiTongjianAdapterError(
                f"SUMMARY.md 卷号与链接不一致：{label} -> {relative_path}"
            )
        entries.append(_SummaryEntry(volume_number, label.strip(), relative_path))
    if len(entries) != VOLUME_COUNT:
        raise ZizhiTongjianAdapterError(
            f"SUMMARY.md 必须包含 {VOLUME_COUNT} 个章节链接，实际 {len(entries)}"
        )
    actual_order = [entry.volume_number for entry in entries]
    expected_order = list(range(1, VOLUME_COUNT + 1))
    if actual_order != expected_order:
        raise ZizhiTongjianAdapterError(
            "SUMMARY.md 卷号必须从 001 到 294 连续且按数字顺序排列"
        )
    return tuple(entries)


def _resolve_summary_link(raw_href: str) -> tuple[str, int]:
    parsed = urlsplit(raw_href)
    decoded = unicodedata.normalize("NFC", unquote(parsed.path))
    parts = decoded.split("/")
    if (
        parsed.scheme
        or parsed.netloc
        or parsed.query
        or parsed.fragment
        or decoded.startswith("/")
        or "\\" in decoded
        or len(parts) != 2
        or parts[0] != "chapters"
        or any(part in {"", ".", ".."} for part in parts)
    ):
        raise ZizhiTongjianAdapterError(
            f"SUMMARY.md 包含不安全、穿越或非 chapters 链接：{raw_href}"
        )
    match = _CHAPTER_NAME.fullmatch(parts[1])
    if match is None:
        raise ZizhiTongjianAdapterError(f"章节文件名格式无效：{raw_href}")
    return str(PurePosixPath(*parts)), int(match.group(1))


def _parse_chapter(
    text: str,
    source_path: str,
) -> tuple[str, tuple[_TimeSegment, ...]]:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    rows = [
        (line_number, line)
        for line_number, line in enumerate(normalized.split("\n"), start=1)
        if line.strip()
    ]
    if not rows:
        raise ZizhiTongjianAdapterError(f"章节为空：{source_path}")
    title = rows[0][1].strip().removeprefix("\ufeff")
    content = rows[1:]
    segments: list[_TimeSegment] = []
    cursor = 0
    while cursor < len(content):
        original_line_number, original_heading_raw = content[cursor]
        crossed_paragraph: _PairedLine | None = None
        pairing_mode = "standard"
        if _is_body_line(original_heading_raw):
            if not _is_heading_wrapped_pair(content, cursor, source_path):
                raise ZizhiTongjianAdapterError(
                    f"{source_path}:{original_line_number} 正文未归类到时间标题"
                )
            source_body_line, source_body_raw = content[cursor]
            original_line_number, original_heading_raw = content[cursor + 1]
            translated_line_number, translated_heading_raw = content[cursor + 2]
            translated_body_line, translated_body_raw = content[cursor + 3]
            crossed_paragraph = _PairedLine(
                original=source_body_raw.strip(),
                translation=translated_body_raw.strip(),
                original_line=source_body_line,
                translation_line=translated_body_line,
            )
            _validate_markers(crossed_paragraph, source_path)
            pairing_mode = "heading-wrapped-first-pair"
            cursor_advance = 4
        else:
            if cursor + 1 >= len(content):
                raise ZizhiTongjianAdapterError(f"{source_path} 时间标题缺少译文配对")
            translated_line_number, translated_heading_raw = content[cursor + 1]
            cursor_advance = 2
            if _is_body_line(translated_heading_raw):
                if _is_indented_time_translation(
                    original_heading_raw,
                    translated_heading_raw,
                ):
                    pairing_mode = "indented-time-heading"
                elif source_path not in _KNOWN_CROSSED_FIRST_PAIR_PATHS:
                    raise ZizhiTongjianAdapterError(
                        f"{source_path}:{translated_line_number} 时间标题译文配对格式无效"
                    )
                elif (
                    cursor + 3 >= len(content)
                    or _is_body_line(content[cursor + 2][1])
                    or not _is_body_line(content[cursor + 3][1])
                ):
                    raise ZizhiTongjianAdapterError(
                        f"{source_path}:{original_line_number} 已知交叉配对结构不完整"
                    )
                else:
                    source_body_line, source_body_raw = content[cursor + 1]
                    translated_line_number, translated_heading_raw = content[cursor + 2]
                    translated_body_line, translated_body_raw = content[cursor + 3]
                    crossed_paragraph = _PairedLine(
                        original=source_body_raw.strip(),
                        translation=translated_body_raw.strip(),
                        original_line=source_body_line,
                        translation_line=translated_body_line,
                    )
                    _validate_markers(crossed_paragraph, source_path)
                    pairing_mode = "crossed-first-pair"
                    cursor_advance = 4
        _reject_ambiguous_indentation(original_heading_raw, source_path, original_line_number)
        if pairing_mode != "indented-time-heading":
            _reject_ambiguous_indentation(
                translated_heading_raw,
                source_path,
                translated_line_number,
            )
        if pairing_mode == "standard" and (
            _has_single_ideographic_indent(original_heading_raw)
            or _has_single_ideographic_indent(translated_heading_raw)
        ):
            pairing_mode = "single-indent-time-heading"
        heading = _PairedLine(
            original=original_heading_raw.strip(),
            translation=translated_heading_raw.strip(),
            original_line=original_line_number,
            translation_line=translated_line_number,
        )
        year, year_pairing_mode = _validate_year_pair(heading, source_path)
        if pairing_mode == "standard" and year_pairing_mode is not None:
            pairing_mode = year_pairing_mode
        cursor += cursor_advance
        paragraphs: list[_PairedLine] = (
            [crossed_paragraph] if crossed_paragraph is not None else []
        )
        while cursor < len(content) and _is_body_line(content[cursor][1]):
            source_line_number, source_raw = content[cursor]
            if cursor + 1 >= len(content) or not _is_body_line(content[cursor + 1][1]):
                if _is_heading_wrapped_pair(content, cursor, source_path):
                    break
                if not _is_known_unindented_translation(content, cursor, source_path):
                    raise ZizhiTongjianAdapterError(
                        f"{source_path}:{source_line_number} 古文/译文段落为奇数或缺少配对"
                    )
                if pairing_mode == "standard":
                    pairing_mode = "unindented-translation"
            translation_line_number, translation_raw = content[cursor + 1]
            pair = _PairedLine(
                original=source_raw.strip(),
                translation=translation_raw.strip(),
                original_line=source_line_number,
                translation_line=translation_line_number,
            )
            _validate_markers(pair, source_path)
            paragraphs.append(pair)
            cursor += 2
        if not paragraphs:
            raise ZizhiTongjianAdapterError(
                f"{source_path}:{original_line_number} 时间段没有古文/译文正文配对"
            )
        segments.append(_TimeSegment(heading, year, tuple(paragraphs), pairing_mode))
    if not segments:
        raise ZizhiTongjianAdapterError(f"章节没有时间段：{source_path}")
    return title, tuple(segments)


def _is_heading_wrapped_pair(
    content: list[tuple[int, str]],
    cursor: int,
    source_path: str,
) -> bool:
    return (
        source_path in _KNOWN_HEADING_WRAPPED_PAIR_PATHS
        and cursor + 3 < len(content)
        and _is_body_line(content[cursor][1])
        and not _is_body_line(content[cursor + 1][1])
        and not _is_body_line(content[cursor + 2][1])
        and _is_body_line(content[cursor + 3][1])
    )


def _is_known_unindented_translation(
    content: list[tuple[int, str]],
    cursor: int,
    source_path: str,
) -> bool:
    return (
        source_path in _KNOWN_UNINDENTED_TRANSLATION_PATHS
        and cursor + 1 < len(content)
        and _is_body_line(content[cursor][1])
        and not _is_body_line(content[cursor + 1][1])
        and not _looks_like_time_heading(content[cursor + 1][1])
    )


def _looks_like_time_heading(value: str) -> bool:
    normalized = unicodedata.normalize("NFKC", value).strip()
    return (
        len(normalized) <= 120
        and ("(" in normalized or "[todo]" in normalized.casefold())
        and _extract_year(normalized) is not None
    )


def _validate_volume_identity(
    entry: _SummaryEntry,
    filename: str,
    title: str,
) -> None:
    match = _CHAPTER_NAME.fullmatch(filename)
    if match is None:
        raise ZizhiTongjianAdapterError(f"章节文件名格式无效：{filename}")
    filename_title = match.group(2)
    known_mismatch = _KNOWN_TITLE_MISMATCHES.get(entry.relative_path)
    accepted_mismatch = known_mismatch == (filename_title, title)
    if (
        _comparison_text(filename_title) != _comparison_text(title)
        and not accepted_mismatch
    ):
        raise ZizhiTongjianAdapterError(
            f"章节文件名与首行标题不一致：{filename_title} != {title}"
        )
    descriptors = (_volume_descriptor(entry.label), _volume_descriptor(title))
    if descriptors[0] != descriptors[1] and not accepted_mismatch:
        raise ZizhiTongjianAdapterError(
            f"SUMMARY 卷名与章节标题不一致：{entry.label} != {title}"
        )
    for label, value in (
        ("文件名", filename_title),
        ("首行标题", title),
    ):
        if _volume_number_from_title(value) != entry.volume_number:
            raise ZizhiTongjianAdapterError(
                f"第 {entry.volume_number} 卷{label}卷号不一致：{value}"
            )


def _volume_descriptor(value: str) -> str:
    normalized = _comparison_text(value)
    start = normalized.find("第")
    if start < 0 or "卷" not in normalized[start:]:
        raise ZizhiTongjianAdapterError(f"卷名缺少第...卷结构：{value}")
    return normalized[start:]


def _volume_number_from_title(value: str) -> int:
    match = _VOLUME_NUMBER.search(unicodedata.normalize("NFKC", value))
    if match is None:
        raise ZizhiTongjianAdapterError(f"卷名无法识别卷号：{value}")
    token = match.group(1)
    if token.isdigit():
        return int(token)
    digits = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
    units = {"十": 10, "百": 100}
    total = 0
    current = 0
    for character in token:
        if character in digits:
            current = digits[character]
        elif character in units:
            total += (current or 1) * units[character]
            current = 0
        else:
            raise ZizhiTongjianAdapterError(f"卷名包含未知数字：{value}")
    return total + current


def _validate_year_pair(
    pair: _PairedLine,
    source_path: str,
) -> tuple[int, str | None]:
    original_year = _extract_year(pair.original)
    if original_year is None:
        raise ZizhiTongjianAdapterError(
            f"{source_path}:{pair.original_line} 时间原文缺少年份"
        )
    if "[todo]" not in pair.translation.casefold():
        translation_year = _extract_year(pair.translation)
        if translation_year is None or translation_year != original_year:
            known_year = _KNOWN_YEAR_MISMATCHES.get(
                (source_path, pair.original, pair.translation)
            )
            if known_year is not None:
                return known_year, "known-year-mismatch"
            raise ZizhiTongjianAdapterError(
                f"{source_path}:{pair.translation_line} 时间译文年份与原文不一致"
            )
    return original_year, None


def _extract_year(value: str) -> int | None:
    matches = list(_YEAR.finditer(unicodedata.normalize("NFKC", value)))
    if not matches:
        return None
    match = matches[-1]
    year = int(match.group(3))
    return -year if match.group(2) else year


def _is_indented_time_translation(original: str, candidate: str) -> bool:
    if not _is_body_line(candidate):
        return False
    normalized = unicodedata.normalize("NFKC", candidate).casefold()
    if "公元" not in normalized and "[todo]" not in normalized:
        return False
    original_year = _extract_year(original)
    candidate_year = _extract_year(candidate)
    return original_year is not None and candidate_year == original_year


def _validate_markers(pair: _PairedLine, source_path: str) -> None:
    if "[todo]" in pair.translation.casefold():
        return
    original_markers = _PAIR_MARKER.findall(pair.original)
    translation_markers = _PAIR_MARKER.findall(pair.translation)
    if original_markers and original_markers != translation_markers:
        raise ZizhiTongjianAdapterError(
            f"{source_path}:{pair.original_line}/{pair.translation_line} "
            "古文与译文 [n] 标记不配对"
        )


def _is_body_line(value: str) -> bool:
    return value.startswith("\u3000\u3000")


def _reject_ambiguous_indentation(value: str, source_path: str, line_number: int) -> None:
    if _has_single_ideographic_indent(value):
        return
    if value != value.lstrip():
        raise ZizhiTongjianAdapterError(
            f"{source_path}:{line_number} 行缩进无法归类为时间或正文"
        )


def _has_single_ideographic_indent(value: str) -> bool:
    return value.startswith("\u3000") and not value.startswith("\u3000\u3000")


def _exclusion_audit(
    record_id: str,
    kind: str,
    pair: _PairedLine,
) -> HistoryExclusionAudit:
    return HistoryExclusionAudit(
        record_id=record_id,
        kind=kind,
        source_text_hash=_text_hash(pair.original),
        excluded_text_hash=_text_hash(pair.translation),
        reason=TRANSLATION_EXCLUSION_REASON,
        source_line=pair.original_line,
        excluded_line=pair.translation_line,
    )


def _comparison_text(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", value).split())


def _text_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _read_utf8(path: Path, root: Path) -> tuple[str, str, int]:
    label = _relative_nfc(path, root)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise ZizhiTongjianAdapterError(
            f"来源文件不可安全读取：{label}：{error}"
        ) from error
    before = os.fstat(descriptor)
    if not stat.S_ISREG(before.st_mode):
        os.close(descriptor)
        raise ZizhiTongjianAdapterError(f"来源不是普通文件：{label}")
    digest = hashlib.sha256()
    payload = bytearray()
    try:
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            while block := stream.read(_BUFFER_BYTES):
                digest.update(block)
                payload.extend(block)
        after = os.fstat(descriptor)
        if (
            before.st_dev,
            before.st_ino,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        ) != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        ):
            raise ZizhiTongjianAdapterError(f"来源在读取期间发生变化：{label}")
        try:
            text = bytes(payload).decode("utf-8")
        except UnicodeDecodeError as error:
            raise ZizhiTongjianAdapterError(f"来源不是 UTF-8：{label}") from error
        return text, digest.hexdigest(), len(payload)
    finally:
        os.close(descriptor)


def _relative_nfc(path: Path, root: Path) -> str:
    try:
        relative = path.relative_to(root)
    except ValueError as error:
        raise ZizhiTongjianAdapterError(f"来源路径越界：{path}") from error
    return unicodedata.normalize("NFC", relative.as_posix())


def _locked_source(lock: SourceLock) -> SourceInventory:
    matches = [
        source for source in lock.sources if source.source_id == ZIZHI_TONGJIAN_SOURCE_ID
    ]
    if len(matches) != 1:
        raise ZizhiTongjianAdapterError("source lock 必须包含唯一 zizhi-tongjian 来源")
    return matches[0]


__all__ = [
    "CONCEPT_NAMESPACE",
    "PACKAGE_ID",
    "PACKAGE_TITLE",
    "PACKAGE_VERSION",
    "VOLUME_COUNT",
    "ZizhiTongjianAdapterError",
    "prepare_zizhi_tongjian",
]
