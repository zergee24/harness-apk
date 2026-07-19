import base64
import errno
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
from collections import Counter
from dataclasses import dataclass, replace
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, load_pem_private_key

from .corpus_pipeline import build_corpus_index_streaming
from .evaluation import evaluate_workspace, validate_declared_corpus_question_coverage
from .evaluation import read_v2_asset_bytes
from .extractors import extract_document, iter_v2_source_sections_stream
from .models import BuildError, BuildReport, CorpusShard, ExtractedDocument, PackResult
from .schema_v2 import (
    AgentAssetPaths,
    Authorship,
    SourceGenre,
    SourceRecord,
    WorkspaceV2,
    WORKSPACE_V2_SCHEMA_VERSION,
    identifier,
)


SCHEMA_VERSION = 1
CHUNK_TARGET_CHARS = 1200
CHUNK_OVERLAP_CHARS = 120
ZIP_TIMESTAMP = (2020, 1, 1, 0, 0, 0)
ALLOWED_PACKAGE_SUFFIXES = {".json", ".jsonl", ".md", ".txt"}
SOURCE_HASH_BUFFER_SIZE = 1024 * 1024
V2_STORED_NAME_MAX_BYTES = 200
V2_SOURCE_HASH_PREFIX_LENGTH = 16
V2_FILE_NAME_HASH_PREFIX_LENGTH = 16


@dataclass(frozen=True)
class _V2SourceSnapshot:
    original_path: Path
    snapshot_path: Path
    source_hash: str
    raw_size_bytes: int


def prepare_workspace(
    inputs: Iterable[Path],
    output_dir: Path,
    agent_id: str,
    name: str,
    version: int,
) -> Path:
    agent_id = identifier(agent_id, "agent id")
    name = name.strip()
    if not name:
        raise BuildError("智能体名称不能为空")
    if version < 1:
        raise BuildError("版本必须大于 0")
    input_paths = [Path(item) for item in inputs]
    if not input_paths:
        raise BuildError("至少需要一个输入文件")

    workspace = Path(output_dir).expanduser().resolve()
    if workspace.exists() and any(workspace.iterdir()):
        raise BuildError(f"输出目录必须为空：{workspace}")
    workspace.mkdir(parents=True, exist_ok=True)
    agent_dir = workspace / "agent"
    corpus_id = f"{agent_id}.corpus"
    corpus_dir = workspace / "corpora" / corpus_id
    source_dir = workspace / "sources"
    agent_dir.mkdir(parents=True)
    corpus_dir.mkdir(parents=True)
    source_dir.mkdir(parents=True)

    documents = [extract_document(path) for path in input_paths]
    chunks: list[dict[str, Any]] = []
    source_rows: list[dict[str, Any]] = []
    for document in documents:
        stored_name = f"{document.source_hash[:16]}-{_safe_filename(document.source_path.name)}"
        shutil.copyfile(document.source_path, source_dir / stored_name)
        source_rows.append(
            {
                "title": document.title,
                "fileName": document.source_path.name,
                "storedName": stored_name,
                "sourceHash": document.source_hash,
                "format": document.source_path.suffix.lower().lstrip("."),
            }
        )
        for section in document.sections:
            for index, text in enumerate(_chunk_text(section.text)):
                chunk_id = "chunk-" + hashlib.sha256(
                    f"{document.source_hash}\n{section.location}\n{index}\n{text}".encode("utf-8")
                ).hexdigest()[:16]
                ngrams = _chinese_ngrams(text)
                chunks.append(
                    {
                        "id": chunk_id,
                        "sourceTitle": document.title,
                        "sourceHash": document.source_hash,
                        "location": f"{section.location} · {index + 1}",
                        "text": text,
                        "keywords": _keywords(text, ngrams),
                        "ngrams": ngrams,
                    }
                )
    if not chunks:
        raise BuildError("输入资料没有生成任何文本块")

    corpus_hash = hashlib.sha256(
        "\n".join(sorted(document.source_hash for document in documents)).encode("ascii")
    ).hexdigest()
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "agent": {
            "id": agent_id,
            "name": name,
            "version": version,
            "summary": "基于用户导入资料构建的模拟代理",
            "personaPath": "agent/persona.md",
            "worldviewPath": "agent/worldview.jsonl",
            "conceptsPath": "agent/concepts.json",
            "examplesPath": "agent/examples.jsonl",
            "evalPath": "agent/eval.jsonl",
            "requiredCorpora": [corpus_id],
        },
        "corpora": [
            {
                "id": corpus_id,
                "title": "选定资料",
                "sourceHash": corpus_hash,
                "sourcesPath": f"corpora/{corpus_id}/sources.json",
                "chunksPath": f"corpora/{corpus_id}/chunks.jsonl",
                "required": True,
            }
        ],
    }
    _write_json(workspace / "workspace.json", manifest)
    _write_json(corpus_dir / "sources.json", source_rows)
    _write_jsonl(corpus_dir / "chunks.jsonl", chunks)
    (agent_dir / "persona.md").write_text(
        f"我是{name}，属于基于用户所选资料构建的模拟代理。\n\n"
        "我必须使用第一人称表达，但只依据已提供资料形成判断；资料不足时明确说明。\n",
        encoding="utf-8",
    )
    (agent_dir / "worldview.jsonl").write_text("", encoding="utf-8")
    _write_json(agent_dir / "concepts.json", {"concepts": []})
    (agent_dir / "examples.jsonl").write_text("", encoding="utf-8")
    (agent_dir / "eval.jsonl").write_text("", encoding="utf-8")
    return workspace


def prepare_workspace_v2(
    inputs: Iterable[Path],
    output_dir: Path,
    agent_id: str,
    name: str,
    version: int,
    source_catalog_path: Path | None = None,
) -> Path:
    agent_id = identifier(agent_id, "agent id")
    name = name.strip()
    if not name:
        raise BuildError("智能体名称不能为空")
    if type(version) is not int or version < 1:
        raise BuildError("版本必须是正整数")
    input_paths = [Path(item) for item in inputs]
    if not input_paths:
        raise BuildError("至少需要一个输入文件")

    workspace = Path(output_dir).expanduser().resolve()
    if workspace.exists() and any(workspace.iterdir()):
        raise BuildError(f"输出目录必须为空：{workspace}")
    catalog = _load_source_catalog(source_catalog_path) if source_catalog_path else None
    workspace.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{workspace.name}.staging-", dir=workspace.parent))
    try:
        snapshots = _snapshot_v2_inputs(input_paths, staging / ".incoming")
        documents = sorted(
            (
                ExtractedDocument(
                    title=snapshot.original_path.stem,
                    source_path=snapshot.original_path,
                    source_hash=snapshot.source_hash,
                    sections=[],
                )
                for snapshot in snapshots
            ),
            key=lambda document: (document.source_hash, document.source_path.name),
        )
        snapshot_by_key = {
            (snapshot.source_hash, snapshot.original_path.name): snapshot
            for snapshot in snapshots
        }
        sources = _build_source_records(
            documents,
            catalog,
            raw_size_bytes={
                key: snapshot.raw_size_bytes for key, snapshot in snapshot_by_key.items()
            },
        )
        source_dir = staging / "sources"
        source_dir.mkdir()
        for source in sources:
            snapshot = snapshot_by_key[(source.source_hash, source.file_name)]
            snapshot.snapshot_path.replace(source_dir / source.stored_name)
        shutil.rmtree(staging / ".incoming", ignore_errors=True)

        extracted_chars_by_source: dict[str, int] = {}
        root_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
        source_root_descriptor = os.open(source_dir, root_flags)
        try:
            root_info = os.fstat(source_root_descriptor)
            if not stat.S_ISDIR(root_info.st_mode):
                raise BuildError("来源目录不是目录")

            def sections_for_index(source: SourceRecord):
                extracted_chars = 0
                for section in _iter_v2_source_sections_at(source_root_descriptor, source):
                    if section.text.strip():
                        extracted_chars += len(section.text)
                    yield section
                if not extracted_chars:
                    raise BuildError(f"没有可提取文本：{source.file_name}")
                extracted_chars_by_source[source.source_id] = extracted_chars

            build_corpus_index_streaming(staging, sources, sections_for_index)
        finally:
            os.close(source_root_descriptor)
        sources = [
            replace(source, extracted_chars=extracted_chars_by_source[source.source_id])
            for source in sources
        ]
        manifest = WorkspaceV2(
            agent_id=agent_id,
            name=name,
            version=version,
            assets=AgentAssetPaths(),
            sources=tuple(sources),
        )
        _write_workspace_v2(staging, manifest, documents, copy_sources=False)
        _verify_staged_v2_sources(staging, manifest.sources)
        if workspace.exists():
            workspace.rmdir()
        staging.replace(workspace)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return workspace


