"""Human-reviewed retrieval evaluation for the two launch history Wikis."""

from __future__ import annotations

import json
import re
import sqlite3
from collections import Counter, defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.evaluation import (
    RetrievalCase,
    RetrievalReport,
    evaluate_retrieval,
    retrieve_chunks,
)
from tools.wiki_builder.models import BuildError
from tools.wiki_builder.normalization import normalize_for_search
from tools.wiki_builder.workspace import WikiWorkspace, load_workspace

EVALUATION_SCHEMA_VERSION = 1
EVALUATION_TYPE = "history-retrieval-evaluation"
EVALUATION_INDEX = "evaluation.json"
EVALUATION_CASES = "cases.jsonl"

SINGLE_CATEGORIES = (
    "original-keyword",
    "modern-paraphrase",
    "alias-title-place",
    "time-expression",
    "homonym-disambiguation",
    "multi-volume-synthesis",
    "known-evidence-gap",
    "no-result",
)
SINGLE_MINIMUM_PER_CATEGORY = 20
PAIR_CATEGORY_MINIMUMS: Mapping[str, int] = {
    "mutual-corroboration": 20,
    "differing-account": 20,
    "one-sided-gap": 10,
    "no-result": 10,
}
SINGLE_OVERALL_RECALL_GATE = 0.90
SINGLE_CATEGORY_RECALL_GATE = 0.85
PAIR_GOLD_COVERAGE_GATE = 0.90
PAIR_FINAL_LIMIT = 12

_SINGLE_RESULTS = {
    "original-keyword": "evidence",
    "modern-paraphrase": "evidence",
    "alias-title-place": "evidence",
    "time-expression": "evidence",
    "homonym-disambiguation": "evidence",
    "multi-volume-synthesis": "evidence",
    "known-evidence-gap": "no-result",
    "no-result": "no-result",
}
_PAIR_RESULTS = {
    "mutual-corroboration": "both",
    "differing-account": "both",
    "one-sided-gap": "one-sided",
    "no-result": "no-result",
}
_CASE_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]*\Z")


@dataclass(frozen=True)
class EvaluationWorkspace:
    path: Path
    wiki_id: str
    wiki_version: int
    database_path: Path


@dataclass(frozen=True)
class GoldEvidence:
    wiki_id: str
    wiki_version: int
    document_id: str
    section_id: str
    chunk_id: str
    quote: str
    locator: dict[str, object]


@dataclass(frozen=True)
class HistoryEvaluationCase:
    case_id: str
    scope: str
    category: str
    query: str
    expected_result: str
    gold_evidence: tuple[GoldEvidence, ...]
    reviewed: bool
    reviewer_notes: str

    def as_retrieval_case(self) -> RetrievalCase:
        return RetrievalCase(
            case_id=self.case_id,
            category=self.category,
            query=self.query,
            expected_chunk_ids=frozenset(
                evidence.chunk_id for evidence in self.gold_evidence
            ),
        )


@dataclass(frozen=True)
class HistoryEvaluationSet:
    root: Path
    scope: str
    workspaces: tuple[EvaluationWorkspace, ...]
    cases: tuple[HistoryEvaluationCase, ...]

    def evaluate_single(self) -> RetrievalReport:
        if self.scope != "single" or len(self.workspaces) != 1:
            raise BuildError("只有单库评测集可以执行单库检索")
        return evaluate_retrieval(
            self.workspaces[0].database_path,
            tuple(case.as_retrieval_case() for case in self.cases),
        )


@dataclass(frozen=True)
class PairEvaluationReport:
    case_count: int
    gold_coverage_at_12: float
    both_side_case_coverage_at_12: float
    category_coverage: dict[str, float]
    quote_match_rate: float
    citation_validity_rate: float
    failed_case_ids: tuple[str, ...]

    @property
    def publishable(self) -> bool:
        return not pair_gate_failures(self)

    def to_dict(self) -> dict[str, object]:
        return {
            "publishable": self.publishable,
            "caseCount": self.case_count,
            "goldCoverageAt12": self.gold_coverage_at_12,
            "bothSideCaseCoverageAt12": self.both_side_case_coverage_at_12,
            "categoryCoverage": dict(sorted(self.category_coverage.items())),
            "quoteMatchRate": self.quote_match_rate,
            "citationValidityRate": self.citation_validity_rate,
            "failedCaseIds": list(self.failed_case_ids),
            "gateFailures": list(pair_gate_failures(self)),
        }


