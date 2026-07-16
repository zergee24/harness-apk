import hashlib
import html
import re
import zipfile
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree

from pypdf import PdfReader

from .models import BuildError, ExtractedDocument, ExtractedSection


SUPPORTED_SUFFIXES = {".txt", ".md", ".markdown", ".epub", ".pdf"}


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
    try:
        with zipfile.ZipFile(path) as archive:
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
        raise BuildError(f"EPUB 解析失败：{path.name}：{error}") from error


def _extract_pdf(path: Path) -> list[ExtractedSection]:
    try:
        reader = PdfReader(str(path))
        return [
            ExtractedSection(f"page-{index}", text)
            for index, page in enumerate(reader.pages, start=1)
            if (text := _normalize_text(page.extract_text() or ""))
        ]
    except Exception as error:
        raise BuildError(f"PDF 解析失败：{path.name}：{error}") from error


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
