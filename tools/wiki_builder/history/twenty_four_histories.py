"""Strict adapter for the classical-text HTML in the Twenty-Four Histories."""

from __future__ import annotations

import hashlib
import os
import re
import stat
import unicodedata
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit

from tools.package_format import canonical_json_bytes

from ..extractors import stable_id
from ..models import BuildError
from .models import HistoryDocumentRecord, HistoryParagraphRecord, HistorySectionRecord
from .source_inventory import (
    SourceInventory,
    SourceLock,
    TWENTY_FOUR_HISTORIES_SOURCE_ID,
    inventory_history_repository,
)
from .workspace_builder import populate_history_workspace

TWENTY_FOUR_HISTORIES = (
    "史记",
    "汉书",
    "后汉书",
    "三国志",
    "晋书",
    "宋书",
    "南齐书",
    "梁书",
    "陈书",
    "魏书",
    "北齐书",
    "周书",
    "隋书",
    "南史",
    "北史",
    "旧唐书",
    "新唐书",
    "旧五代史",
    "新五代史",
    "宋史",
    "辽史",
    "金史",
    "元史",
    "明史",
)

PACKAGE_ID = "cn.history.twenty-four-histories"
PACKAGE_TITLE = "二十四史"
PACKAGE_VERSION = 1
CONCEPT_NAMESPACE = "cn-history-v1"
_BUFFER_BYTES = 1024 * 1024
_NAVIGATION_WORDS = ("上一页", "下一页", "上一章", "下一章", "返回目录", "目录")
_IGNORED_TAGS = {"script", "style", "nav", "header", "footer", "noscript"}
_IGNORED_ATTR_WORDS = {
    "nav",
    "navbar",
    "navigation",
    "pager",
    "pagination",
    "previous",
    "next",
    "footer",
    "header",
    "breadcrumb",
    "menu",
}


class HistoryAdapterError(BuildError):
    """Raised when source HTML cannot be consumed without ambiguity."""


def prepare_twenty_four_histories(
    source: Path,
    workspace: Path,
    source_lock: SourceLock,
) -> Path:
    expected = _locked_source(source_lock)
    inventory_history_repository(
        TWENTY_FOUR_HISTORIES_SOURCE_ID,
        source,
        expected=expected,
    )
    root = Path(source).resolve()
    linked_count = 0

    def documents():
        nonlocal linked_count
        for ordinal, document_title in enumerate(TWENTY_FOUR_HISTORIES):
            document, consumed = _build_document(
                root,
                document_title,
                ordinal,
                expected.git_revision,
            )
            linked_count += consumed
            yield document
        if linked_count != expected.relevant_file_count:
            raise HistoryAdapterError(
                "二十四史古文未被 24 部书白名单完整消费："
                f"linked={linked_count}, lock={expected.relevant_file_count}"
            )

    def verify_unchanged() -> None:
        inventory_history_repository(
            TWENTY_FOUR_HISTORIES_SOURCE_ID,
            root,
            expected=expected,
        )

    return populate_history_workspace(
        documents(),
        workspace,
        wiki_id=PACKAGE_ID,
        title=PACKAGE_TITLE,
        version=PACKAGE_VERSION,
        concept_namespace=CONCEPT_NAMESPACE,
        source_id=TWENTY_FOUR_HISTORIES_SOURCE_ID,
        source_revision=expected.git_revision,
        exclusions=("-白话", "-译文", "-段译", "原始 HTML 载体"),
        before_publish=verify_unchanged,
    )


