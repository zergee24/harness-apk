import hashlib
import html
import codecs
import re
import tempfile
import zipfile
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree

from pypdf import PdfReader

from .models import BuildError, ExtractedDocument, ExtractedSection


SUPPORTED_SUFFIXES = {".txt", ".md", ".markdown", ".epub", ".pdf"}
V2_TEXT_READ_BYTES = 64 * 1024
V2_MAX_SECTION_CHARS = 16 * 1024


@dataclass(frozen=True)
class _DecodedLinePart:
    text: str
    starts_line: bool
    ends_line: bool
    continues_line: bool


class _TextHtmlParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs):
        if tag.lower() in {"p", "div", "br", "h1", "h2", "h3", "h4", "li", "section"}:
            self._parts.append("\n")

    def handle_data(self, data: str):
        self._parts.append(data)

    def text(self) -> str:
        return _normalize_text(html.unescape("".join(self._parts)))


def extract_document(path: Path) -> ExtractedDocument:
    path = path.expanduser().resolve()
    if not path.is_file():
        raise BuildError(f"输入文件不存在：{path}")
    suffix = path.suffix.lower()
    if suffix not in SUPPORTED_SUFFIXES:
        raise BuildError(f"不支持的输入格式：{path.name}")

    if suffix in {".txt", ".md", ".markdown"}:
        sections = _extract_plain_text(path)
    elif suffix == ".epub":
        sections = _extract_epub(path)
    else:
        sections = _extract_pdf(path)

    if not any(section.text.strip() for section in sections):
        raise BuildError(f"没有可提取文本：{path.name}")
    return ExtractedDocument(
        title=path.stem,
        source_path=path,
        source_hash=_sha256_file(path),
        sections=sections,
    )


def iter_v2_plain_text_sections(
    path: Path,
    read_chars: int = V2_TEXT_READ_BYTES,
    max_section_chars: int = V2_MAX_SECTION_CHARS,
):
    """Yield TXT/Markdown sections using only a bounded decode and section window."""
    source = Path(path)
    if read_chars < 1 or max_section_chars < 128:
        raise BuildError("V2 文本窗口必须为正数")
    with source.open("rb") as stream:
        yield from _iter_v2_plain_text_sections_stream(stream, read_chars, max_section_chars)


def iter_v2_source_sections_stream(
    stream,
    suffix: str,
    display_name: str,
    *,
    read_chars: int = V2_TEXT_READ_BYTES,
    max_section_chars: int = V2_MAX_SECTION_CHARS,
):
    """Extract a V2 source from an already-open, descriptor-anchored stream."""
    normalized_suffix = suffix.lower()
    if normalized_suffix in {".txt", ".md", ".markdown"}:
        yield from _iter_v2_plain_text_sections_stream(stream, read_chars, max_section_chars)
        return
    if normalized_suffix == ".epub":
        yield from _extract_epub_stream(stream, display_name)
        return
    if normalized_suffix == ".pdf":
        yield from _extract_pdf_stream(stream, display_name)
        return
    raise BuildError(f"不支持的输入格式：{display_name}")


def _iter_v2_plain_text_sections_stream(stream, read_chars: int, max_section_chars: int):
    if read_chars < 1 or max_section_chars < 128:
        raise BuildError("V2 文本窗口必须为正数")
    encoding = _v2_detect_text_encoding_stream(stream, read_chars)
    yield from _iter_v2_sections_from_decoded_lines(
        _iter_decoded_lines_stream(stream, encoding, read_chars, max_section_chars),
        max_section_chars,
    )


def _v2_detect_text_encoding(path: Path, buffer_size: int) -> str:
    with Path(path).open("rb") as stream:
        return _v2_detect_text_encoding_stream(stream, buffer_size)


def _v2_detect_text_encoding_stream(stream, buffer_size: int) -> str:
    for encoding in ("utf-8-sig", "gb18030"):
        decoder = codecs.getincrementaldecoder(encoding)(errors="strict")
        try:
            stream.seek(0)
            while block := stream.read(buffer_size):
                decoder.decode(block)
            decoder.decode(b"", final=True)
            return encoding
        except UnicodeDecodeError:
            continue
    raise BuildError("文本编码无法识别")