def create_evaluation_template(
    workspaces: Sequence[Path],
    output: Path,
    *,
    cross_wiki: bool = False,
    minimum_cases: int | None = None,
) -> Path:
    loaded = tuple(load_workspace(path) for path in workspaces)
    expected_count = 2 if cross_wiki else 1
    if len(loaded) != expected_count:
        label = "双库" if cross_wiki else "单库"
        raise BuildError(f"{label}评测模板必须提供 {expected_count} 个工作区")
    if len({workspace.wiki_id for workspace in loaded}) != len(loaded):
        raise BuildError("评测模板不能重复使用同一个 wikiId")

    baseline = (
        sum(PAIR_CATEGORY_MINIMUMS.values())
        if cross_wiki
        else len(SINGLE_CATEGORIES) * SINGLE_MINIMUM_PER_CATEGORY
    )
    requested = baseline if minimum_cases is None else minimum_cases
    if type(requested) is not int or requested < baseline:
        raise BuildError(f"minimum-cases 不得低于批准门槛 {baseline}")

    target = Path(output)
    if target.is_symlink() or target.exists():
        raise BuildError(f"评测目录已存在，拒绝覆盖人工结果：{target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.mkdir()
    try:
        evaluation_workspaces = tuple(_evaluation_workspace(item) for item in loaded)
        rows = (
            _pair_template_rows(evaluation_workspaces, requested)
            if cross_wiki
            else _single_template_rows(evaluation_workspaces[0], requested)
        )
        index = {
            "type": EVALUATION_TYPE,
            "schemaVersion": EVALUATION_SCHEMA_VERSION,
            "scope": "pair" if cross_wiki else "single",
            "cases": EVALUATION_CASES,
            "workspaces": [
                {
                    "path": str(workspace.path),
                    "wikiId": workspace.wiki_id,
                    "wikiVersion": workspace.wiki_version,
                }
                for workspace in evaluation_workspaces
            ],
        }
        (target / EVALUATION_INDEX).write_bytes(canonical_json_bytes(index))
        (target / EVALUATION_CASES).write_bytes(
            b"".join(canonical_json_bytes(row) + b"\n" for row in rows)
        )
    except BaseException:
        for child in sorted(target.iterdir(), reverse=True):
            child.unlink(missing_ok=True)
        target.rmdir()
        raise
    return target


def validate_evaluation_set(path: Path) -> HistoryEvaluationSet:
    root = _evaluation_root(path)
    index = _load_index(root / EVALUATION_INDEX)
    workspaces = _load_index_workspaces(index["workspaces"])
    scope = index["scope"]
    if (scope == "single" and len(workspaces) != 1) or (
        scope == "pair" and len(workspaces) != 2
    ):
        raise BuildError("评测索引的 scope 与工作区数量不一致")

    cases = _load_cases(root / EVALUATION_CASES, scope)
    _validate_case_balance(cases, scope)
    _validate_unique_queries(cases)
    _validate_review_state(cases)
    _validate_gold(cases, workspaces, scope)
    return HistoryEvaluationSet(root, scope, workspaces, cases)


def load_single_retrieval_cases(
    path: Path,
    workspace: Path,
) -> tuple[RetrievalCase, ...]:
    evaluation = validate_evaluation_set(path)
    if evaluation.scope != "single":
        raise BuildError("validate --eval 只接受单库 history 评测集")
    actual = load_workspace(workspace)
    indexed = evaluation.workspaces[0]
    if (
        actual.root.resolve() != indexed.path
        or actual.wiki_id != indexed.wiki_id
        or actual.version != indexed.wiki_version
    ):
        raise BuildError("单库评测集引用了不同的 Wiki 工作区或版本")
    return tuple(case.as_retrieval_case() for case in evaluation.cases)


def evaluate_pair(
    left: Path,
    right: Path,
    evaluation_path: Path,
) -> PairEvaluationReport:
    evaluation = validate_evaluation_set(evaluation_path)
    if evaluation.scope != "pair":
        raise BuildError("evaluate-pair 只接受双库评测集")
    supplied = tuple(load_workspace(path) for path in (left, right))
    expected_identity = tuple(
        (workspace.path, workspace.wiki_id, workspace.wiki_version)
        for workspace in evaluation.workspaces
    )
    supplied_identity = tuple(
        (workspace.root.resolve(), workspace.wiki_id, workspace.version)
        for workspace in supplied
    )
    if supplied_identity != expected_identity:
        raise BuildError("双库评测集与命令提供的 Wiki 顺序或版本不一致")

    scores: list[float] = []
    by_category: dict[str, list[float]] = defaultdict(list)
    both_side_scores: list[float] = []
    failed: set[str] = set()
    left_index, right_index = evaluation.workspaces
    for case in evaluation.cases:
        final = _pair_final_ranking(left_index, right_index, case.query)
        expected = {
            (evidence.wiki_id, evidence.chunk_id) for evidence in case.gold_evidence
        }
        if expected:
            score = len(expected.intersection(final)) / len(expected)
        else:
            score = 1.0 if not final else 0.0
        if case.expected_result == "one-sided":
            expected_wikis = {wiki_id for wiki_id, _chunk_id in expected}
            if any(wiki_id not in expected_wikis for wiki_id, _chunk_id in final):
                score = 0.0
        scores.append(score)
        by_category[case.category].append(score)
        if score < 1.0:
            failed.add(case.case_id)

        if case.expected_result == "both":
            expected_wikis = {evidence.wiki_id for evidence in case.gold_evidence}
            found_wikis = {
                wiki_id
                for wiki_id, chunk_id in final
                if (wiki_id, chunk_id) in expected
            }
            side_score = 1.0 if found_wikis == expected_wikis else 0.0
            both_side_scores.append(side_score)
            if side_score < 1.0:
                failed.add(case.case_id)

    return PairEvaluationReport(
        case_count=len(evaluation.cases),
        gold_coverage_at_12=_average(scores),
        both_side_case_coverage_at_12=_average(both_side_scores),
        category_coverage={
            category: _average(values)
            for category, values in sorted(by_category.items())
        },
        quote_match_rate=1.0,
        citation_validity_rate=1.0,
        failed_case_ids=tuple(sorted(failed)),
    )


def single_gate_failures(report: RetrievalReport) -> tuple[str, ...]:
    failures: list[str] = []
    if report.overall_recall_at_20 < SINGLE_OVERALL_RECALL_GATE:
        failures.append("overall_recall")
    if set(report.category_recall) != set(SINGLE_CATEGORIES) or any(
        value < SINGLE_CATEGORY_RECALL_GATE
        for value in report.category_recall.values()
    ):
        failures.append("category_recall")
    return tuple(failures)


def pair_gate_failures(report: PairEvaluationReport) -> tuple[str, ...]:
    failures: list[str] = []
    if report.gold_coverage_at_12 < PAIR_GOLD_COVERAGE_GATE:
        failures.append("pair_gold_coverage")
    if report.both_side_case_coverage_at_12 < PAIR_GOLD_COVERAGE_GATE:
        failures.append("both_side_coverage")
    if report.quote_match_rate != 1.0:
        failures.append("quote_match")
    if report.citation_validity_rate != 1.0:
        failures.append("citation_validity")
    return tuple(failures)


def _evaluation_workspace(workspace: WikiWorkspace) -> EvaluationWorkspace:
    return EvaluationWorkspace(
        path=workspace.root.resolve(),
        wiki_id=workspace.wiki_id,
        wiki_version=workspace.version,
        database_path=workspace.database_path.resolve(),
    )


def _single_template_rows(
    workspace: EvaluationWorkspace,
    minimum_cases: int,
) -> list[dict[str, object]]:
    counts = _expanded_counts(
        {category: SINGLE_MINIMUM_PER_CATEGORY for category in SINGLE_CATEGORIES},
        minimum_cases,
    )
    candidates = _candidate_evidence(workspace)
    rows: list[dict[str, object]] = []
    cursor = 0
    for category in SINGLE_CATEGORIES:
        for number in range(1, counts[category] + 1):
            expected = _SINGLE_RESULTS[category]
            gold: list[dict[str, object]] = []
            if expected == "evidence":
                item_count = 2 if category == "multi-volume-synthesis" else 1
                gold = [
                    candidates[(cursor + offset) % len(candidates)]
                    for offset in range(item_count)
                ]
                cursor += item_count
            rows.append(
                _template_case(
                    case_id=f"{category}-{number:03d}",
                    scope="single",
                    category=category,
                    expected_result=expected,
                    gold=gold,
                )
            )
    return sorted(rows, key=lambda row: str(row["caseId"]))


def _pair_template_rows(
    workspaces: tuple[EvaluationWorkspace, ...],
    minimum_cases: int,
) -> list[dict[str, object]]:
    counts = _expanded_counts(dict(PAIR_CATEGORY_MINIMUMS), minimum_cases)
    candidates = tuple(_candidate_evidence(workspace) for workspace in workspaces)
    rows: list[dict[str, object]] = []
    for category in PAIR_CATEGORY_MINIMUMS:
        for number in range(1, counts[category] + 1):
            expected = _PAIR_RESULTS[category]
            if expected == "both":
                gold = [
                    items[(number - 1) % len(items)] for items in candidates
                ]
            elif expected == "one-sided":
                gold = [candidates[0][(number - 1) % len(candidates[0])]]
            else:
                gold = []
            rows.append(
                _template_case(
                    case_id=f"{category}-{number:03d}",
                    scope="pair",
                    category=category,
                    expected_result=expected,
                    gold=gold,
                )
            )
    return sorted(rows, key=lambda row: str(row["caseId"]))


def _template_case(
    *,
    case_id: str,
    scope: str,
    category: str,
    expected_result: str,
    gold: list[dict[str, object]],
) -> dict[str, object]:
    return {
        "caseId": case_id,
        "scope": scope,
        "category": category,
        "query": f"待人工改写 {category} {case_id.rsplit('-', 1)[-1]}",
        "expectedResult": expected_result,
        "goldEvidence": gold,
        "reviewed": False,
        "reviewerNotes": "",
    }


def _candidate_evidence(workspace: EvaluationWorkspace) -> list[dict[str, object]]:
    connection = _open_read_only(workspace.database_path)
    try:
        rows = connection.execute(
            """
            SELECT documents.document_id, sections.section_id, chunks.chunk_id,
                   chunks.original_text, chunks.locator_json
            FROM chunks
            JOIN sections USING(section_id)
            JOIN documents USING(document_id)
            ORDER BY documents.ordinal, sections.ordinal, chunks.ordinal
            """
        ).fetchall()
    finally:
        connection.close()
    if not rows:
        raise BuildError(f"Wiki 没有可供评测的原文 chunk：{workspace.wiki_id}")
    return [
        {
            "wikiId": workspace.wiki_id,
            "wikiVersion": workspace.wiki_version,
            "documentId": document_id,
            "sectionId": section_id,
            "chunkId": chunk_id,
            "quote": original_text[:160],
            "locator": json.loads(locator_json),
        }
        for document_id, section_id, chunk_id, original_text, locator_json in rows
    ]


def _expanded_counts(baseline: dict[str, int], total: int) -> dict[str, int]:
    counts = dict(baseline)
    categories = tuple(baseline)
    for offset in range(total - sum(baseline.values())):
        counts[categories[offset % len(categories)]] += 1
    return counts


def _evaluation_root(path: Path) -> Path:
    target = Path(path)
    root = target.parent if target.name == EVALUATION_CASES else target
    if root.is_symlink() or not root.is_dir():
        raise BuildError(f"评测目录不可安全读取：{root}")
    return root.resolve()


def _load_index(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise BuildError(f"缺少安全的评测索引：{path}")
    try:
        raw = path.read_bytes()
        value = json.loads(raw, parse_constant=_reject_json_constant)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise BuildError(f"评测索引无效：{error}") from error
    if raw != canonical_json_bytes(value):
        raise BuildError("evaluation.json 不是规范 JSON")
    expected = {
        "type",
        "schemaVersion",
        "scope",
        "cases",
        "workspaces",
    }
    if not isinstance(value, dict) or set(value) != expected:
        raise BuildError("evaluation.json 字段无效")
    if (
        value["type"] != EVALUATION_TYPE
        or type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != EVALUATION_SCHEMA_VERSION
        or value["scope"] not in {"single", "pair"}
        or value["cases"] != EVALUATION_CASES
        or not isinstance(value["workspaces"], list)
    ):
        raise BuildError("evaluation.json 协议无效")
    return value


def _load_index_workspaces(raw: object) -> tuple[EvaluationWorkspace, ...]:
    if not isinstance(raw, list):
        raise BuildError("evaluation.json workspaces 必须是数组")
    result: list[EvaluationWorkspace] = []
    for number, item in enumerate(raw, start=1):
        if not isinstance(item, dict) or set(item) != {
            "path",
            "wikiId",
            "wikiVersion",
        }:
            raise BuildError(f"evaluation.json 第 {number} 个 workspace 字段无效")
        if not isinstance(item["path"], str) or not Path(item["path"]).is_absolute():
            raise BuildError(f"evaluation.json 第 {number} 个 workspace path 无效")
        loaded = load_workspace(Path(item["path"]))
        if (
            item["wikiId"] != loaded.wiki_id
            or type(item["wikiVersion"]) is not int
            or item["wikiVersion"] != loaded.version
        ):
            raise BuildError("evaluation.json 的 Wiki 身份与当前工作区不一致")
        result.append(_evaluation_workspace(loaded))
    if len({item.wiki_id for item in result}) != len(result):
        raise BuildError("evaluation.json 包含重复 wikiId")
    return tuple(result)


def _load_cases(path: Path, scope: str) -> tuple[HistoryEvaluationCase, ...]:
    if path.is_symlink() or not path.is_file():
        raise BuildError(f"缺少安全的评测 cases：{path}")
    cases: list[HistoryEvaluationCase] = []
    previous: str | None = None
    try:
        with path.open("rb") as stream:
            for line_number, raw in enumerate(stream, start=1):
                try:
                    value = json.loads(raw, parse_constant=_reject_json_constant)
                except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
                    raise BuildError(
                        f"评测第 {line_number} 行 JSON 无效：{error}"
                    ) from error
                if raw != canonical_json_bytes(value) + b"\n":
                    raise BuildError(f"评测第 {line_number} 行不是规范 JSONL")
                case = _parse_case(value, line_number, scope)
                if previous is not None and case.case_id <= previous:
                    label = "重复" if case.case_id == previous else "未按 caseId 排序"
                    raise BuildError(f"评测 caseId {label}：{case.case_id}")
                previous = case.case_id
                cases.append(case)
    except OSError as error:
        raise BuildError(f"评测 cases 无法读取：{error}") from error
    if not cases:
        raise BuildError("评测 cases 不能为空")
    return tuple(cases)


def _parse_case(
    value: object,
    line_number: int,
    scope: str,
) -> HistoryEvaluationCase:
    fields = {
        "caseId",
        "scope",
        "category",
        "query",
        "expectedResult",
        "goldEvidence",
        "reviewed",
        "reviewerNotes",
    }
    if not isinstance(value, dict) or set(value) != fields:
        raise BuildError(f"评测第 {line_number} 行字段无效")
    case_id = _nonempty_string(value["caseId"], "caseId", line_number)
    if not _CASE_ID_PATTERN.fullmatch(case_id):
        raise BuildError(f"评测第 {line_number} 行 caseId 格式无效")
    if value["scope"] != scope:
        raise BuildError(f"评测第 {line_number} 行 scope 与索引不一致")
    category = _nonempty_string(value["category"], "category", line_number)
    allowed = _SINGLE_RESULTS if scope == "single" else _PAIR_RESULTS
    if category not in allowed:
        raise BuildError(f"评测第 {line_number} 行 category 无效：{category}")
    if value["expectedResult"] != allowed[category]:
        raise BuildError(f"评测第 {line_number} 行 expectedResult 与类别不一致")
    query = _nonempty_string(value["query"], "query", line_number)
    if type(value["reviewed"]) is not bool:
        raise BuildError(f"评测第 {line_number} 行 reviewed 必须是布尔值")
    if not isinstance(value["reviewerNotes"], str):
        raise BuildError(f"评测第 {line_number} 行 reviewerNotes 必须是字符串")
    evidence_raw = value["goldEvidence"]
    if not isinstance(evidence_raw, list):
        raise BuildError(f"评测第 {line_number} 行 goldEvidence 必须是数组")
    evidence = tuple(
        _parse_gold(item, line_number, evidence_number)
        for evidence_number, item in enumerate(evidence_raw, start=1)
    )
    identities = [(item.wiki_id, item.chunk_id) for item in evidence]
    if len(identities) != len(set(identities)):
        raise BuildError(f"评测第 {line_number} 行 goldEvidence 重复")
    return HistoryEvaluationCase(
        case_id=case_id,
        scope=scope,
        category=category,
        query=query,
        expected_result=value["expectedResult"],
        gold_evidence=evidence,
        reviewed=value["reviewed"],
        reviewer_notes=value["reviewerNotes"],
    )


def _parse_gold(value: object, line: int, number: int) -> GoldEvidence:
    fields = {
        "wikiId",
        "wikiVersion",
        "documentId",
        "sectionId",
        "chunkId",
        "quote",
        "locator",
    }
    label = f"评测第 {line} 行第 {number} 条 goldEvidence"
    if not isinstance(value, dict) or set(value) != fields:
        raise BuildError(f"{label} 字段无效")
    strings = {
        field: _plain_nonempty_string(value[field], f"{label}.{field}")
        for field in (
            "wikiId",
            "documentId",
            "sectionId",
            "chunkId",
            "quote",
        )
    }
    if type(value["wikiVersion"]) is not int or value["wikiVersion"] <= 0:
        raise BuildError(f"{label}.wikiVersion 无效")
    if not isinstance(value["locator"], dict):
        raise BuildError(f"{label}.locator 必须是对象")
    return GoldEvidence(
        wiki_id=strings["wikiId"],
        wiki_version=value["wikiVersion"],
        document_id=strings["documentId"],
        section_id=strings["sectionId"],
        chunk_id=strings["chunkId"],
        quote=strings["quote"],
        locator=value["locator"],
    )


def _validate_case_balance(
    cases: tuple[HistoryEvaluationCase, ...],
    scope: str,
) -> None:
    counts = Counter(case.category for case in cases)
    minimums = (
        {category: SINGLE_MINIMUM_PER_CATEGORY for category in SINGLE_CATEGORIES}
        if scope == "single"
        else dict(PAIR_CATEGORY_MINIMUMS)
    )
    missing = [
        f"{category}={counts[category]}/{minimum}"
        for category, minimum in minimums.items()
        if counts[category] < minimum
    ]
    if missing:
        raise BuildError("评测类别配额不足：" + "，".join(missing))


def _validate_unique_queries(cases: tuple[HistoryEvaluationCase, ...]) -> None:
    seen: dict[str, str] = {}
    for case in cases:
        normalized = normalize_for_search(case.query).casefold().strip()
        if not normalized:
            raise BuildError(f"评测 query 规范化后为空：{case.case_id}")
        previous = seen.get(normalized)
        if previous is not None:
            raise BuildError(
                f"评测规范化 query 重复：{previous} 与 {case.case_id}"
            )
        seen[normalized] = case.case_id


def _validate_review_state(cases: tuple[HistoryEvaluationCase, ...]) -> None:
    unreviewed = [case.case_id for case in cases if not case.reviewed]
    if unreviewed:
        preview = "，".join(unreviewed[:5])
        raise BuildError(f"评测包含尚未人工复核的 case：{preview}")
    missing_rationale = [
        case.case_id
        for case in cases
        if case.expected_result in {"no-result", "one-sided"}
        and not case.reviewer_notes.strip()
    ]
    if missing_rationale:
        raise BuildError(
            "无结果或单侧缺口 case 缺少 reviewerNotes："
            + "，".join(missing_rationale[:5])
        )


def _validate_gold(
    cases: tuple[HistoryEvaluationCase, ...],
    workspaces: tuple[EvaluationWorkspace, ...],
    scope: str,
) -> None:
    by_wiki = {workspace.wiki_id: workspace for workspace in workspaces}
    connections = {
        wiki_id: _open_read_only(workspace.database_path)
        for wiki_id, workspace in by_wiki.items()
    }
    try:
        for case in cases:
            evidence_wikis = {item.wiki_id for item in case.gold_evidence}
            if case.expected_result == "evidence" and not case.gold_evidence:
                raise BuildError(f"正向评测缺少 goldEvidence：{case.case_id}")
            if case.expected_result == "both" and evidence_wikis != set(by_wiki):
                raise BuildError(f"双侧评测没有覆盖两个 Wiki：{case.case_id}")
            if case.expected_result == "one-sided" and len(evidence_wikis) != 1:
                raise BuildError(f"单侧缺口评测必须且只能引用一个 Wiki：{case.case_id}")
            if case.expected_result == "no-result" and case.gold_evidence:
                raise BuildError(f"无结果评测不能包含 goldEvidence：{case.case_id}")
            if scope == "single" and evidence_wikis - set(by_wiki):
                raise BuildError(f"单库评测引用了范围外 Wiki：{case.case_id}")
            for evidence in case.gold_evidence:
                workspace = by_wiki.get(evidence.wiki_id)
                if workspace is None or evidence.wiki_version != workspace.wiki_version:
                    raise BuildError(
                        f"goldEvidence Wiki 身份不一致：{case.case_id}/{evidence.chunk_id}"
                    )
                row = connections[evidence.wiki_id].execute(
                    """
                    SELECT documents.document_id, sections.section_id,
                           chunks.original_text, chunks.locator_json,
                           source_locators.locator_json
                    FROM chunks
                    JOIN sections USING(section_id)
                    JOIN documents USING(document_id)
                    LEFT JOIN source_locators USING(chunk_id)
                    WHERE chunks.chunk_id=?
                    """,
                    (evidence.chunk_id,),
                ).fetchone()
                if row is None:
                    raise BuildError(
                        f"goldEvidence 引用了不存在的 chunk：{evidence.chunk_id}"
                    )
                document_id, section_id, original, chunk_locator, source_locator = row
                if evidence.quote not in original:
                    raise BuildError(
                        f"goldEvidence quote 不是原文连续片段：{case.case_id}/{evidence.chunk_id}"
                    )
                if evidence.document_id != document_id or evidence.section_id != section_id:
                    raise BuildError(
                        f"goldEvidence document/section 与数据库不一致：{case.case_id}"
                    )
                expected_locator = json.loads(chunk_locator)
                if (
                    source_locator != chunk_locator
                    or evidence.locator != expected_locator
                ):
                    raise BuildError(
                        f"goldEvidence locator 与数据库不一致：{case.case_id}/{evidence.chunk_id}"
                    )
    finally:
        for connection in connections.values():
            connection.close()


def _pair_final_ranking(
    left: EvaluationWorkspace,
    right: EvaluationWorkspace,
    query: str,
) -> tuple[tuple[str, str], ...]:
    rankings = (
        (
            left.wiki_id,
            retrieve_chunks(left.database_path, query, limit=PAIR_FINAL_LIMIT),
        ),
        (
            right.wiki_id,
            retrieve_chunks(right.database_path, query, limit=PAIR_FINAL_LIMIT),
        ),
    )
    result: list[tuple[str, str]] = []
    for rank in range(PAIR_FINAL_LIMIT):
        for wiki_id, chunk_ids in rankings:
            if rank < len(chunk_ids):
                result.append((wiki_id, chunk_ids[rank]))
                if len(result) == PAIR_FINAL_LIMIT:
                    return tuple(result)
    return tuple(result)


def _average(values: Sequence[float]) -> float:
    return round(sum(values) / len(values), 6) if values else 0.0


def _open_read_only(path: Path) -> sqlite3.Connection:
    try:
        return sqlite3.connect(f"{Path(path).resolve().as_uri()}?mode=ro", uri=True)
    except sqlite3.Error as error:
        raise BuildError(f"评测无法只读打开 content.sqlite：{error}") from error


def _nonempty_string(value: object, field: str, line: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BuildError(f"评测第 {line} 行 {field} 必须是非空字符串")
    return value


def _plain_nonempty_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise BuildError(f"{field} 必须是非空字符串")
    return value


def _reject_json_constant(value: str) -> object:
    raise ValueError(f"不允许 JSON 常量 {value}")


__all__ = [
    "PAIR_CATEGORY_MINIMUMS",
    "PAIR_FINAL_LIMIT",
    "SINGLE_CATEGORIES",
    "SINGLE_MINIMUM_PER_CATEGORY",
    "HistoryEvaluationSet",
    "PairEvaluationReport",
    "create_evaluation_template",
    "evaluate_pair",
    "load_single_retrieval_cases",
    "pair_gate_failures",
    "single_gate_failures",
    "validate_evaluation_set",
]
