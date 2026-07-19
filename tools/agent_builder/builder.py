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
from .evaluation import evaluate_workspace
from .extractors import extract_document, iter_v2_source_sections_stream
from .models import BuildError, BuildReport, ExtractedDocument, PackResult
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
    semantic_assets = _validate_v2_asset_files(workspace, manifest.assets, errors)
    if semantic_assets is not None and _v2_semantic_assets_are_empty(*semantic_assets):
        errors.append("人物语义资产尚未完成")
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


def _validate_v2_asset_files(
    workspace: Path,
    assets: AgentAssetPaths,
    errors: list[str],
) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]]] | None:
    agent_root = _v2_agent_root(workspace, errors)
    if agent_root is None:
        return None

    json_asset_names = {"identity", "voice", "concepts", "openers"}
    jsonl_asset_names = {"worldview", "episodes", "examples", "eval"}
    json_assets: dict[str, Any] = {}
    jsonl_assets: dict[str, list[dict[str, Any]]] = {}
    valid = True
    for name, path in assets.to_dict().items():
        try:
            content = _read_v2_asset_text(agent_root, path)
        except (BuildError, OSError, UnicodeDecodeError) as error:
            errors.append(f"人物资产无法读取：{path}：{error}")
            valid = False
            continue

        if name == "persona":
            if not content.strip():
                errors.append(f"人物资产不能为空：{path}")
                valid = False
        elif name in json_asset_names:
            try:
                value = json.loads(content)
            except (json.JSONDecodeError, RecursionError) as error:
                errors.append(f"人物资产无法读取：{path}：{error}")
                valid = False
                continue
            if not isinstance(value, dict):
                errors.append(f"人物资产必须是 JSON 对象：{path}")
                valid = False
                continue
            json_assets[name] = value
        elif name in jsonl_asset_names:
            try:
                rows = [json.loads(line) for line in content.splitlines() if line.strip()]
            except (json.JSONDecodeError, RecursionError) as error:
                errors.append(f"人物资产无法读取：{path}：{error}")
                valid = False
                continue
            if any(not isinstance(row, dict) for row in rows):
                errors.append(f"人物资产中的每一行必须是 JSON 对象：{path}")
                valid = False
                continue
            jsonl_assets[name] = rows
    if not valid:
        return None
    return json_assets, jsonl_assets


def _v2_agent_root(workspace: Path, errors: list[str]) -> Path | None:
    agent_root = workspace / "agent"
    try:
        mode = agent_root.lstat().st_mode
    except OSError as error:
        errors.append(f"人物资产目录无法读取：agent：{error}")
        return None
    if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
        errors.append("人物资产目录缺失、不安全或不是目录：agent")
        return None
    try:
        resolved_agent_root = agent_root.resolve(strict=True)
    except OSError as error:
        errors.append(f"人物资产目录无法读取：agent：{error}")
        return None
    if not resolved_agent_root.is_relative_to(workspace):
        errors.append("人物资产目录不在工作区内：agent")
        return None
    return resolved_agent_root


def _read_v2_asset_text(agent_root: Path, asset_path: str) -> str:
    path = PurePosixPath(asset_path)
    if len(path.parts) < 2 or path.parts[0] != "agent":
        raise BuildError("人物资产必须位于 agent 目录内")

    current = agent_root
    for part in path.parts[1:-1]:
        current /= part
        mode = current.lstat().st_mode
        if stat.S_ISLNK(mode):
            raise BuildError("人物资产父目录不能是符号链接")
        if not stat.S_ISDIR(mode):
            raise BuildError("人物资产父路径必须是目录")

    leaf = current / path.parts[-1]
    mode = leaf.lstat().st_mode
    if stat.S_ISLNK(mode):
        raise BuildError("人物资产不能是符号链接")
    if not stat.S_ISREG(mode):
        raise BuildError("人物资产必须是普通文件")
    resolved_leaf = leaf.resolve(strict=True)
    if not resolved_leaf.is_relative_to(agent_root):
        raise BuildError("人物资产不在 agent 目录内")
    return leaf.read_text("utf-8")


def _v2_semantic_assets_are_empty(
    json_assets: dict[str, Any],
    jsonl_assets: dict[str, list[dict[str, Any]]],
) -> bool:
    if any(jsonl_assets.values()):
        return False
    return not any(_json_has_content(value) for value in json_assets.values())


def _json_has_content(value: Any) -> bool:
    pending = [value]
    while pending:
        current = pending.pop()
        if isinstance(current, dict):
            pending.extend(current.values())
        elif isinstance(current, list):
            pending.extend(current)
        elif current:
            return True
    return False


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