def _iter_decoded_lines(path: Path, encoding: str, buffer_size: int, max_line_chars: int):
    with Path(path).open("rb") as stream:
        yield from _iter_decoded_lines_stream(stream, encoding, buffer_size, max_line_chars)


def _iter_decoded_lines_stream(stream, encoding: str, buffer_size: int, max_line_chars: int):
    decoder = codecs.getincrementaldecoder(encoding)(errors="strict")
    pending = ""
    starts_line = True

    def drain(final: bool):
        nonlocal pending, starts_line
        while pending:
            newline = re.search(r"\r\n|[\r\n]", pending)
            waits_for_lf = (
                newline is not None
                and newline.group() == "\r"
                and newline.end() == len(pending)
                and not final
            )
            if newline is not None and not waits_for_lf:
                yield _DecodedLinePart(
                    pending[: newline.start()],
                    starts_line=starts_line,
                    ends_line=True,
                    continues_line=False,
                )
                pending = pending[newline.end() :]
                starts_line = True
                continue
            if len(pending) >= max_line_chars:
                yield _DecodedLinePart(
                    pending[:max_line_chars],
                    starts_line=starts_line,
                    ends_line=False,
                    continues_line=True,
                )
                pending = pending[max_line_chars:]
                starts_line = False
                continue
            if final:
                yield _DecodedLinePart(
                    pending,
                    starts_line=starts_line,
                    ends_line=False,
                    continues_line=False,
                )
                pending = ""
            break

    stream.seek(0)
    while block := stream.read(buffer_size):
        pending += decoder.decode(block)
        yield from drain(final=False)
    pending += decoder.decode(b"", final=True)
    yield from drain(final=True)


def _iter_v2_sections_from_decoded_lines(lines, max_section_chars: int):
    headings: list[str] = []
    location = "正文"
    buffer = ""
    normalizer = _StreamingSectionNormalizer()

    def flush_window():
        nonlocal buffer
        value = buffer
        buffer = ""
        if value:
            return ExtractedSection(location, value)
        return None

    def finish_section():
        normalizer.finish()
        return flush_window()

    def append(value: str):
        nonlocal buffer
        pending = value
        while pending:
            available = max_section_chars - len(buffer)
            if available <= 0:
                emitted = flush_window()
                if emitted is not None:
                    yield emitted
                available = max_section_chars
            buffer += pending[:available]
            pending = pending[available:]
            if pending:
                emitted = flush_window()
                if emitted is not None:
                    yield emitted

    try:
        for part in lines:
            line = part.text
            heading = (
                re.match(r"^(#{1,6})\s+(.+?)\s*$", line)
                if part.starts_line and not part.continues_line
                else None
            )
            if heading:
                emitted = finish_section()
                if emitted is not None:
                    yield emitted
                level = len(heading.group(1))
                title = heading.group(2).strip()
                headings[:] = headings[: level - 1]
                headings.append(title)
                location = " / ".join(headings)
            for value in (line, "\n" if part.ends_line else ""):
                for fragment in normalizer.feed(value):
                    yield from append(fragment)
        emitted = finish_section()
        if emitted is not None:
            yield emitted
    finally:
        normalizer.close()


class _StreamingSectionNormalizer:
    """Bounded, boundary-stable form of the V2 whitespace normalization."""

    def __init__(self):
        self._pending = tempfile.SpooledTemporaryFile(
            max_size=V2_MAX_SECTION_CHARS,
            mode="w+t",
            encoding="utf-8",
            newline="",
        )
        self._has_content = False
        self._previous_horizontal_space = False
        self._newline_run = 0

    def feed(self, value: str):
        output: list[str] = []
        for character in value:
            if character == "\u00a0":
                character = " "
            if character in " \t":
                if not self._has_content:
                    continue
                if not self._previous_horizontal_space:
                    self._pending.write(" ")
                self._previous_horizontal_space = True
                self._newline_run = 0
                continue
            if character == "\n":
                if not self._has_content:
                    continue
                if self._newline_run < 2:
                    self._pending.write("\n")
                self._previous_horizontal_space = False
                self._newline_run += 1
                continue
            if self._has_content:
                if output:
                    yield "".join(output)
                    output = []
                yield from self._drain_pending()
            self._has_content = True
            self._previous_horizontal_space = False
            self._newline_run = 0
            output.append(character)
        if output:
            yield "".join(output)

    def finish(self) -> None:
        self._discard_pending()
        self._has_content = False
        self._previous_horizontal_space = False
        self._newline_run = 0

    def close(self) -> None:
        self._pending.close()

    def _drain_pending(self):
        self._pending.seek(0)
        while fragment := self._pending.read(V2_MAX_SECTION_CHARS):
            yield fragment
        self._discard_pending()

    def _discard_pending(self) -> None:
        self._pending.seek(0)
        self._pending.truncate(0)