def _build_document(
    root: Path,
    title: str,
    ordinal: int,
    revision: str,
) -> tuple[HistoryDocumentRecord, int]:
    document_root = root / title
    index_path = document_root / f"{title}.html"
    if not index_path.is_file() or index_path.is_symlink():
        raise HistoryAdapterError(f"缺少 {title} 索引：{index_path.relative_to(root)}")
    index_text, index_hash, index_size = _read_utf8(index_path, root)
    links = _parse_index_links(index_text, title)
    if not links:
        raise HistoryAdapterError(f"{title} 索引没有原文章节链接")

    linked_paths: list[Path] = []
    linked_relative: set[str] = set()
    for href in links:
        relative = _resolve_document_link(title, href)
        if relative in linked_relative:
            raise HistoryAdapterError(f"{title} 索引包含重复章节链接：{relative}")
        linked_relative.add(relative)
        chapter = root / relative
        if not chapter.is_file() or chapter.is_symlink():
            raise HistoryAdapterError(f"{title} 索引链接的原文缺失：{relative}")
        linked_paths.append(chapter)

    actual_sources = {
        _relative_nfc(path, root)
        for path in document_root.rglob("*-原文.html")
        if path.is_file() and not path.is_symlink()
    }
    unlinked = sorted(actual_sources - linked_relative)
    unexpected_links = sorted(linked_relative - actual_sources)
    if unlinked:
        raise HistoryAdapterError(f"{title} 存在未链接原文：{', '.join(unlinked[:10])}")
    if unexpected_links:
        raise HistoryAdapterError(
            f"{title} 索引链接不在原文集合：{', '.join(unexpected_links[:10])}"
        )

    document_id = stable_id("doc", PACKAGE_ID, revision, title)
    sections: list[HistorySectionRecord] = []
    category_ids: dict[tuple[str, ...], str] = {}
    chapter_descriptors: list[dict[str, object]] = []
    section_ordinal = 0
    total_size = index_size
    for chapter_path in linked_paths:
        relative = _relative_nfc(chapter_path, root)
        chapter_text, chapter_hash, chapter_size = _read_utf8(chapter_path, root)
        total_size += chapter_size
        category_path = tuple(PurePosixPath(relative).parts[1:-1])
        parent_id: str | None = None
        for depth in range(1, len(category_path) + 1):
            key = category_path[:depth]
            category_id = category_ids.get(key)
            if category_id is None:
                category_id = stable_id(
                    "section",
                    PACKAGE_ID,
                    revision,
                    title,
                    "category",
                    *key,
                )
                category_ids[key] = category_id
                sections.append(
                    HistorySectionRecord(
                        section_id=category_id,
                        document_id=document_id,
                        parent_section_id=parent_id,
                        title=key[-1],
                        path=" / ".join(key),
                        ordinal=section_ordinal,
                        source_path=None,
                        source_hash=None,
                    )
                )
                section_ordinal += 1
            parent_id = category_id

        chapter_title, paragraph_texts = _parse_chapter_html(chapter_text, relative)
        leaf_id = stable_id("section", PACKAGE_ID, revision, relative)
        paragraphs = tuple(
            HistoryParagraphRecord(
                paragraph_id=stable_id(
                    "paragraph",
                    PACKAGE_ID,
                    revision,
                    relative,
                    paragraph_ordinal,
                ),
                text=text,
                ordinal=paragraph_ordinal,
                locator={
                    "documentTitle": title,
                    "categoryPath": list(category_path),
                    "chapterTitle": chapter_title,
                    "paragraphNumber": paragraph_ordinal + 1,
                    "sourcePath": relative,
                    "sourceHash": chapter_hash,
                },
                source_hash=chapter_hash,
            )
            for paragraph_ordinal, text in enumerate(paragraph_texts)
        )
        sections.append(
            HistorySectionRecord(
                section_id=leaf_id,
                document_id=document_id,
                parent_section_id=parent_id,
                title=chapter_title,
                path=" / ".join((*category_path, chapter_title)),
                ordinal=section_ordinal,
                source_path=relative,
                source_hash=chapter_hash,
                paragraphs=paragraphs,
                metadata={"sourceFileName": chapter_path.name},
            )
        )
        section_ordinal += 1
        chapter_descriptors.append(
            {"path": relative, "sha256": chapter_hash, "sizeBytes": chapter_size}
        )

    document_hash = hashlib.sha256(
        canonical_json_bytes(
            {"indexHash": index_hash, "chapters": chapter_descriptors}
        )
    ).hexdigest()
    return (
        HistoryDocumentRecord(
            document_id=document_id,
            title=title,
            ordinal=ordinal,
            source_path=index_path,
            source_hash=document_hash,
            source_size_bytes=total_size,
            source_format="html",
            sections=tuple(sections),
            metadata={"indexPath": _relative_nfc(index_path, root)},
        ),
        len(linked_paths),
    )


class _IndexLinkParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() != "a":
            return
        href = next((value for key, value in attrs if key.lower() == "href"), None)
        if href and "-原文.html" in unquote(href):
            self.links.append(href)


def _parse_index_links(payload: str, title: str) -> tuple[str, ...]:
    parser = _IndexLinkParser()
    try:
        parser.feed(payload)
        parser.close()
    except Exception as error:
        raise HistoryAdapterError(f"{title} 索引 HTML 无法解析：{error}") from error
    return tuple(parser.links)