def load_workspace_v2(workspace: Path) -> WorkspaceV2:
    workspace = Path(workspace).expanduser().resolve()
    try:
        return WorkspaceV2.from_dict(_read_json(workspace / "workspace.json"))
    except (OSError, json.JSONDecodeError, RecursionError) as error:
        raise BuildError(f"workspace.json 无法读取：{error}") from error


def validate_workspace_v2(workspace: Path) -> BuildReport:
    workspace = Path(workspace).expanduser().resolve()
    try:
        manifest = load_workspace_v2(workspace)
    except BuildError as error:
        return BuildReport(False, [str(error)])

    errors: list[str] = []
    if any(
        source.genre == SourceGenre.UNKNOWN
        or source.authorship == Authorship.UNKNOWN
        or source.period == "unknown"
        for source in manifest.sources
    ):
        errors.append("来源元数据仍有未确认项")
    _validate_v2_source_files(workspace, manifest.sources, errors)
    evaluation = evaluate_workspace(workspace)
    errors.extend(error for error in evaluation.errors if error not in errors)
    metrics = {
        "sourceCount": len(manifest.sources),
        "schemaVersion": WORKSPACE_V2_SCHEMA_VERSION,
    }
    metrics.update(evaluation.metrics())
    return BuildReport(
        publishable=not errors,
        errors=errors,
        metrics=metrics,
    )


def _load_source_catalog(path: Path) -> dict[str, dict[str, str]]:
    try:
        catalog = _read_json(Path(path))
    except (OSError, json.JSONDecodeError, RecursionError) as error:
        raise BuildError(f"来源目录无法读取：{error}") from error
    if not isinstance(catalog, dict):
        raise BuildError("来源目录必须是 JSON 对象")
    if "schemaVersion" in catalog and (
        isinstance(catalog["schemaVersion"], bool)
        or not isinstance(catalog["schemaVersion"], int)
        or catalog["schemaVersion"] != WORKSPACE_V2_SCHEMA_VERSION
    ):
        raise BuildError(f"不支持的来源目录 schemaVersion：{catalog['schemaVersion']}")
    rows = catalog.get("sources")
    if not isinstance(rows, list) or not rows:
        raise BuildError("来源目录 sources 必须是非空数组")

    by_file_name: dict[str, dict[str, str]] = {}
    source_ids: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise BuildError("来源目录中的 source 必须是对象")
        values = {
            key: (
                _catalog_file_name(row.get(key), f"来源目录 {key}")
                if key == "fileName"
                else (
                    identifier(row.get(key), f"来源目录 {key}")
                    if key == "sourceId"
                    else _catalog_string(row.get(key), f"来源目录 {key}")
                )
            )
            for key in ("sourceId", "fileName", "title", "genre", "authorship", "period")
        }
        try:
            SourceGenre(values["genre"])
        except ValueError as error:
            raise BuildError(f"来源目录 genre 无效：{values['genre']}") from error
        try:
            Authorship(values["authorship"])
        except ValueError as error:
            raise BuildError(f"来源目录 authorship 无效：{values['authorship']}") from error
        if values["sourceId"] in source_ids:
            raise BuildError(f"来源目录存在重复 sourceId：{values['sourceId']}")
        if values["fileName"] in by_file_name:
            raise BuildError(f"来源目录存在重复 fileName：{values['fileName']}")
        source_ids.add(values["sourceId"])
        by_file_name[values["fileName"]] = values
    return by_file_name


def _snapshot_v2_inputs(inputs: list[Path], incoming_dir: Path) -> list[_V2SourceSnapshot]:
    incoming_dir.mkdir()
    snapshots: list[_V2SourceSnapshot] = []
    for index, raw_path in enumerate(inputs):
        source_path = Path(raw_path).expanduser()
        suffix = source_path.suffix.lower()
        if suffix not in {".txt", ".md", ".markdown", ".epub", ".pdf"}:
            raise BuildError(f"不支持的输入格式：{source_path.name}")
        snapshot_path = incoming_dir / f"{index:08d}{suffix}"
        source_hash, raw_size = _copy_v2_source(source_path, snapshot_path)
        snapshots.append(
            _V2SourceSnapshot(
                original_path=source_path,
                snapshot_path=snapshot_path,
                source_hash=source_hash,
                raw_size_bytes=raw_size,
            )
        )
    return snapshots