def _extract_plain_text(path: Path) -> list[ExtractedSection]:
    payload = path.read_bytes()
    decoded = None
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            decoded = payload.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    if decoded is None:
        raise BuildError(f"文本编码无法识别：{path.name}")
    text = _normalize_text(decoded)
    if not text:
        return []
    return _split_markdown_sections(text)


def _extract_epub(path: Path) -> list[ExtractedSection]:
    with Path(path).open("rb") as stream:
        return list(_extract_epub_stream(stream, Path(path).name))


def _extract_epub_stream(stream, display_name: str):
    try:
        with zipfile.ZipFile(stream) as archive:
            container_root = ElementTree.fromstring(archive.read("META-INF/container.xml"))
            rootfile = next(
                element for element in container_root.iter() if element.tag.endswith("rootfile")
            )
            opf_path = _safe_epub_path(rootfile.attrib["full-path"])
            opf_root = ElementTree.fromstring(archive.read(opf_path))
            manifest = {
                element.attrib["id"]: element.attrib["href"]
                for element in opf_root.iter()
                if element.tag.endswith("item") and "id" in element.attrib and "href" in element.attrib
            }
            spine_ids = [
                element.attrib["idref"]
                for element in opf_root.iter()
                if element.tag.endswith("itemref") and "idref" in element.attrib
            ]
            base = PurePosixPath(opf_path).parent
            sections: list[ExtractedSection] = []
            for index, item_id in enumerate(spine_ids, start=1):
                href = manifest.get(item_id)
                if href is None:
                    continue
                entry_path = _safe_epub_path(str(base / href.split("#", 1)[0]))
                parser = _TextHtmlParser()
                parser.feed(archive.read(entry_path).decode("utf-8", errors="replace"))
                text = parser.text()
                if text:
                    sections.append(ExtractedSection(f"spine-{index}", text))
            return sections
    except (KeyError, StopIteration, zipfile.BadZipFile, ElementTree.ParseError) as error:
        raise BuildError(f"EPUB 解析失败：{display_name}：{error}") from error


def _extract_pdf(path: Path) -> list[ExtractedSection]:
    with Path(path).open("rb") as stream:
        return list(_extract_pdf_stream(stream, Path(path).name))


def _extract_pdf_stream(stream, display_name: str):
    try:
        reader = PdfReader(stream)
        return [
            ExtractedSection(f"page-{index}", text)
            for index, page in enumerate(reader.pages, start=1)
            if (text := _normalize_text(page.extract_text() or ""))
        ]
    except Exception as error:
        raise BuildError(f"PDF 解析失败：{display_name}：{error}") from error


def _split_markdown_sections(text: str) -> list[ExtractedSection]:
    sections: list[ExtractedSection] = []
    heading = "正文"
    buffer: list[str] = []
    for line in text.splitlines():
        match = re.match(r"^#{1,6}\s+(.+?)\s*$", line)
        if match:
            if _normalize_text("\n".join(buffer)):
                sections.append(ExtractedSection(heading, _normalize_text("\n".join(buffer))))
            heading = match.group(1).strip()
            buffer = [line]
        else:
            buffer.append(line)
    final = _normalize_text("\n".join(buffer))
    if final:
        sections.append(ExtractedSection(heading, final))
    return sections


def _safe_epub_path(raw: str) -> str:
    path = PurePosixPath(raw)
    if path.is_absolute() or ".." in path.parts:
        raise BuildError(f"EPUB 包含不安全路径：{raw}")
    return str(path)


def _normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
