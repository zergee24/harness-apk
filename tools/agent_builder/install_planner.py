from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import stat
import tempfile
from contextlib import AbstractContextManager
from pathlib import Path
from typing import Any, Iterable

from .builder import load_workspace_v2
from .evaluation import read_v2_asset_bytes
from .models import (
    BuildError,
    CorpusShard,
    InstallPackage,
    InstallPlan,
    InstallProfile,
)
from .schema_v2 import WorkspaceV2


MAX_JSONL_LINE_BYTES = 16 * 1024 * 1024
SMALL_FIXTURE_ID_LIMIT = 4096


def plan_corpus_shards(workspace: Path) -> list[CorpusShard]:
    with CorpusPlanIndex(workspace) as index:
        return index.shards(materialize_ids=index.chunk_count <= SMALL_FIXTURE_ID_LIMIT)


def choose_install_profiles(shards: Iterable[CorpusShard]) -> InstallPlan:
    values = tuple(sorted(shards, key=lambda shard: shard.package_id))
    if not values:
        raise BuildError("安装计划至少需要一个语料分片")
    ids = [shard.package_id for shard in values]
    names = [shard.file_name for shard in values if shard.file_name]
    if len(ids) != len(set(ids)):
        raise BuildError("安装计划存在重复 package ID")
    if len(names) != len(set(names)):
        raise BuildError("安装计划存在重复 fileName")

    corpora = tuple(shard for shard in values if shard.package_type == "hcorpus")
    sources = tuple(shard for shard in values if shard.package_type == "hsource")
    required = tuple(
        sorted(shard.package_id for shard in corpora if shard.install_class == "required")
    )
    if required != ("core-evidence",):
        raise BuildError("安装计划必须且只能包含 core-evidence required corpus")

    by_id = {shard.package_id: shard for shard in values}
    covered: set[str] = set()
    for package_id in required:
        covered.update(by_id[package_id].coverage)
    selected: list[str] = list(required)
    remaining = [
        shard for shard in corpora
        if shard.package_id not in required
    ]
    while True:
        ranked = sorted(
            (
                (_coverage_score(shard.coverage - covered), shard.package_id, shard)
                for shard in remaining
            ),
            key=lambda item: (-item[0], item[1]),
        )
        if not ranked or ranked[0][0] <= 0:
            break
        _, _, chosen = ranked[0]
        selected.append(chosen.package_id)
        covered.update(chosen.coverage)
        remaining.remove(chosen)

    complete = tuple(sorted(shard.package_id for shard in corpora))
    source = (*complete, *(shard.package_id for shard in sources))
    profiles = (
        InstallProfile("lite", required),
        InstallProfile("balanced", tuple(selected), recommended=True),
        InstallProfile("complete", complete),
        InstallProfile("source", tuple(source)),
    )
    selected_set = set(selected)
    packages = tuple(
        InstallPackage(
            package_id=shard.package_id,
            package_type=shard.package_type,
            file_name=shard.file_name,
            install_class=(
                shard.install_class
                if shard.install_class in {"required", "source"}
                else ("recommended" if shard.package_id in selected_set else "optional")
            ),
            dependencies=shard.dependencies,
            size_bytes=shard.size_bytes,
            sha256=shard.sha256,
        )
        for shard in values
    )
    plan = InstallPlan(
        packages=packages,
        profiles=profiles,
        required_corpus_ids=required,
    )
    plan.to_dict(require_artifacts=False)
    return plan


def _coverage_score(features: set[str] | frozenset[str]) -> int:
    weights = {
        "dialogue": 100,
        "relationship": 80,
        "period": 60,
        "eval": 50,
        "identity": 40,
        "stance": 40,
        "voice": 40,
        "episode": 40,
        "genre": 20,
        "authorship": 10,
    }
    return sum(weights.get(feature.split(":", 1)[0], 1) for feature in features)