class _ChapterParser(HTMLParser):
    def __init__(self, label: str):
        super().__init__(convert_charrefs=True)
        self.label = label
        self.heading_parts: list[str] = []
        self.paragraphs: list[str] = []
        self.discarded_visible: list[str] = []
        self.heading_count = 0
        self._ignore_depth = 0
        self._heading_depth = 0
        self._paragraph_depth = 0
        self._paragraph_parts: list[str] = []
        self._paragraph_has_link = False
        self._after_heading = False

    def handle_starttag(self, tag: str, attrs) -> None:
        name = tag.lower()
        if self._ignore_depth:
            self._ignore_depth += 1
            return
        if name in _IGNORED_TAGS or _ignored_attrs(attrs):
            self._ignore_depth = 1
            return
        if name == "h1":
            self.heading_count += 1
            self._heading_depth = 1
            self._after_heading = True
            return
        if self._heading_depth:
            self._heading_depth += 1
            return
        if name == "p" and self._after_heading and not self._paragraph_depth:
            self._paragraph_depth = 1
            self._paragraph_parts = []
            self._paragraph_has_link = False
            return
        if self._paragraph_depth:
            self._paragraph_depth += 1
            if name == "a":
                self._paragraph_has_link = True
            if name == "br":
                self._paragraph_parts.append("\n")

    def handle_startendtag(self, tag: str, attrs) -> None:
        if self._paragraph_depth and tag.lower() == "br":
            self._paragraph_parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if self._ignore_depth:
            self._ignore_depth -= 1
            return
        if self._heading_depth:
            self._heading_depth -= 1
            return
        if self._paragraph_depth:
            self._paragraph_depth -= 1
            if self._paragraph_depth == 0:
                value = _clean_source_text("".join(self._paragraph_parts))
                if value and not (
                    self._paragraph_has_link
                    and any(word in value for word in _NAVIGATION_WORDS)
                ):
                    self.paragraphs.append(value)

    def handle_data(self, data: str) -> None:
        if self._ignore_depth:
            return
        if self._heading_depth:
            self.heading_parts.append(data)
        elif self._paragraph_depth:
            self._paragraph_parts.append(data)
        elif self._after_heading and data.strip():
            self.discarded_visible.append(_clean_source_text(data))


def _parse_chapter_html(payload: str, label: str) -> tuple[str, tuple[str, ...]]:
    parser = _ChapterParser(label)
    try:
        parser.feed(payload)
        parser.close()
    except Exception as error:
        raise HistoryAdapterError(f"章节 HTML 无法解析：{label}：{error}") from error
    heading = _clean_source_text("".join(parser.heading_parts))
    if parser.heading_count != 1 or not heading:
        raise HistoryAdapterError(f"章节必须且只能包含一个非空 h1：{label}")
    discarded = [value for value in parser.discarded_visible if value]
    if discarded:
        raise HistoryAdapterError(
            f"章节存在被丢弃的可见正文：{label}：{' | '.join(discarded[:3])}"
        )
    if not parser.paragraphs:
        raise HistoryAdapterError(f"章节没有可用正文段落：{label}")
    return heading, tuple(parser.paragraphs)


def _ignored_attrs(attrs) -> bool:
    values = " ".join(
        value.casefold()
        for key, value in attrs
        if key.casefold() in {"id", "class", "role"} and value
    )
    tokens = set(re.split(r"[^a-z]+", values))
    return bool(tokens.intersection(_IGNORED_ATTR_WORDS))


def _resolve_document_link(document_title: str, raw_href: str) -> str:
    parsed = urlsplit(raw_href)
    decoded = unicodedata.normalize("NFC", unquote(parsed.path))
    raw_parts = decoded.split("/")
    if (
        parsed.scheme
        or parsed.netloc
        or decoded.startswith("/")
        or "\\" in decoded
        or any(part in {"", ".", ".."} for part in raw_parts)
    ):
        raise HistoryAdapterError(f"{document_title} 索引包含不安全或穿越链接：{raw_href}")
    relative = PurePosixPath(document_title, *raw_parts)
    if relative.parts[0] != document_title or not relative.name.endswith("-原文.html"):
        raise HistoryAdapterError(f"{document_title} 索引原文链接越界：{raw_href}")
    return str(relative)


def _read_utf8(path: Path, root: Path) -> tuple[str, str, int]:
    label = _relative_nfc(path, root)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise HistoryAdapterError(f"来源文件不可安全读取：{label}：{error}") from error
    before = os.fstat(descriptor)
    if not stat.S_ISREG(before.st_mode):
        os.close(descriptor)
        raise HistoryAdapterError(f"来源不是普通文件：{label}")
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
            raise HistoryAdapterError(f"来源在读取期间发生变化：{label}")
        try:
            text = bytes(payload).decode("utf-8")
        except UnicodeDecodeError as error:
            raise HistoryAdapterError(f"来源不是 UTF-8：{label}") from error
        return text, digest.hexdigest(), len(payload)
    finally:
        os.close(descriptor)


def _clean_source_text(value: str) -> str:
    normalized = value.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ")
    lines = [line.strip(" \t") for line in normalized.split("\n")]
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines)


def _relative_nfc(path: Path, root: Path) -> str:
    return unicodedata.normalize("NFC", path.relative_to(root).as_posix())


def _locked_source(lock: SourceLock) -> SourceInventory:
    matches = [
        source
        for source in lock.sources
        if source.source_id == TWENTY_FOUR_HISTORIES_SOURCE_ID
    ]
    if len(matches) != 1:
        raise HistoryAdapterError("source lock 必须包含唯一 twenty-four-histories 来源")
    return matches[0]


__all__ = [
    "CONCEPT_NAMESPACE",
    "HistoryAdapterError",
    "PACKAGE_ID",
    "PACKAGE_TITLE",
    "PACKAGE_VERSION",
    "TWENTY_FOUR_HISTORIES",
    "prepare_twenty_four_histories",
]