def _copy_v2_source(source_path: Path, destination: Path) -> tuple[str, int]:
    """Copy one regular input through a no-follow descriptor and verify its identity."""
    try:
        expected = source_path.lstat()
    except OSError as error:
        raise BuildError(f"输入文件不存在：{source_path}") from error
    if stat.S_ISLNK(expected.st_mode) or not stat.S_ISREG(expected.st_mode):
        raise BuildError(f"输入文件必须是普通文件：{source_path}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(source_path, flags)
    except OSError as error:
        raise BuildError(f"输入文件无法安全打开：{source_path}：{error}") from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or not _same_file_identity(expected, before):
            raise BuildError(f"输入来源在读取期间发生变化：{source_path}")
        digest = hashlib.sha256()
        raw_size = 0
        with os.fdopen(descriptor, "rb", closefd=False) as source, destination.open("xb") as target:
            while block := source.read(SOURCE_HASH_BUFFER_SIZE):
                digest.update(block)
                raw_size += len(block)
                target.write(block)
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            destination.unlink(missing_ok=True)
            raise BuildError(f"输入来源在读取期间发生变化：{source_path}")
    except OSError as error:
        destination.unlink(missing_ok=True)
        raise BuildError(f"输入文件无法读取：{source_path}：{error}") from error
    finally:
        os.close(descriptor)
    verified_destination = destination.with_name(f"{destination.name}.verified")
    try:
        # The first copy is the no-follow descriptor snapshot of user input.
        # This compatibility copy only reads that private staging snapshot.
        shutil.copyfile(destination, verified_destination)
        verified_destination.replace(destination)
    except OSError:
        destination.unlink(missing_ok=True)
        verified_destination.unlink(missing_ok=True)
        raise
    return digest.hexdigest(), raw_size


def _same_file_identity(left: os.stat_result, right: os.stat_result) -> bool:
    return (
        left.st_dev,
        left.st_ino,
        left.st_size,
        left.st_mtime_ns,
        left.st_ctime_ns,
    ) == (
        right.st_dev,
        right.st_ino,
        right.st_size,
        right.st_mtime_ns,
        right.st_ctime_ns,
    )


def _iter_v2_source_sections_at(root_descriptor: int, source: SourceRecord):
    """Hash and parse one staged source through the same no-follow descriptor."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(source.stored_name, flags, dir_fd=root_descriptor)
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise BuildError(f"来源文件必须是普通文件：{source.stored_name}")
        source_hash, raw_size_bytes = _sha256_and_size_descriptor(descriptor)
        if source_hash != source.source_hash or raw_size_bytes != source.raw_size_bytes:
            raise BuildError(f"来源文件在索引前发生变化：{source.stored_name}")
        os.lseek(descriptor, 0, os.SEEK_SET)
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            yield from iter_v2_source_sections_stream(
                stream,
                Path(source.stored_name).suffix,
                source.file_name,
            )
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise BuildError(f"来源文件在读取期间发生变化：{source.stored_name}")
    except OSError as error:
        raise BuildError(f"来源文件无法读取：{source.stored_name}：{error}") from error
    finally:
        os.close(descriptor)


def _sha256_and_size_descriptor(descriptor: int) -> tuple[str, int]:
    digest = hashlib.sha256()
    raw_size_bytes = 0
    os.lseek(descriptor, 0, os.SEEK_SET)
    with os.fdopen(os.dup(descriptor), "rb") as stream:
        while block := stream.read(SOURCE_HASH_BUFFER_SIZE):
            digest.update(block)
            raw_size_bytes += len(block)
    os.lseek(descriptor, 0, os.SEEK_SET)
    return digest.hexdigest(), raw_size_bytes


def _verify_staged_v2_sources(workspace: Path, sources: tuple[SourceRecord, ...]) -> None:
    errors: list[str] = []
    _validate_v2_source_files(workspace, sources, errors)
    if errors:
        raise BuildError("来源快照验证失败：" + "；".join(errors))


def _build_source_records(
    documents: list[Any],
    catalog: dict[str, dict[str, str]] | None,
    raw_size_bytes: dict[tuple[str, str], int] | None = None,
) -> list[SourceRecord]:
    input_file_names = {document.source_path.name for document in documents}
    if len(input_file_names) != len(documents):
        raise BuildError("输入资料文件名不能重复")
    if catalog is not None and set(catalog) != input_file_names:
        raise BuildError("来源目录必须与输入资料逐一对应")

    records: list[SourceRecord] = []
    for document in documents:
        metadata = catalog.get(document.source_path.name) if catalog else None
        stored_name = _v2_stored_name(document)
        records.append(
            SourceRecord(
                source_id=(
                    metadata["sourceId"]
                    if metadata
                    else _v2_default_source_id(document)
                ),
                title=metadata["title"] if metadata else _default_source_title(document),
                file_name=document.source_path.name,
                stored_name=stored_name,
                source_hash=document.source_hash,
                format=document.source_path.suffix.lower().lstrip("."),
                genre=SourceGenre(metadata["genre"]) if metadata else SourceGenre.UNKNOWN,
                authorship=Authorship(metadata["authorship"]) if metadata else Authorship.UNKNOWN,
                period=metadata["period"] if metadata else "unknown",
                raw_size_bytes=(
                    raw_size_bytes[(document.source_hash, document.source_path.name)]
                    if raw_size_bytes is not None
                    else document.source_path.stat().st_size
                ),
                extracted_chars=sum(len(section.text) for section in document.sections),
            )
        )
    source_ids = [record.source_id for record in records]
    stored_names = [record.stored_name for record in records]
    if len(source_ids) != len(set(source_ids)):
        raise BuildError("来源目录生成了重复 sourceId")
    if len(stored_names) != len(set(stored_names)):
        raise BuildError("输入资料生成了重复 storedName")
    return records


def _catalog_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BuildError(f"{label} 必须是非空字符串")
    return value.strip()


def _catalog_file_name(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise BuildError(f"{label} 必须是非空字符串")
    return value


def _default_source_title(document: Any) -> str:
    title = document.title.strip()
    return title or f"资料-{document.source_hash[:16]}"


def _write_workspace_v2(
    workspace: Path,
    manifest: WorkspaceV2,
    documents: list[Any],
    copy_sources: bool = True,
) -> None:
    agent_dir = workspace / "agent"
    source_dir = workspace / "sources"
    agent_dir.mkdir()
    if copy_sources:
        source_dir.mkdir()
        for document, source in zip(documents, manifest.sources, strict=True):
            shutil.copyfile(document.source_path, source_dir / source.stored_name)
    _write_json(workspace / "workspace.json", manifest.to_dict())
    _write_json(
        workspace / "source-catalog.json",
        {
            "schemaVersion": WORKSPACE_V2_SCHEMA_VERSION,
            "sources": [source.to_dict() for source in manifest.sources],
        },
    )
    (agent_dir / "persona.md").write_text(
        f"我是{manifest.name}，属于基于用户所选资料构建的模拟代理。\n\n"
        "我必须使用第一人称表达，但只依据已提供资料形成判断；资料不足时明确说明。\n",
        encoding="utf-8",
    )
    _write_json(
        agent_dir / "identity.json",
        {"selfNames": [], "timeHorizon": "", "roles": [], "relationships": []},
    )
    _write_json(
        agent_dir / "voice.json",
        {
            "defaultForm": "",
            "sentenceRhythm": [],
            "rhetoricalMoves": [],
            "preferredTerms": [],
            "avoidPatterns": [],
            "evidence": [],
        },
    )
    (agent_dir / "worldview.jsonl").write_text("", encoding="utf-8")
    (agent_dir / "episodes.jsonl").write_text("", encoding="utf-8")
    _write_json(agent_dir / "concepts.json", {"concepts": []})
    (agent_dir / "examples.jsonl").write_text("", encoding="utf-8")
    _write_json(agent_dir / "openers.json", {"default": "", "alternatives": []})
    (agent_dir / "eval.jsonl").write_text("", encoding="utf-8")


def _validate_v2_source_files(workspace: Path, sources: tuple[SourceRecord, ...], errors: list[str]) -> None:
    source_root = workspace / "sources"
    try:
        root_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
        root_descriptor = os.open(source_root, root_flags)
        root_info = os.fstat(root_descriptor)
        if not stat.S_ISDIR(root_info.st_mode):
            raise OSError("sources 不是目录")
    except OSError as error:
        errors.append(f"来源目录缺失或不安全：sources：{error}")
        return
    try:
        for source in sources:
            try:
                source_hash, raw_size_bytes = _sha256_and_size_at(root_descriptor, source.stored_name)
            except FileNotFoundError:
                errors.append(f"来源文件缺失：sources/{source.stored_name}")
                continue
            except OSError as error:
                if error.errno == errno.ELOOP:
                    errors.append(f"来源文件必须是 sources 内的普通文件：{source.stored_name}")
                else:
                    errors.append(f"来源文件无法读取：{source.stored_name}：{error}")
                continue
            if raw_size_bytes != source.raw_size_bytes:
                errors.append(f"来源文件字节数不匹配：{source.stored_name}")
            if source_hash != source.source_hash:
                errors.append(f"来源文件 SHA-256 不匹配：{source.stored_name}")
    finally:
        os.close(root_descriptor)


def _sha256_and_size_at(root_descriptor: int, stored_name: str) -> tuple[str, int]:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(stored_name, flags, dir_fd=root_descriptor)
    digest = hashlib.sha256()
    raw_size_bytes = 0
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise OSError("来源文件必须是普通文件")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            while chunk := source.read(SOURCE_HASH_BUFFER_SIZE):
                digest.update(chunk)
                raw_size_bytes += len(chunk)
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise OSError("来源文件在读取期间发生变化")
        return digest.hexdigest(), raw_size_bytes
    finally:
        os.close(descriptor)


def validate_workspace(workspace: Path) -> BuildReport:
    workspace = Path(workspace).expanduser().resolve()
    errors: list[str] = []
    warnings: list[str] = []
    try:
        manifest = _read_json(workspace / "workspace.json")
    except (OSError, json.JSONDecodeError) as error:
        report = BuildReport(False, [f"workspace.json 无法读取：{error}"])
        _write_report(workspace, report)
        return report
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(f"不支持的 schemaVersion：{manifest.get('schemaVersion')}")

    chunk_rows: list[dict[str, Any]] = []
    for corpus in manifest.get("corpora", []):
        try:
            chunk_rows.extend(_read_jsonl(workspace / _safe_relative_path(corpus["chunksPath"])))
        except (KeyError, OSError, json.JSONDecodeError, BuildError) as error:
            errors.append(f"资料块无法读取：{error}")
    chunks = {row.get("id"): row for row in chunk_rows if isinstance(row.get("id"), str)}
    if not chunks:
        errors.append("没有可用资料块")

    agent = manifest.get("agent", {})
    persona_path = workspace / _safe_relative_path(agent.get("personaPath", "agent/persona.md"))
    if not persona_path.is_file() or not persona_path.read_text("utf-8").strip():
        errors.append("persona.md 不能为空")

    worldview_rows = _read_jsonl_safe(workspace / "agent" / "worldview.jsonl", errors, "worldview")
    if not worldview_rows:
        errors.append("worldview.jsonl 至少需要一条观点")
    for row in worldview_rows:
        statement = str(row.get("statement", "")).strip()
        evidence = row.get("evidence", [])
        if not statement:
            errors.append(f"观点 {row.get('id', '<unknown>')} 缺少 statement")
        if not evidence:
            errors.append(f"观点 {row.get('id', '<unknown>')} 缺少 evidence")
        for chunk_id in evidence:
            if chunk_id not in chunks:
                errors.append(f"观点 {row.get('id', '<unknown>')} 引用了不存在的 {chunk_id}")

    eval_rows = _read_jsonl_safe(workspace / "agent" / "eval.jsonl", errors, "eval")
    required_corpus_count = len(agent.get("requiredCorpora", []))
    minimum_eval_count = max(20, required_corpus_count * 2)
    if len(eval_rows) < minimum_eval_count:
        errors.append(f"评估题不足：需要 {minimum_eval_count}，实际 {len(eval_rows)}")
    hits = 0
    for row in eval_rows:
        question = str(row.get("question", "")).strip()
        expected = [item for item in row.get("expectedEvidence", []) if isinstance(item, str)]
        if not question or not expected:
            errors.append(f"评估题 {row.get('id', '<unknown>')} 缺少 question 或 expectedEvidence")
            continue
        missing = [chunk_id for chunk_id in expected if chunk_id not in chunks]
        if missing:
            errors.append(f"评估题 {row.get('id', '<unknown>')} 引用了不存在的 {', '.join(missing)}")
            continue
        top_ids = [item["id"] for item in _rank_chunks(question, chunk_rows)[:8]]
        if any(chunk_id in top_ids for chunk_id in expected):
            hits += 1
    hit_rate = hits / len(eval_rows) if eval_rows else 0.0
    if eval_rows and hit_rate < 0.85:
        errors.append(f"Top 8 来源命中率不足：{hit_rate:.1%}，要求至少 85.0%")

    report = BuildReport(
        publishable=not errors,
        errors=errors,
        warnings=warnings,
        metrics={
            "sourceCount": sum(
                len(_read_json(workspace / _safe_relative_path(corpus["sourcesPath"])))
                for corpus in manifest.get("corpora", [])
            ),
            "chunkCount": len(chunks),
            "worldviewCount": len(worldview_rows),
            "evalCount": len(eval_rows),
            "top8HitRate": round(hit_rate, 4),
        },
    )
    _write_report(workspace, report)
    return report


def pack_workspace(
    workspace: Path,
    output_dir: Path,
    private_key_path: Path,
    include_sources: bool = False,
) -> PackResult:
    workspace = Path(workspace).expanduser().resolve()
    report = validate_workspace(workspace)
    if not report.publishable:
        raise BuildError("构建验证未通过：" + "；".join(report.errors))
    manifest = _read_json(workspace / "workspace.json")
    agent = manifest["agent"]
    output = Path(output_dir).expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    private_key = _load_private_key(Path(private_key_path))
    stem = f"{_safe_filename(agent['id'])}-v{agent['version']}"

    agent_files = {
        "manifest.json": _canonical_json_bytes({"schemaVersion": SCHEMA_VERSION, "type": "hagent", **agent}),
        "persona.md": (workspace / agent["personaPath"]).read_bytes(),
        "worldview.jsonl": (workspace / agent["worldviewPath"]).read_bytes(),
        "concepts.json": (workspace / agent["conceptsPath"]).read_bytes(),
        "examples.jsonl": (workspace / agent["examplesPath"]).read_bytes(),
        "eval.jsonl": (workspace / agent["evalPath"]).read_bytes(),
    }
    agent_package = _write_signed_package(output / f"{stem}.hagent", agent_files, private_key)

    corpus_packages: list[Path] = []
    corpus_bundle_files: dict[str, bytes] = {}
    for corpus in manifest["corpora"]:
        corpus_id = corpus["id"]
        corpus_files = {
            "manifest.json": _canonical_json_bytes({"schemaVersion": SCHEMA_VERSION, "type": "hcorpus", **corpus}),
            "sources.json": (workspace / corpus["sourcesPath"]).read_bytes(),
            "chunks.jsonl": (workspace / corpus["chunksPath"]).read_bytes(),
        }
        corpus_packages.append(
            _write_signed_package(output / f"{_safe_filename(corpus_id)}.hcorpus", corpus_files, private_key)
        )
        for relative, payload in corpus_files.items():
            corpus_bundle_files[f"corpora/{corpus_id}/{relative}"] = payload

    source_packages: list[Path] = []
    source_bundle_files: dict[str, bytes] = {}
    if include_sources:
        source_files = {
            f"files/{path.name}": path.read_bytes()
            for path in sorted((workspace / "sources").iterdir(), key=lambda item: item.name)
            if path.is_file()
        }
        source_manifest = {
            "schemaVersion": SCHEMA_VERSION,
            "type": "hsource",
            "agentId": agent["id"],
            "version": agent["version"],
            "fileCount": len(source_files),
        }
        source_files = {"manifest.json": _canonical_json_bytes(source_manifest), **source_files}
        source_package = _write_signed_package(output / f"{stem}.hsource", source_files, private_key)
        source_packages.append(source_package)
        source_bundle_files = {f"sources/{key}": value for key, value in source_files.items()}

    bundle_files = {
        "bundle-manifest.json": _canonical_json_bytes(manifest),
        **{f"agent/{key}": value for key, value in agent_files.items()},
        **corpus_bundle_files,
        **source_bundle_files,
    }
    bundle_package = _write_signed_package(output / f"{stem}.hbundle", bundle_files, private_key)
    report_path = output / f"{stem}-build-report.json"
    report_path.write_bytes(_canonical_json_bytes(report.to_dict()))
    return PackResult(agent_package, corpus_packages, source_packages, bundle_package, report_path)


def pack_workspace_v2(
    workspace: Path,
    output_dir: Path,
    private_key_path: Path,
    profile_id: str = "balanced",
    emit_sources: bool = False,
) -> PackResult:
    from .install_planner import CorpusPlanIndex, choose_install_profiles

    if profile_id not in {"lite", "balanced", "complete", "source"}:
        raise BuildError(f"未知 profile：{profile_id}")
    workspace = Path(workspace).expanduser().resolve()
    manifest, validation = _validate_workspace_v2_for_pack(workspace)
    if not validation.publishable:
        raise BuildError("构建验证未通过：" + "；".join(validation.errors))
    output = Path(output_dir).expanduser().resolve()
    output_parent = output.parent
    output_parent.mkdir(parents=True, exist_ok=True)
    private_key = _load_private_key(Path(private_key_path))
    stem = f"{_safe_filename(manifest.agent_id)}-v{manifest.version}"
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.staging-", dir=output_parent))
    published: list[Path] = []
    created_output = False
    try:
        package_root = staging / "packages"
        package_root.mkdir()
        with CorpusPlanIndex(workspace) as planner:
            if planner.manifest != manifest:
                raise BuildError("workspace manifest 在验证与规划之间发生变化")
            logical_shards = planner.shards(materialize_ids=False)
            logical_sources = [
                CorpusShard.source(
                    package_id=f"source-{source.source_id}",
                    source_ids=(source.source_id,),
                    source_hashes=(source.source_hash,),
                )
                for source in sorted(manifest.sources, key=lambda item: item.source_id)
            ] if emit_sources else []
            logical_plan = choose_install_profiles([*logical_shards, *logical_sources])
            install_classes = {
                package.package_id: package.install_class
                for package in logical_plan.packages
            }
            evaluation = evaluate_workspace(workspace)
            coverage_errors = validate_declared_corpus_question_coverage(
                evaluation,
                install_classes,
            )
            coverage_errors.extend(planner.validate_declared_corpus_questions(install_classes))
            if coverage_errors:
                raise BuildError("语料评估题归属校验失败：" + "；".join(dict.fromkeys(coverage_errors)))
            logical_shards = [
                replace(shard, install_class=install_classes[shard.package_id])
                for shard in logical_shards
            ]
            corpus_artifacts: list[CorpusShard] = []
            corpus_paths: dict[str, Path] = {}
            for shard in logical_shards:
                target = package_root / shard.file_name
                _pack_corpus_shard_v2(planner, shard, target, private_key, staging)
                sha256, size_bytes = _hash_regular_file(target)
                artifact = shard.with_artifact(target.name, size_bytes, sha256)
                corpus_artifacts.append(artifact)
                corpus_paths[artifact.package_id] = target

            source_artifacts: list[CorpusShard] = []
            source_paths: dict[str, Path] = {}
            if emit_sources:
                for source in sorted(manifest.sources, key=lambda item: item.source_id):
                    package_id = f"source-{source.source_id}"
                    target = package_root / f"{package_id}.hsource"
                    _pack_source_v2(
                        workspace,
                        manifest.agent_id,
                        manifest.version,
                        source,
                        target,
                        private_key,
                    )
                    sha256, size_bytes = _hash_regular_file(target)
                    artifact = CorpusShard.source(
                        package_id=package_id,
                        source_ids=(source.source_id,),
                        source_hashes=(source.source_hash,),
                        file_name=target.name,
                        size_bytes=size_bytes,
                        sha256=sha256,
                    )
                    source_artifacts.append(artifact)
                    source_paths[package_id] = target

            install_plan = choose_install_profiles([*corpus_artifacts, *source_artifacts])
            install_plan_bytes = _canonical_json_bytes(
                install_plan.to_dict(require_artifacts=True)
            )
            agent_target = package_root / f"{stem}.hagent"
            _pack_agent_v2(
                workspace,
                manifest,
                install_plan_bytes,
                agent_target,
                private_key,
            )
            agent_sha256, agent_size = _hash_regular_file(agent_target)

            selected_ids = install_plan.profile(profile_id).package_ids
            child_paths = {**corpus_paths, **source_paths}
            selected_paths = []
            for package_id in selected_ids:
                child = child_paths.get(package_id)
                if child is None:
                    raise BuildError(f"profile 引用了未生成安装包：{package_id}")
                selected_paths.append(child)
            bundle_target = package_root / f"{stem}-{profile_id}.hbundle"
            _pack_bundle_v2(
                manifest,
                profile_id,
                agent_target,
                agent_sha256,
                agent_size,
                install_plan,
                selected_paths,
                bundle_target,
                private_key,
            )
            _, bundle_size = _hash_regular_file(bundle_target)
            index_metrics = _read_small_json(
                workspace / "corpora" / "index" / "report.json"
            )
            report_target = package_root / f"{stem}-{profile_id}-build-report.json"
            _write_json(
                report_target,
                {
                    "agentId": manifest.agent_id,
                    "bundleSizeBytes": bundle_size,
                    "children": [
                        {
                            "fileName": package.file_name,
                            "id": package.package_id,
                            "sha256": package.sha256,
                            "sizeBytes": package.size_bytes,
                            "type": package.package_type,
                        }
                        for package in install_plan.packages
                    ],
                    "corpusMetrics": {
                        "deduplicatedChunks": index_metrics.get(
                            "chunksAfterDeduplication", 0
                        ),
                        "extractedCharacters": index_metrics.get(
                            "extractedCharacters", 0
                        ),
                        "rawBytes": index_metrics.get("rawBytes", 0),
                        "sourceChunks": index_metrics.get(
                            "chunksBeforeDeduplication", 0
                        ),
                    },
                    "profile": profile_id,
                    "selectedPackageIds": list(selected_ids),
                    "sourcesEmitted": emit_sources,
                    "version": manifest.version,
                },
            )

        final_names = sorted(path.name for path in package_root.iterdir())
        if output.exists():
            if not output.is_dir():
                raise BuildError(f"输出路径不是目录：{output}")
        else:
            output.mkdir()
            created_output = True
        conflicts = [name for name in final_names if (output / name).exists()]
        if conflicts:
            raise BuildError("输出文件已存在：" + ", ".join(conflicts))
        try:
            for name in final_names:
                source = package_root / name
                destination = output / name
                os.link(source, destination, follow_symlinks=False)
                published.append(destination)
                source.unlink()
        except BaseException:
            for path in published:
                path.unlink(missing_ok=True)
            raise

        corpus_packages = [
            output / corpus_paths[shard.package_id].name for shard in corpus_artifacts
        ]
        source_packages = [
            output / source_paths[shard.package_id].name for shard in source_artifacts
        ]
        return PackResult(
            output / agent_target.name,
            corpus_packages,
            source_packages,
            output / bundle_target.name,
            output / report_target.name,
        )
    except BuildError:
        for path in published:
            path.unlink(missing_ok=True)
        raise
    except BaseException as error:
        for path in published:
            path.unlink(missing_ok=True)
        raise BuildError(f"V2 打包失败：{error}") from error
    finally:
        shutil.rmtree(staging, ignore_errors=True)
        if created_output:
            try:
                output.rmdir()
            except OSError:
                pass


def _validate_workspace_v2_for_pack(
    workspace: Path,
) -> tuple[WorkspaceV2, BuildReport]:
    manifest = load_workspace_v2(workspace)
    errors: list[str] = []
    if any(
        source.genre == SourceGenre.UNKNOWN
        or source.authorship == Authorship.UNKNOWN
        or source.period == "unknown"
        for source in manifest.sources
    ):
        errors.append("来源元数据仍有未确认项")
    try:
        _validate_v2_source_types_without_read(workspace, manifest.sources)
    except BuildError as error:
        errors.append(str(error))
    evaluation = evaluate_workspace(workspace)
    errors.extend(error for error in evaluation.errors if error not in errors)
    metrics = {
        "sourceCount": len(manifest.sources),
        "schemaVersion": WORKSPACE_V2_SCHEMA_VERSION,
    }
    metrics.update(evaluation.metrics())
    return manifest, BuildReport(not errors, errors=errors, metrics=metrics)


def _validate_v2_source_types_without_read(
    workspace: Path,
    sources: tuple[SourceRecord, ...],
) -> None:
    source_root = workspace / "sources"
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(source_root, flags)
    except OSError as error:
        raise BuildError(f"来源目录缺失或不安全：sources：{error}") from error
    try:
        if not stat.S_ISDIR(os.fstat(descriptor).st_mode):
            raise BuildError("来源目录必须是普通目录：sources")
        for source in sources:
            try:
                info = os.stat(
                    source.stored_name,
                    dir_fd=descriptor,
                    follow_symlinks=False,
                )
            except OSError as error:
                raise BuildError(f"来源文件无法检查：{source.stored_name}：{error}") from error
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                raise BuildError(f"来源文件必须是普通文件：{source.stored_name}")
    finally:
        os.close(descriptor)


def _pack_corpus_shard_v2(
    planner: Any,
    shard: CorpusShard,
    target: Path,
    private_key: Ed25519PrivateKey,
    staging: Path,
) -> None:
    entry_root = Path(tempfile.mkdtemp(prefix=".corpus-", dir=staging))
    try:
        sources_path = entry_root / "sources.json"
        nodes_path = entry_root / "nodes.jsonl"
        chunks_path = entry_root / "chunks.jsonl"
        duplicates_path = entry_root / "duplicates.jsonl"
        source_rows = list(planner.iter_sources(shard))
        _write_json(sources_path, source_rows)
        _write_jsonl_stream(nodes_path, planner.iter_nodes(shard))
        _write_jsonl_stream(chunks_path, planner.iter_chunks(shard))
        _write_jsonl_stream(duplicates_path, planner.iter_duplicates(shard))
        metrics = planner.metrics(shard)
        manifest = {
            "agentId": planner.manifest.agent_id,
            "chunkCount": metrics["chunkCount"],
            "id": shard.package_id,
            "installClass": shard.install_class,
            "periods": list(shard.periods),
            "schemaVersion": WORKSPACE_V2_SCHEMA_VERSION,
            "sourceIds": [row["sourceId"] for row in source_rows],
            "sourceHashes": [row["sourceHash"] for row in source_rows],
            "topLevelIds": list(shard.top_level_ids),
            "type": "hcorpus",
            "version": planner.manifest.version,
        }
        _write_signed_package_v2(
            target,
            {
                "chunks.jsonl": chunks_path,
                "duplicates.jsonl": duplicates_path,
                "manifest.json": _canonical_json_bytes(manifest),
                "nodes.jsonl": nodes_path,
                "sources.json": sources_path,
            },
            private_key,
        )
    finally:
        shutil.rmtree(entry_root, ignore_errors=True)


def _pack_source_v2(
    workspace: Path,
    agent_id: str,
    version: int,
    source: SourceRecord,
    target: Path,
    private_key: Ed25519PrivateKey,
) -> None:
    source_path = workspace / "sources" / source.stored_name
    actual_hash, actual_size = _hash_regular_file(source_path)
    if actual_hash != source.source_hash or actual_size != source.raw_size_bytes:
        raise BuildError(f"来源文件大小或哈希不匹配：{source.stored_name}")
    _write_signed_package_v2(
        target,
        {
            f"files/{source.stored_name}": source_path,
            "manifest.json": _canonical_json_bytes(
                {
                    "agentId": agent_id,
                    "fileName": source.file_name,
                    "id": f"source-{source.source_id}",
                    "schemaVersion": WORKSPACE_V2_SCHEMA_VERSION,
                    "sourceHash": source.source_hash,
                    "sourceId": source.source_id,
                    "storedName": source.stored_name,
                    "type": "hsource",
                    "version": version,
                }
            ),
        },
        private_key,
        expected_files={
            f"files/{source.stored_name}": (
                source.source_hash,
                source.raw_size_bytes,
            )
        },
    )


def _pack_agent_v2(
    workspace: Path,
    manifest: WorkspaceV2,
    install_plan: bytes,
    target: Path,
    private_key: Ed25519PrivateKey,
) -> None:
    asset_entries = {
        "agent/persona.md": manifest.assets.persona,
        "agent/identity.json": manifest.assets.identity,
        "agent/voice.json": manifest.assets.voice,
        "agent/worldview.jsonl": manifest.assets.worldview,
        "agent/episodes.jsonl": manifest.assets.episodes,
        "agent/concepts.json": manifest.assets.concepts,
        "agent/examples.jsonl": manifest.assets.examples,
        "agent/openers.json": manifest.assets.openers,
        "agent/eval.jsonl": manifest.assets.eval,
    }
    entries: dict[str, bytes | Path] = {
        path: read_v2_asset_bytes(workspace, source)
        for path, source in asset_entries.items()
    }
    entries["install-plan.json"] = install_plan
    entries["manifest.json"] = _canonical_json_bytes(
        {
            "agent": {
                "id": manifest.agent_id,
                "name": manifest.name,
                "version": manifest.version,
            },
            "requiredCorpora": ["core-evidence"],
            "runnableWithoutCorpora": False,
            "schemaVersion": WORKSPACE_V2_SCHEMA_VERSION,
            "type": "hagent",
        }
    )
    _write_signed_package_v2(target, entries, private_key)


def _pack_bundle_v2(
    manifest: WorkspaceV2,
    profile_id: str,
    agent_path: Path,
    agent_sha256: str,
    agent_size: int,
    install_plan: Any,
    selected_paths: list[Path],
    target: Path,
    private_key: Ed25519PrivateKey,
) -> None:
    package_by_name = {
        package.file_name: package for package in install_plan.packages
    }
    if len(package_by_name) != len(install_plan.packages):
        raise BuildError("安装计划存在重复子包文件名")
    selected_ids = install_plan.profile(profile_id).package_ids
    if len(selected_paths) != len(selected_ids):
        raise BuildError("bundle 子包与安装计划不一致")
    selected_package_ids = []
    for path in selected_paths:
        declared = package_by_name.get(path.name)
        if declared is None:
            raise BuildError(f"bundle 包含未声明子包：{path.name}")
        selected_package_ids.append(declared.package_id)
    if tuple(selected_package_ids) != tuple(selected_ids):
        raise BuildError("bundle 子包 package ID 序列与安装计划不一致")
    entries: dict[str, bytes | Path] = {
        f"packages/{agent_path.name}": agent_path,
    }
    expected_files = {
        f"packages/{agent_path.name}": (agent_sha256, agent_size),
    }
    for path in selected_paths:
        declared = package_by_name.get(path.name)
        assert declared is not None
        actual_hash, actual_size = _hash_regular_file(path)
        if actual_hash != declared.sha256 or actual_size != declared.size_bytes:
            raise BuildError(f"bundle 子包大小或哈希不匹配：{path.name}")
        entries[f"packages/{path.name}"] = path
        expected_files[f"packages/{path.name}"] = (
            declared.sha256,
            declared.size_bytes,
        )
    entries["bundle-manifest.json"] = _canonical_json_bytes(
        {
            "agent": {
                "fileName": agent_path.name,
                "id": manifest.agent_id,
                "sha256": agent_sha256,
                "sizeBytes": agent_size,
                "version": manifest.version,
            },
            "profile": profile_id,
            "schemaVersion": WORKSPACE_V2_SCHEMA_VERSION,
            "selectedPackageIds": list(selected_ids),
            "type": "hbundle",
        }
    )
    _write_signed_package_v2(
        target,
        entries,
        private_key,
        expected_files=expected_files,
    )


def _write_signed_package_v2(
    target: Path,
    files: dict[str, bytes | Path],
    private_key: Ed25519PrivateKey,
    *,
    expected_files: dict[str, tuple[str, int]] | None = None,
) -> Path:
    normalized: dict[str, bytes | Path] = {}
    for raw_path, payload in files.items():
        path = _safe_package_path_v2(raw_path)
        if path in normalized or path in {"checksums.json", "signature.json"}:
            raise BuildError(f"重复或保留的包内路径：{path}")
        normalized[path] = payload
    expected = {
        _safe_package_path_v2(path): value
        for path, value in (expected_files or {}).items()
    }
    if any(path not in normalized or isinstance(normalized[path], bytes) for path in expected):
        raise BuildError("预期哈希只能绑定已声明的文件条目")
    checksums: dict[str, str] = {}
    expected_identities: dict[str, os.stat_result] = {}
    for path, payload in sorted(normalized.items()):
        if isinstance(payload, bytes):
            checksums[path] = hashlib.sha256(payload).hexdigest()
        else:
            digest, size, identity = _hash_regular_file(payload, return_identity=True)
            if path in expected and expected[path] != (digest, size):
                raise BuildError(f"文件与声明大小或哈希不匹配：{path}")
            checksums[path] = digest
            expected_identities[path] = identity
    checksums_bytes = _canonical_json_bytes({"files": checksums})
    public_key = private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    signature_bytes = _canonical_json_bytes(
        {
            "algorithm": "Ed25519",
            "publicKey": base64.b64encode(public_key).decode("ascii"),
            "signature": base64.b64encode(
                private_key.sign(checksums_bytes)
            ).decode("ascii"),
            "signedFile": "checksums.json",
        }
    )
    package_files: dict[str, bytes | Path] = {
        **normalized,
        "checksums.json": checksums_bytes,
        "signature.json": signature_bytes,
    }
    try:
        with zipfile.ZipFile(
            target,
            "x",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
            allowZip64=True,
        ) as archive:
            for path, payload in sorted(package_files.items()):
                if isinstance(payload, bytes):
                    _write_bytes_into_zip(archive, path, payload)
                else:
                    _stream_file_into_zip(
                        archive,
                        path,
                        payload,
                        expected_identities.get(path),
                    )
    except BaseException:
        target.unlink(missing_ok=True)
        raise
    return target


def _write_bytes_into_zip(
    archive: zipfile.ZipFile,
    path: str,
    payload: bytes,
) -> None:
    info = _zip_info(path)
    archive.writestr(
        info,
        payload,
        compress_type=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    )


def _stream_file_into_zip(
    archive: zipfile.ZipFile,
    path: str,
    source_path: Path,
    expected_identity: os.stat_result | None = None,
) -> None:
    source_path = Path(source_path)
    descriptor = _open_regular_nofollow(source_path)
    try:
        before = os.fstat(descriptor)
        if expected_identity is not None and not _same_file_identity(
            expected_identity, before
        ):
            raise BuildError(f"文件在写入 ZIP 前发生变化：{source_path}")
        with archive.open(_zip_info(path), "w", force_zip64=True) as target:
            with os.fdopen(os.dup(descriptor), "rb") as source:
                while block := source.read(SOURCE_HASH_BUFFER_SIZE):
                    target.write(block)
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise BuildError(f"文件在写入 ZIP 期间发生变化：{source_path}")
    finally:
        os.close(descriptor)


def _zip_info(path: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(path, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    info.create_system = 3
    return info


def _hash_regular_file(
    path: Path,
    *,
    return_identity: bool = False,
) -> tuple[Any, ...]:
    descriptor = _open_regular_nofollow(Path(path))
    try:
        before = os.fstat(descriptor)
        digest = hashlib.sha256()
        size = 0
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            while block := stream.read(SOURCE_HASH_BUFFER_SIZE):
                digest.update(block)
                size += len(block)
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise BuildError(f"文件在哈希期间发生变化：{path}")
        if return_identity:
            return digest.hexdigest(), size, after
        return digest.hexdigest(), size
    finally:
        os.close(descriptor)


def _open_regular_nofollow(path: Path) -> int:
    try:
        expected = path.lstat()
    except OSError as error:
        raise BuildError(f"文件无法读取：{path}：{error}") from error
    if stat.S_ISLNK(expected.st_mode) or not stat.S_ISREG(expected.st_mode):
        raise BuildError(f"文件必须是普通文件：{path}")
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as error:
        raise BuildError(f"文件无法安全打开：{path}：{error}") from error
    actual = os.fstat(descriptor)
    if not _same_file_identity(expected, actual):
        os.close(descriptor)
        raise BuildError(f"文件在打开期间发生变化：{path}")
    return descriptor


def _safe_package_path_v2(value: str) -> str:
    if not isinstance(value, str) or not value or "\x00" in value or "\\" in value:
        raise BuildError(f"不安全的包内路径：{value}")
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
        or ":" in path.parts[0]
    ):
        raise BuildError(f"不安全的包内路径：{value}")
    normalized = str(path)
    if normalized != value:
        raise BuildError(f"未规范化的包内路径：{value}")
    return normalized


def _write_jsonl_stream(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(
                json.dumps(
                    row,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
            )
            stream.write("\n")


def _read_small_json(path: Path) -> dict[str, Any]:
    descriptor = _open_regular_nofollow(path)
    try:
        before = os.fstat(descriptor)
        if before.st_size > 4 * 1024 * 1024:
            raise BuildError(f"报告文件过大：{path}")
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            value = json.load(stream)
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise BuildError(f"报告文件在读取期间发生变化：{path}")
    finally:
        os.close(descriptor)
    if not isinstance(value, dict):
        raise BuildError(f"报告必须是 JSON 对象：{path}")
    return value


def _write_signed_package(
    target: Path,
    files: dict[str, bytes],
    private_key: Ed25519PrivateKey,
) -> Path:
    normalized = {_safe_relative_path(path): payload for path, payload in files.items()}
    checksums = {
        "files": {
            path: hashlib.sha256(payload).hexdigest()
            for path, payload in sorted(normalized.items())
        }
    }
    checksums_bytes = _canonical_json_bytes(checksums)
    public_key = private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    signature = {
        "algorithm": "Ed25519",
        "publicKey": base64.b64encode(public_key).decode("ascii"),
        "signature": base64.b64encode(private_key.sign(checksums_bytes)).decode("ascii"),
        "signedFile": "checksums.json",
    }
    package_files = {
        **normalized,
        "checksums.json": checksums_bytes,
        "signature.json": _canonical_json_bytes(signature),
    }
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(package_files):
            info = zipfile.ZipInfo(path, ZIP_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            info.create_system = 3
            archive.writestr(info, package_files[path], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return target


def _load_private_key(path: Path) -> Ed25519PrivateKey:
    try:
        key = load_pem_private_key(path.read_bytes(), password=None)
    except (OSError, ValueError, TypeError) as error:
        raise BuildError(f"发布者私钥无法读取：{path}：{error}") from error
    if not isinstance(key, Ed25519PrivateKey):
        raise BuildError("发布者私钥必须是 Ed25519")
    return key


def _rank_chunks(query: str, chunks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    terms = set(_query_terms(query))

    def score(row: dict[str, Any]) -> tuple[int, int, str]:
        searchable = set(row.get("keywords", [])) | set(row.get("ngrams", []))
        text = str(row.get("text", ""))
        term_score = sum(3 for term in terms if term in searchable)
        text_score = sum(1 for term in terms if term in text)
        return term_score + text_score, -len(text), str(row.get("id", ""))

    return sorted(chunks, key=score, reverse=True)


def _query_terms(text: str) -> list[str]:
    latin = re.findall(r"[A-Za-z0-9_]{2,}", text.lower())
    return list(dict.fromkeys(latin + _chinese_ngrams(text)))


def _keywords(text: str, ngrams: list[str]) -> list[str]:
    latin = re.findall(r"[A-Za-z0-9_]{2,}", text.lower())
    common_ngrams = [item for item, _ in Counter(ngrams).most_common(64)]
    return list(dict.fromkeys(latin + common_ngrams))


def _chinese_ngrams(text: str) -> list[str]:
    runs = re.findall(r"[\u3400-\u9fff]+", text)
    result: list[str] = []
    for run in runs:
        if len(run) == 1:
            result.append(run)
        else:
            result.extend(run[index : index + 2] for index in range(len(run) - 1))
    return list(dict.fromkeys(result))


def _chunk_text(text: str) -> list[str]:
    paragraphs = [item.strip() for item in re.split(r"\n\s*\n", text) if item.strip()]
    if not paragraphs:
        return []
    chunks: list[str] = []
    buffer = ""
    for paragraph in paragraphs:
        if len(paragraph) > CHUNK_TARGET_CHARS:
            if buffer:
                chunks.append(buffer)
                buffer = ""
            start = 0
            while start < len(paragraph):
                chunks.append(paragraph[start : start + CHUNK_TARGET_CHARS].strip())
                start += CHUNK_TARGET_CHARS - CHUNK_OVERLAP_CHARS
            continue
        candidate = paragraph if not buffer else f"{buffer}\n\n{paragraph}"
        if len(candidate) <= CHUNK_TARGET_CHARS:
            buffer = candidate
        else:
            chunks.append(buffer)
            overlap = buffer[-CHUNK_OVERLAP_CHARS:].strip()
            buffer = f"{overlap}\n\n{paragraph}" if overlap else paragraph
    if buffer:
        chunks.append(buffer)
    return chunks


def _safe_filename(value: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip(".-")
    return normalized or "asset"


def _v2_default_source_id(document: Any) -> str:
    return (
        f"source-{document.source_hash[:V2_SOURCE_HASH_PREFIX_LENGTH]}-"
        f"{_v2_file_name_hash(document.source_path.name)[:V2_FILE_NAME_HASH_PREFIX_LENGTH]}"
    )


def _v2_stored_name(document: Any) -> str:
    suffix = document.source_path.suffix.lower()
    prefix = (
        f"{document.source_hash[:V2_SOURCE_HASH_PREFIX_LENGTH]}-"
        f"{_v2_file_name_hash(document.source_path.name)[:V2_FILE_NAME_HASH_PREFIX_LENGTH]}-"
    )
    available_stem_bytes = V2_STORED_NAME_MAX_BYTES - len(prefix.encode("utf-8")) - len(suffix.encode("utf-8"))
    if available_stem_bytes < 1:
        raise BuildError("来源文件扩展名过长，无法生成可移植 storedName")
    stem = _safe_filename(document.source_path.stem)[:available_stem_bytes]
    return f"{prefix}{stem}{suffix}"


def _v2_file_name_hash(file_name: str) -> str:
    return hashlib.sha256(file_name.encode("utf-8")).hexdigest()


def _safe_relative_path(value: str) -> str:
    path = PurePosixPath(str(value))
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise BuildError(f"不安全的包内路径：{value}")
    return str(path)


def _write_json(path: Path, value: Any):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(_canonical_json_bytes(value))


def _write_jsonl(path: Path, rows: Iterable[dict[str, Any]]):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n" for row in rows),
        encoding="utf-8",
    )


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text("utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text("utf-8").splitlines() if line.strip()]


def _read_jsonl_safe(path: Path, errors: list[str], label: str) -> list[dict[str, Any]]:
    try:
        return _read_jsonl(path)
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"{label} 无法读取：{error}")
        return []


def _canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _write_report(workspace: Path, report: BuildReport):
    workspace.mkdir(parents=True, exist_ok=True)
    _write_json(workspace / "build-report.json", report.to_dict())