class CorpusPlanIndex(AbstractContextManager["CorpusPlanIndex"]):
    def __init__(self, workspace: Path):
        self.workspace = Path(workspace).expanduser().resolve()
        self.manifest: WorkspaceV2 = load_workspace_v2(self.workspace)
        self._temp_dir: tempfile.TemporaryDirectory[str] | None = None
        self.connection: sqlite3.Connection | None = None
        self.chunk_count = 0

    def __enter__(self) -> "CorpusPlanIndex":
        try:
            self._temp_dir = tempfile.TemporaryDirectory(prefix=".harness-install-plan-")
            self.connection = sqlite3.connect(Path(self._temp_dir.name) / "plan.sqlite3")
            self.connection.row_factory = sqlite3.Row
            self._create_schema()
            self._load_nodes()
            self._load_chunks()
            self._load_duplicates()
            self._load_references()
            self._validate_references()
            self.connection.commit()
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        if self.connection is not None:
            self.connection.close()
            self.connection = None
        if self._temp_dir is not None:
            self._temp_dir.cleanup()
            self._temp_dir = None

    def shards(self, *, materialize_ids: bool = False) -> list[CorpusShard]:
        connection = self._connection()
        core_chunks = tuple(
            row["id"]
            for row in connection.execute("SELECT chunk_id AS id FROM core_refs ORDER BY chunk_id")
        ) if materialize_ids else ()
        core_nodes = tuple(
            row["node_id"]
            for row in connection.execute(
                """
                SELECT DISTINCT chunk_nodes.node_id
                FROM chunk_nodes JOIN core_refs ON core_refs.chunk_id = chunk_nodes.chunk_id
                ORDER BY chunk_nodes.node_id
                """
            )
        ) if materialize_ids else ()
        core_sources = tuple(
            row["source_id"]
            for row in connection.execute(
                """
                SELECT DISTINCT chunk_sources.source_id
                FROM chunk_sources
                JOIN core_refs ON core_refs.chunk_id = chunk_sources.chunk_id
                ORDER BY chunk_sources.source_id
                """
            )
        )
        core_hashes = tuple(
            self._source(source_id)["sourceHash"] for source_id in core_sources
        )
        shards = [
            CorpusShard(
                package_id="core-evidence",
                package_type="hcorpus",
                title="核心证据",
                install_class="required",
                source_ids=core_sources,
                source_hashes=core_hashes,
                chunk_ids=core_chunks,
                node_ids=core_nodes,
                coverage=self.coverage_for("core", ""),
                file_name="core-evidence.hcorpus",
            )
        ]
        selectors = connection.execute(
            """
            SELECT source_id, source_hash, period, top_id, COUNT(*) AS chunk_count
            FROM chunks
            GROUP BY source_id, source_hash, period, top_id
            ORDER BY source_id, period, top_id
            """
        )
        for row in selectors:
            selector = self.selector(row["source_id"], row["period"], row["top_id"])
            shard_id = _shard_id(row["source_id"], row["period"], row["top_id"])
            chunk_ids = tuple(
                item["id"]
                for item in connection.execute(
                    """
                    SELECT id FROM chunks
                    WHERE source_id = ? AND period = ? AND top_id = ?
                    ORDER BY id
                    """,
                    (row["source_id"], row["period"], row["top_id"]),
                )
            ) if materialize_ids else ()
            node_ids = tuple(
                item["node_id"]
                for item in connection.execute(
                    """
                    SELECT DISTINCT chunk_nodes.node_id
                    FROM chunk_nodes
                    JOIN chunks ON chunks.id = chunk_nodes.chunk_id
                    WHERE chunks.source_id = ? AND chunks.period = ? AND chunks.top_id = ?
                    ORDER BY chunk_nodes.node_id
                    """,
                    (row["source_id"], row["period"], row["top_id"]),
                )
            ) if materialize_ids else ()
            shards.append(
                CorpusShard(
                    package_id=shard_id,
                    package_type="hcorpus",
                    title=f"{self._source(row['source_id'])['title']} / {row['period']}",
                    install_class="optional",
                    source_ids=(row["source_id"],),
                    source_hashes=(row["source_hash"],),
                    periods=(row["period"],),
                    top_level_ids=(row["top_id"],),
                    chunk_ids=chunk_ids,
                    node_ids=node_ids,
                    coverage=self.coverage_for("selector", selector),
                    file_name=f"{shard_id}.hcorpus",
                )
            )
        return shards

    def selector(self, source_id: str, period: str, top_id: str) -> str:
        return "\u001f".join((source_id, period, top_id))

    def iter_sources(self, shard: CorpusShard):
        query, params = self._selection_query(shard)
        selected = {
            row["source_id"]
            for row in self._connection().execute(
                f"""
                SELECT DISTINCT chunk_sources.source_id
                FROM chunk_sources
                JOIN chunks ON chunks.id = chunk_sources.chunk_id
                {query}
                ORDER BY chunk_sources.source_id
                """,
                params,
            )
        }
        for source in sorted(self.manifest.sources, key=lambda item: item.source_id):
            if source.source_id in selected:
                yield source.to_dict()

    def iter_nodes(self, shard: CorpusShard):
        connection = self._connection()
        query, params = self._selection_query(shard)
        cursor = connection.execute(
            f"""
            SELECT DISTINCT nodes.id, nodes.payload
            FROM nodes
            JOIN chunk_nodes ON chunk_nodes.node_id = nodes.id
            JOIN chunks ON chunks.id = chunk_nodes.chunk_id
            {query}
            ORDER BY nodes.id
            """,
            params,
        )
        for row in cursor:
            yield json.loads(row["payload"])

    def iter_chunks(self, shard: CorpusShard):
        connection = self._connection()
        query, params = self._selection_query(shard)
        for row in connection.execute(
            f"SELECT chunks.payload FROM chunks {query} ORDER BY chunks.id",
            params,
        ):
            yield json.loads(row["payload"])

    def iter_duplicates(self, shard: CorpusShard):
        query, params = self._selection_query(shard)
        for row in self._connection().execute(
            f"""
            SELECT duplicates.payload
            FROM duplicates
            JOIN chunks ON chunks.id = duplicates.physical_id
            {query}
            ORDER BY duplicates.duplicate_id
            """,
            params,
        ):
            yield json.loads(row["payload"])

    def metrics(self, shard: CorpusShard) -> dict[str, int]:
        query, params = self._selection_query(shard)
        row = self._connection().execute(
            f"SELECT COUNT(*) AS count, COALESCE(SUM(LENGTH(text)), 0) AS chars FROM chunks {query}",
            params,
        ).fetchone()
        return {"chunkCount": row["count"], "extractedCharacters": row["chars"]}

    def coverage_for(self, mode: str, selector: str) -> frozenset[str]:
        connection = self._connection()
        if mode == "core":
            rows = connection.execute(
                """
                SELECT DISTINCT coverage.feature
                FROM coverage JOIN core_refs ON core_refs.chunk_id = coverage.chunk_id
                ORDER BY coverage.feature
                """
            )
        else:
            source_id, period, top_id = selector.split("\u001f")
            rows = connection.execute(
                """
                SELECT DISTINCT coverage.feature
                FROM coverage JOIN chunks ON chunks.id = coverage.chunk_id
                WHERE chunks.source_id = ? AND chunks.period = ? AND chunks.top_id = ?
                ORDER BY coverage.feature
                """,
                (source_id, period, top_id),
            )
        return frozenset(row["feature"] for row in rows)

    def _selection_query(self, shard: CorpusShard) -> tuple[str, tuple[str, ...]]:
        if shard.package_id == "core-evidence":
            return (
                "JOIN core_refs ON core_refs.chunk_id = chunks.id",
                (),
            )
        return (
            "WHERE chunks.source_id = ? AND chunks.period = ? AND chunks.top_id = ?",
            (shard.source_ids[0], shard.periods[0], shard.top_level_ids[0]),
        )

    def _create_schema(self) -> None:
        self._connection().executescript(
            """
            CREATE TABLE nodes (
                id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                parent_id TEXT,
                payload TEXT NOT NULL
            );
            CREATE TABLE chunks (
                id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                source_hash TEXT NOT NULL,
                period TEXT NOT NULL,
                genre TEXT NOT NULL,
                authorship TEXT NOT NULL,
                top_id TEXT NOT NULL,
                duplicate_group TEXT NOT NULL,
                text TEXT NOT NULL,
                payload TEXT NOT NULL
            );
            CREATE TABLE chunk_nodes (
                chunk_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                PRIMARY KEY(chunk_id, ordinal)
            );
            CREATE TABLE chunk_sources (
                chunk_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                PRIMARY KEY(chunk_id, source_id)
            );
            CREATE TABLE core_refs (
                chunk_id TEXT PRIMARY KEY
            );
            CREATE TABLE coverage (
                chunk_id TEXT NOT NULL,
                feature TEXT NOT NULL,
                PRIMARY KEY(chunk_id, feature)
            );
            CREATE TABLE duplicates (
                duplicate_id TEXT PRIMARY KEY,
                physical_id TEXT NOT NULL,
                duplicate_source_id TEXT NOT NULL,
                payload TEXT NOT NULL
            );
            """
        )

    def _load_nodes(self) -> None:
        connection = self._connection()
        for row in _iter_jsonl_regular(self.workspace / "corpora" / "index" / "nodes.jsonl"):
            connection.execute(
                "INSERT INTO nodes(id, source_id, parent_id, payload) VALUES (?, ?, ?, ?)",
                (
                    row["id"],
                    row["sourceId"],
                    row.get("parentId"),
                    _canonical_json_text(row),
                ),
            )

    def _load_chunks(self) -> None:
        connection = self._connection()
        source_hashes = {
            source.source_id: source.source_hash for source in self.manifest.sources
        }
        for row in _iter_jsonl_regular(self.workspace / "corpora" / "index" / "chunks.jsonl"):
            parents = row.get("parentIds")
            if not isinstance(parents, list) or len(parents) < 2:
                raise BuildError(f"chunk 缺少完整父路径：{row.get('id')}")
            source_id = row.get("sourceId")
            if source_id not in source_hashes or row.get("sourceHash") != source_hashes[source_id]:
                raise BuildError(f"chunk 来源不匹配：{row.get('id')}")
            connection.execute(
                """
                INSERT INTO chunks(
                    id, source_id, source_hash, period, genre, authorship,
                    top_id, duplicate_group, text, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    row["id"],
                    source_id,
                    row["sourceHash"],
                    row["period"],
                    row["genre"],
                    row["authorship"],
                    parents[1],
                    row.get("duplicateGroup", ""),
                    row.get("text", ""),
                    _canonical_json_text(row),
                ),
            )
            aliases = row.get("sourceAliases")
            if (
                not isinstance(aliases, list)
                or any(not isinstance(item, str) or item not in source_hashes for item in aliases)
            ):
                raise BuildError(f"chunk sourceAliases 无效：{row.get('id')}")
            for alias_source_id in sorted(set((source_id, *aliases))):
                connection.execute(
                    "INSERT INTO chunk_sources(chunk_id, source_id) VALUES (?, ?)",
                    (row["id"], alias_source_id),
                )
            for ordinal, node_id in enumerate(parents):
                connection.execute(
                    "INSERT INTO chunk_nodes(chunk_id, node_id, ordinal) VALUES (?, ?, ?)",
                    (row["id"], node_id, ordinal),
                )
            self._add_coverage(row["id"], f"period:{row['period']}")
            self._add_coverage(row["id"], f"genre:{row['genre']}")
            self._add_coverage(row["id"], f"authorship:{row['authorship']}")
            if (
                row["genre"] in {"conversation", "interview"}
                and row["authorship"] in {"direct", "edited_direct"}
            ):
                self._add_coverage(row["id"], f"dialogue:{row['id']}")
            self.chunk_count += 1

    def _load_duplicates(self) -> None:
        connection = self._connection()
        known_sources = {source.source_id for source in self.manifest.sources}
        for row in _iter_jsonl_regular(
            self.workspace / "corpora" / "index" / "duplicates.jsonl"
        ):
            duplicate_id = row.get("duplicateChunkId")
            physical_id = row.get("physicalChunkId")
            duplicate_source_id = row.get("duplicateSourceId")
            if (
                not isinstance(duplicate_id, str)
                or not duplicate_id
                or not isinstance(physical_id, str)
                or connection.execute(
                    "SELECT 1 FROM chunks WHERE id = ?",
                    (physical_id,),
                ).fetchone() is None
                or duplicate_source_id not in known_sources
            ):
                raise BuildError(f"duplicates.jsonl 引用无效：{duplicate_id}")
            connection.execute(
                """
                INSERT INTO duplicates(
                    duplicate_id, physical_id, duplicate_source_id, payload
                ) VALUES (?, ?, ?, ?)
                """,
                (
                    duplicate_id,
                    physical_id,
                    duplicate_source_id,
                    _canonical_json_text(row),
                ),
            )

    def _load_references(self) -> None:
        assets = self.manifest.assets
        identity = _json_asset(self.workspace, assets.identity)
        voice = _json_asset(self.workspace, assets.voice)
        worldview = _jsonl_asset(self.workspace, assets.worldview)
        episodes = _jsonl_asset(self.workspace, assets.episodes)
        concepts = _json_asset(self.workspace, assets.concepts)
        examples = _jsonl_asset(self.workspace, assets.examples)
        evaluations = _jsonl_asset(self.workspace, assets.eval)

        relationships = identity.get("relationships", []) if isinstance(identity, dict) else []
        for row in relationships if isinstance(relationships, list) else []:
            self._reference("identity", row)
            self._reference("relationship", row)
        self._reference("voice", voice)
        for row in worldview:
            self._reference("stance", row)
        for row in episodes:
            self._reference("episode", row)
        concept_rows = concepts.get("concepts", []) if isinstance(concepts, dict) else []
        for row in concept_rows if isinstance(concept_rows, list) else []:
            self._reference("identity", row)
        for row in examples:
            self._reference("voice", row)
        for row in evaluations:
            category = row.get("category", "unassigned")
            self._reference(f"eval:{category}", row, field="expectedEvidence")

    def _reference(self, feature: str, row: Any, *, field: str = "evidence") -> None:
        if not isinstance(row, dict) or not isinstance(row.get(field), list):
            return
        for chunk_id in row[field]:
            if not isinstance(chunk_id, str):
                continue
            self._connection().execute(
                "INSERT OR IGNORE INTO core_refs(chunk_id) VALUES (?)",
                (chunk_id,),
            )
            self._add_coverage(chunk_id, f"{feature}:{chunk_id}")

    def _add_coverage(self, chunk_id: str, feature: str) -> None:
        self._connection().execute(
            "INSERT OR IGNORE INTO coverage(chunk_id, feature) VALUES (?, ?)",
            (chunk_id, feature),
        )

    def _validate_references(self) -> None:
        missing = [
            row["chunk_id"]
            for row in self._connection().execute(
                """
                SELECT core_refs.chunk_id
                FROM core_refs LEFT JOIN chunks ON chunks.id = core_refs.chunk_id
                WHERE chunks.id IS NULL
                ORDER BY core_refs.chunk_id
                """
            )
        ]
        if missing:
            raise BuildError("人物资产引用了不存在的证据：" + ", ".join(missing))
        missing_nodes = self._connection().execute(
            """
            SELECT chunk_nodes.chunk_id, chunk_nodes.node_id
            FROM chunk_nodes LEFT JOIN nodes ON nodes.id = chunk_nodes.node_id
            WHERE nodes.id IS NULL
            ORDER BY chunk_nodes.chunk_id, chunk_nodes.ordinal
            LIMIT 1
            """
        ).fetchone()
        if missing_nodes:
            raise BuildError(
                f"chunk 父节点不存在：{missing_nodes['chunk_id']} -> {missing_nodes['node_id']}"
            )

    def _source(self, source_id: str) -> dict[str, Any]:
        for source in self.manifest.sources:
            if source.source_id == source_id:
                return source.to_dict()
        raise BuildError(f"未知来源：{source_id}")

    def _connection(self) -> sqlite3.Connection:
        if self.connection is None:
            raise BuildError("语料规划索引尚未打开")
        return self.connection


def _shard_id(source_id: str, period: str, top_id: str) -> str:
    suffix = hashlib.sha256(
        f"{source_id}\n{period}\n{top_id}".encode("utf-8")
    ).hexdigest()[:12]
    safe_source = "".join(
        character if character.isalnum() or character in "._-" else "-"
        for character in source_id
    ).strip(".-") or "source"
    return f"corpus-{safe_source}-{suffix}"


def _iter_jsonl_regular(path: Path):
    try:
        expected = path.lstat()
    except OSError as error:
        raise BuildError(f"索引文件无法读取：{path.name}：{error}") from error
    if stat.S_ISLNK(expected.st_mode) or not stat.S_ISREG(expected.st_mode):
        raise BuildError(f"索引文件必须是普通文件：{path.name}")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        before = os.fstat(descriptor)
        if not _same_file_identity(expected, before):
            raise BuildError(f"索引文件在读取前发生变化：{path.name}")
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            for line_number, raw_line in enumerate(stream, start=1):
                if len(raw_line) > MAX_JSONL_LINE_BYTES:
                    raise BuildError(f"{path.name} 第 {line_number} 行过大")
                if not raw_line.strip():
                    continue
                try:
                    value = json.loads(raw_line.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
                    raise BuildError(
                        f"{path.name} 第 {line_number} 行无法读取：{error}"
                    ) from error
                if not isinstance(value, dict):
                    raise BuildError(f"{path.name} 第 {line_number} 行必须是对象")
                yield value
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise BuildError(f"索引文件在读取期间发生变化：{path.name}")
    finally:
        os.close(descriptor)


def _json_asset(workspace: Path, asset_path: str) -> dict[str, Any]:
    try:
        value = json.loads(read_v2_asset_bytes(workspace, asset_path))
    except (OSError, ValueError, json.JSONDecodeError, RecursionError) as error:
        raise BuildError(f"人物资产无法读取：{asset_path}：{error}") from error
    if not isinstance(value, dict):
        raise BuildError(f"人物资产必须是对象：{asset_path}")
    return value


def _jsonl_asset(workspace: Path, asset_path: str) -> list[dict[str, Any]]:
    payload = read_v2_asset_bytes(workspace, asset_path)
    rows = []
    for line_number, raw_line in enumerate(payload.splitlines(), start=1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line)
        except (json.JSONDecodeError, RecursionError) as error:
            raise BuildError(
                f"人物资产 JSONL 无法读取：{asset_path} 第 {line_number} 行：{error}"
            ) from error
        if not isinstance(value, dict):
            raise BuildError(f"人物资产 JSONL 必须是对象：{asset_path} 第 {line_number} 行")
        rows.append(value)
    return rows


def _canonical_json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


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
