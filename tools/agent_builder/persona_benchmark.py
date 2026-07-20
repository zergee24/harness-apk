import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Iterable, Sequence

from .models import BuildError


BENCHMARK_CATEGORIES = frozenset(
    {"grounding", "stance", "voice", "temporal", "continuity", "blind"}
)
MAX_BENCHMARK_ROWS = 4_096
MAX_BENCHMARK_TOTAL_BYTES = 16 * 1024 * 1024
MAX_BENCHMARK_LINE_BYTES = 256 * 1024
MAX_BENCHMARK_ID_CHARS = 256
MAX_BENCHMARK_QUESTION_CHARS = 8_192
MAX_BENCHMARK_ANSWER_CHARS = 65_536
MAX_BENCHMARK_LIST_ITEMS = 512
MAX_BENCHMARK_PATTERN_ITEMS = 128
MAX_BENCHMARK_PATTERN_CHARS = 1_024

MIN_STANCE_GROUNDED_COUNT = 30
MIN_GROUNDING_RATE = 0.85
MIN_TEMPORAL_PASS_COUNT = 12
MIN_CONTINUITY_PASS_COUNT = 20
MAX_AVOID_PATTERN_RATE = 0.05
MIN_BLIND_PAIR_COUNT = 20
MIN_V2_PREFERENCE_RATE = 0.70

_CASE_FIELDS = frozenset(
    {
        "id",
        "category",
        "question",
        "requiredEvidence",
        "requiredPeriods",
        "requiredRecallKeys",
        "forbiddenRecallKeys",
        "forbiddenPatterns",
    }
)
_RESPONSE_REQUIRED_FIELDS = frozenset(
    {"caseId", "answer", "evidenceIds", "recalledFactKeys"}
)
_RESPONSE_OPTIONAL_FIELDS = frozenset({"question"})
_BLIND_RESPONSE_FIELDS = frozenset(
    {"caseId", "question", "answer", "evidenceIds", "recalledFactKeys"}
)
_PUBLIC_PAIR_FIELDS = frozenset({"pairId", "question", "answerA", "answerB"})
_ANSWER_KEY_FIELDS = frozenset({"schemaVersion", "seed", "pairs"})
_ANSWER_KEY_PAIR_FIELDS = frozenset(
    {"pairId", "caseId", "versionA", "versionB"}
)
_CHOICE_FIELDS = frozenset({"pairId", "choice"})
_BLIND_VERSION_HINT_PATTERN = re.compile(
    r"(?i)(?<![a-z0-9])v[12](?![a-z0-9])"
)


@dataclass(frozen=True)
class BenchmarkCase:
    case_id: str
    category: str
    question: str
    required_evidence: tuple[str, ...]
    required_periods: tuple[str, ...]
    required_recall_keys: tuple[str, ...]
    forbidden_recall_keys: tuple[str, ...]
    forbidden_patterns: tuple[str, ...]


@dataclass(frozen=True)
class BenchmarkResponse:
    case_id: str
    answer: str
    evidence_ids: tuple[str, ...]
    recalled_fact_keys: tuple[str, ...]


@dataclass(frozen=True)
class CountRate:
    numerator: int
    denominator: int
    rate: float

    @classmethod
    def of(cls, numerator: int, denominator: int) -> "CountRate":
        rate = 0.0 if denominator == 0 else round(numerator / denominator, 6)
        return cls(numerator=numerator, denominator=denominator, rate=rate)

    def to_dict(self) -> dict[str, int | float]:
        return {
            "denominator": self.denominator,
            "numerator": self.numerator,
            "rate": self.rate,
        }


@dataclass(frozen=True)
class PersonaBenchmarkReport:
    case_count: int
    metrics: dict[str, CountRate]
    avoid_pattern_hits: CountRate
    avoid_pattern_case_hits: CountRate
    thresholds: dict[str, bool]
    passed: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "avoidPatternCaseHits": self.avoid_pattern_case_hits.to_dict(),
            "avoidPatternHits": self.avoid_pattern_hits.to_dict(),
            "caseCount": self.case_count,
            "metrics": {
                name: metric.to_dict()
                for name, metric in sorted(self.metrics.items())
            },
            "passed": self.passed,
            "schemaVersion": 1,
            "thresholds": dict(sorted(self.thresholds.items())),
        }


@dataclass(frozen=True)
class BlindResponse:
    case_id: str
    question: str
    answer: str


@dataclass(frozen=True)
class BlindPair:
    pair_id: str
    case_id: str
    question: str
    answer_a: str
    answer_b: str
    version_a: str
    version_b: str

    def to_public_dict(self) -> dict[str, str]:
        return {
            "answerA": self.answer_a,
            "answerB": self.answer_b,
            "pairId": self.pair_id,
            "question": self.question,
        }

    def to_key_dict(self) -> dict[str, str]:
        return {
            "caseId": self.case_id,
            "pairId": self.pair_id,
            "versionA": self.version_a,
            "versionB": self.version_b,
        }


@dataclass(frozen=True)
class BlindChoice:
    pair_id: str
    choice: str


@dataclass(frozen=True)
class BlindBundlePaths:
    output_dir: Path
    pairs_path: Path
    answer_key_path: Path


@dataclass(frozen=True)
class BlindComparisonReport:
    pair_count: int
    v2_wins: CountRate
    v2_losses: CountRate
    ties: CountRate
    passed: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "pairCount": self.pair_count,
            "passed": self.passed,
            "schemaVersion": 1,
            "thresholds": {
                "minimumPairCount": self.pair_count >= MIN_BLIND_PAIR_COUNT,
                "minimumV2WinRateAllPairs": self.v2_wins.rate
                >= MIN_V2_PREFERENCE_RATE,
            },
            "ties": self.ties.to_dict(),
            "v2Losses": self.v2_losses.to_dict(),
            "v2Wins": self.v2_wins.to_dict(),
        }


def load_benchmark_cases(path: Path) -> list[BenchmarkCase]:
    return [_validate_case(row, index) for index, row in enumerate(_read_jsonl(path, "用例"), 1)]


def load_benchmark_responses(path: Path) -> list[BenchmarkResponse]:
    return [
        _validate_response(row, index)
        for index, row in enumerate(_read_jsonl(path, "响应"), 1)
    ]


def load_blind_responses(path: Path) -> list[BlindResponse]:
    return [
        _validate_blind_response(row, index)
        for index, row in enumerate(_read_jsonl(path, "盲测响应"), 1)
    ]


def load_blind_choices(path: Path) -> list[BlindChoice]:
    return [
        _validate_blind_choice(row, index)
        for index, row in enumerate(_read_jsonl(path, "盲测选择"), 1)
    ]


def score_benchmark(
    cases: Sequence[BenchmarkCase | dict[str, Any]],
    responses: Sequence[BenchmarkResponse | dict[str, Any]],
) -> PersonaBenchmarkReport:
    validated_cases = _coerce_cases(cases)
    validated_responses = _coerce_responses(responses)
    case_by_id = _index_unique(validated_cases, "用例", lambda item: item.case_id)
    response_by_id = _index_unique(validated_responses, "响应", lambda item: item.case_id)

    missing = sorted(set(case_by_id) - set(response_by_id))
    if missing:
        raise BuildError(f"响应缺失 caseId：{', '.join(missing[:8])}")
    extra = sorted(set(response_by_id) - set(case_by_id))
    if extra:
        raise BuildError(f"响应包含额外 caseId：{', '.join(extra[:8])}")

    category_passes = {
        "grounding": [],
        "stance": [],
        "voice": [],
        "temporal": [],
        "continuity": [],
    }
    pattern_hit_count = 0
    pattern_count = 0
    pattern_case_hit_count = 0
    pattern_case_count = 0

    for benchmark_case in validated_cases:
        response = response_by_id[benchmark_case.case_id]
        normalized_answer = _normalized_literal(response.answer)
        pattern_hits = sum(
            1
            for pattern in benchmark_case.forbidden_patterns
            if _normalized_literal(pattern) in normalized_answer
        )
        if benchmark_case.forbidden_patterns:
            pattern_case_count += 1
            if pattern_hits > 0:
                pattern_case_hit_count += 1
        pattern_count += len(benchmark_case.forbidden_patterns)
        pattern_hit_count += pattern_hits

        required_evidence = set(benchmark_case.required_evidence)
        grounding_passed = bool(required_evidence) and required_evidence.issubset(
            response.evidence_ids
        )
        if benchmark_case.category == "grounding":
            category_passes["grounding"].append(grounding_passed)
        elif benchmark_case.category == "stance":
            category_passes["stance"].append(grounding_passed)
        elif benchmark_case.category == "voice":
            category_passes["voice"].append(pattern_hits == 0)
        elif benchmark_case.category == "temporal":
            category_passes["temporal"].append(
                grounding_passed
                and bool(benchmark_case.required_periods)
                and pattern_hits == 0
            )
        elif benchmark_case.category == "continuity":
            recalled = set(response.recalled_fact_keys)
            category_passes["continuity"].append(
                bool(benchmark_case.required_recall_keys)
                and set(benchmark_case.required_recall_keys).issubset(recalled)
                and set(benchmark_case.forbidden_recall_keys).isdisjoint(recalled)
            )

    metrics = {
        category: CountRate.of(sum(results), len(results))
        for category, results in category_passes.items()
    }
    avoid_pattern_hits = CountRate.of(pattern_hit_count, pattern_count)
    avoid_pattern_case_hits = CountRate.of(pattern_case_hit_count, pattern_case_count)
    thresholds = {
        "avoidPatternHitRate": avoid_pattern_hits.rate <= MAX_AVOID_PATTERN_RATE,
        "continuity": (
            metrics["continuity"].denominator >= MIN_CONTINUITY_PASS_COUNT
            and metrics["continuity"].numerator == metrics["continuity"].denominator
        ),
        "groundingRate": (
            metrics["grounding"].denominator > 0
            and metrics["grounding"].rate >= MIN_GROUNDING_RATE
        ),
        "stanceGrounded": (
            metrics["stance"].denominator >= MIN_STANCE_GROUNDED_COUNT
            and metrics["stance"].numerator == metrics["stance"].denominator
        ),
        "temporal": (
            metrics["temporal"].denominator >= MIN_TEMPORAL_PASS_COUNT
            and metrics["temporal"].numerator == metrics["temporal"].denominator
        ),
    }
    return PersonaBenchmarkReport(
        case_count=len(validated_cases),
        metrics=metrics,
        avoid_pattern_hits=avoid_pattern_hits,
        avoid_pattern_case_hits=avoid_pattern_case_hits,
        thresholds=thresholds,
        passed=all(thresholds.values()),
    )


def build_blind_pairs(
    v1_responses: Sequence[BlindResponse | dict[str, Any]],
    v2_responses: Sequence[BlindResponse | dict[str, Any]],
    *,
    seed: int = 20260718,
) -> list[BlindPair]:
    normalized_seed = _validated_seed(seed)
    v1 = _coerce_blind_responses(v1_responses, "V1")
    v2 = _coerce_blind_responses(v2_responses, "V2")
    v1_by_id = _index_unique(v1, "V1 盲测响应", lambda item: item.case_id)
    v2_by_id = _index_unique(v2, "V2 盲测响应", lambda item: item.case_id)
    if len(v1_by_id) < MIN_BLIND_PAIR_COUNT or len(v2_by_id) < MIN_BLIND_PAIR_COUNT:
        raise BuildError(f"盲测响应至少需要 {MIN_BLIND_PAIR_COUNT} 题")
    missing = sorted(set(v1_by_id) - set(v2_by_id))
    if missing:
        raise BuildError(f"V2 盲测响应缺失 caseId：{', '.join(missing[:8])}")
    extra = sorted(set(v2_by_id) - set(v1_by_id))
    if extra:
        raise BuildError(f"V2 盲测响应包含额外 caseId：{', '.join(extra[:8])}")

    pairs: list[BlindPair] = []
    for index, case_id in enumerate(sorted(v1_by_id), 1):
        left = v1_by_id[case_id]
        right = v2_by_id[case_id]
        if left.question != right.question:
            raise BuildError(f"V1/V2 盲测 question 不一致：{case_id}")
        _reject_blind_version_hint(left.question, f"{case_id} question")
        _reject_blind_version_hint(left.answer, f"{case_id} V1 answer")
        _reject_blind_version_hint(right.answer, f"{case_id} V2 answer")
        swap = hashlib.sha256(
            f"{normalized_seed}\0{case_id}".encode("utf-8")
        ).digest()[0] & 1
        if swap:
            answer_a, answer_b = right.answer, left.answer
            version_a, version_b = "v2", "v1"
        else:
            answer_a, answer_b = left.answer, right.answer
            version_a, version_b = "v1", "v2"
        pairs.append(
            BlindPair(
                pair_id=f"pair-{index:04d}",
                case_id=case_id,
                question=left.question,
                answer_a=answer_a,
                answer_b=answer_b,
                version_a=version_a,
                version_b=version_b,
            )
        )
    return pairs


def prepare_blind_bundle(
    v1_responses: Sequence[BlindResponse | dict[str, Any]],
    v2_responses: Sequence[BlindResponse | dict[str, Any]],
    output_dir: Path,
    *,
    seed: int = 20260718,
) -> BlindBundlePaths:
    if os.path.lexists(output_dir):
        raise BuildError(f"盲测输出已存在，拒绝覆盖：{output_dir}")
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    pairs = build_blind_pairs(v1_responses, v2_responses, seed=seed)
    try:
        staging = Path(
            tempfile.mkdtemp(
                prefix=f".{output_dir.name}.staging-",
                dir=output_dir.parent,
            )
        )
    except OSError as error:
        raise BuildError(f"无法创建盲测 staging：{error}") from error
    try:
        pairs_path = staging / "pairs.jsonl"
        answer_key_path = staging / "answer-key.json"
        pairs_path.write_bytes(
            b"".join(
                canonical_json_bytes(pair.to_public_dict()) + b"\n"
                for pair in pairs
            )
        )
        answer_key_path.write_bytes(
            canonical_json_bytes(
                {
                    "pairs": [pair.to_key_dict() for pair in pairs],
                    "schemaVersion": 1,
                    "seed": _validated_seed(seed),
                }
            )
            + b"\n"
        )
        answer_key_path.chmod(0o600)
        if os.path.lexists(output_dir):
            raise BuildError(f"盲测输出已存在，拒绝覆盖：{output_dir}")
        staging.rename(output_dir)
    except BuildError:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    except OSError as error:
        shutil.rmtree(staging, ignore_errors=True)
        raise BuildError(f"盲测输出发布失败：{error}") from error
    return BlindBundlePaths(
        output_dir=output_dir,
        pairs_path=output_dir / "pairs.jsonl",
        answer_key_path=output_dir / "answer-key.json",
    )


def load_blind_bundle(
    pairs_path: Path,
    answer_key_path: Path,
) -> list[BlindPair]:
    public_rows = _read_jsonl(pairs_path, "盲测公开 pairs")
    public_by_id: dict[str, dict[str, str]] = {}
    public_order: list[str] = []
    for index, raw in enumerate(public_rows, 1):
        payload = _required_object(raw, "盲测公开 pair", index)
        _reject_unknown_fields(
            payload,
            _PUBLIC_PAIR_FIELDS,
            "盲测公开 pair",
            index,
        )
        pair_id = _required_string(
            payload,
            "pairId",
            MAX_BENCHMARK_ID_CHARS,
            "盲测公开 pair",
            index,
        )
        if pair_id in public_by_id:
            raise BuildError(f"盲测公开 pairs 存在重复 id：{pair_id}")
        public_order.append(pair_id)
        public_by_id[pair_id] = {
            "question": _required_string(
                payload,
                "question",
                MAX_BENCHMARK_QUESTION_CHARS,
                "盲测公开 pair",
                index,
            ),
            "answerA": _required_string(
                payload,
                "answerA",
                MAX_BENCHMARK_ANSWER_CHARS,
                "盲测公开 pair",
                index,
            ),
            "answerB": _required_string(
                payload,
                "answerB",
                MAX_BENCHMARK_ANSWER_CHARS,
                "盲测公开 pair",
                index,
            ),
        }

    key_payload = _read_answer_key(answer_key_path)
    key_rows = key_payload["pairs"]
    if not isinstance(key_rows, list):
        raise BuildError("盲测 answer key pairs 必须是数组")
    if len(key_rows) > MAX_BENCHMARK_ROWS:
        raise BuildError("盲测 answer key pairs 数量超过上限")
    key_by_id: dict[str, dict[str, str]] = {}
    key_case_ids: set[str] = set()
    for index, raw in enumerate(key_rows, 1):
        payload = _required_object(raw, "盲测 answer key pair", index)
        _reject_unknown_fields(
            payload,
            _ANSWER_KEY_PAIR_FIELDS,
            "盲测 answer key pair",
            index,
        )
        pair_id = _required_string(
            payload,
            "pairId",
            MAX_BENCHMARK_ID_CHARS,
            "盲测 answer key pair",
            index,
        )
        if pair_id in key_by_id:
            raise BuildError(f"盲测 answer key 存在重复 id：{pair_id}")
        version_a = _required_string(
            payload,
            "versionA",
            2,
            "盲测 answer key pair",
            index,
        )
        version_b = _required_string(
            payload,
            "versionB",
            2,
            "盲测 answer key pair",
            index,
        )
        if {version_a, version_b} != {"v1", "v2"}:
            raise BuildError("盲测 answer key 版本映射必须恰好包含 v1/v2")
        case_id = _required_string(
            payload,
            "caseId",
            MAX_BENCHMARK_ID_CHARS,
            "盲测 answer key pair",
            index,
        )
        if case_id in key_case_ids:
            raise BuildError(f"盲测 answer key 存在重复 caseId：{case_id}")
        key_case_ids.add(case_id)
        key_by_id[pair_id] = {
            "caseId": case_id,
            "versionA": version_a,
            "versionB": version_b,
        }

    missing = sorted(set(public_by_id) - set(key_by_id))
    if missing:
        raise BuildError(f"盲测 answer key 缺失 pairId：{', '.join(missing[:8])}")
    extra = sorted(set(key_by_id) - set(public_by_id))
    if extra:
        raise BuildError(f"盲测 answer key 包含额外 pairId：{', '.join(extra[:8])}")
    if len(public_order) < MIN_BLIND_PAIR_COUNT:
        raise BuildError(f"盲测 pairs 至少需要 {MIN_BLIND_PAIR_COUNT} 题")

    return [
        BlindPair(
            pair_id=pair_id,
            case_id=key_by_id[pair_id]["caseId"],
            question=public_by_id[pair_id]["question"],
            answer_a=public_by_id[pair_id]["answerA"],
            answer_b=public_by_id[pair_id]["answerB"],
            version_a=key_by_id[pair_id]["versionA"],
            version_b=key_by_id[pair_id]["versionB"],
        )
        for pair_id in public_order
    ]


def score_blind_choices(
    pairs: Sequence[BlindPair],
    choices: Sequence[BlindChoice | dict[str, Any]],
) -> BlindComparisonReport:
    if len(pairs) < MIN_BLIND_PAIR_COUNT:
        raise BuildError(f"盲测 pairs 至少需要 {MIN_BLIND_PAIR_COUNT} 题")
    pair_by_id = _index_unique(pairs, "盲测 pair", lambda item: item.pair_id)
    validated_choices = [
        item if isinstance(item, BlindChoice) else _validate_blind_choice(item, index)
        for index, item in enumerate(choices, 1)
    ]
    choice_by_id = _index_unique(
        validated_choices,
        "盲测选择",
        lambda item: item.pair_id,
    )
    missing = sorted(set(pair_by_id) - set(choice_by_id))
    if missing:
        raise BuildError(f"盲测选择缺失 pairId：{', '.join(missing[:8])}")
    extra = sorted(set(choice_by_id) - set(pair_by_id))
    if extra:
        raise BuildError(f"盲测选择包含额外 pairId：{', '.join(extra[:8])}")

    v2_wins = 0
    v2_losses = 0
    ties = 0
    for pair_id, pair in pair_by_id.items():
        choice = choice_by_id[pair_id].choice
        if choice == "TIE":
            ties += 1
        else:
            selected_version = pair.version_a if choice == "A" else pair.version_b
            if selected_version == "v2":
                v2_wins += 1
            else:
                v2_losses += 1
    pair_count = len(pair_by_id)
    win_metric = CountRate.of(v2_wins, pair_count)
    return BlindComparisonReport(
        pair_count=pair_count,
        v2_wins=win_metric,
        v2_losses=CountRate.of(v2_losses, pair_count),
        ties=CountRate.of(ties, pair_count),
        passed=(
            pair_count >= MIN_BLIND_PAIR_COUNT
            and win_metric.rate >= MIN_V2_PREFERENCE_RATE
        ),
    )


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def publish_new_canonical_json(path: Path, value: Any) -> None:
    if os.path.lexists(path):
        raise BuildError(f"输出已存在，拒绝覆盖：{path}")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.staging-",
            dir=path.parent,
        )
    except OSError as error:
        raise BuildError(f"无法创建报告 staging：{error}") from error
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            os.fchmod(stream.fileno(), 0o644)
            stream.write(canonical_json_bytes(value) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise BuildError(f"输出已存在，拒绝覆盖：{path}") from error
        except OSError as error:
            raise BuildError(f"报告原子发布失败：{error}") from error
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            pass


def _coerce_cases(
    cases: Sequence[BenchmarkCase | dict[str, Any]],
) -> list[BenchmarkCase]:
    if len(cases) > MAX_BENCHMARK_ROWS:
        raise BuildError(f"用例数量超过上限：{MAX_BENCHMARK_ROWS}")
    return [
        item if isinstance(item, BenchmarkCase) else _validate_case(item, index)
        for index, item in enumerate(cases, 1)
    ]


def _coerce_responses(
    responses: Sequence[BenchmarkResponse | dict[str, Any]],
) -> list[BenchmarkResponse]:
    if len(responses) > MAX_BENCHMARK_ROWS:
        raise BuildError(f"响应数量超过上限：{MAX_BENCHMARK_ROWS}")
    return [
        item if isinstance(item, BenchmarkResponse) else _validate_response(item, index)
        for index, item in enumerate(responses, 1)
    ]


def _validate_case(row: Any, index: int) -> BenchmarkCase:
    payload = _required_object(row, "用例", index)
    _reject_unknown_fields(payload, _CASE_FIELDS, "用例", index)
    case_id = _required_string(payload, "id", MAX_BENCHMARK_ID_CHARS, "用例", index)
    category = _required_string(payload, "category", 32, "用例", index)
    if category not in BENCHMARK_CATEGORIES:
        raise BuildError(f"用例第 {index} 行 category 无效：{category}")
    return BenchmarkCase(
        case_id=case_id,
        category=category,
        question=_required_string(
            payload,
            "question",
            MAX_BENCHMARK_QUESTION_CHARS,
            "用例",
            index,
        ),
        required_evidence=_string_list(
            payload,
            "requiredEvidence",
            MAX_BENCHMARK_LIST_ITEMS,
            MAX_BENCHMARK_ID_CHARS,
            "用例",
            index,
        ),
        required_periods=_string_list(
            payload,
            "requiredPeriods",
            MAX_BENCHMARK_LIST_ITEMS,
            MAX_BENCHMARK_ID_CHARS,
            "用例",
            index,
        ),
        required_recall_keys=_string_list(
            payload,
            "requiredRecallKeys",
            MAX_BENCHMARK_LIST_ITEMS,
            MAX_BENCHMARK_ID_CHARS,
            "用例",
            index,
        ),
        forbidden_recall_keys=_string_list(
            payload,
            "forbiddenRecallKeys",
            MAX_BENCHMARK_LIST_ITEMS,
            MAX_BENCHMARK_ID_CHARS,
            "用例",
            index,
        ),
        forbidden_patterns=_string_list(
            payload,
            "forbiddenPatterns",
            MAX_BENCHMARK_PATTERN_ITEMS,
            MAX_BENCHMARK_PATTERN_CHARS,
            "用例",
            index,
        ),
    )


def _validate_response(row: Any, index: int) -> BenchmarkResponse:
    payload = _required_object(row, "响应", index)
    _reject_fields(
        payload,
        _RESPONSE_REQUIRED_FIELDS,
        _RESPONSE_OPTIONAL_FIELDS,
        "响应",
        index,
    )
    if "question" in payload:
        _required_string(
            payload,
            "question",
            MAX_BENCHMARK_QUESTION_CHARS,
            "响应",
            index,
        )
    return BenchmarkResponse(
        case_id=_required_string(
            payload,
            "caseId",
            MAX_BENCHMARK_ID_CHARS,
            "响应",
            index,
        ),
        answer=_required_string(
            payload,
            "answer",
            MAX_BENCHMARK_ANSWER_CHARS,
            "响应",
            index,
        ),
        evidence_ids=_string_list(
            payload,
            "evidenceIds",
            MAX_BENCHMARK_LIST_ITEMS,
            MAX_BENCHMARK_ID_CHARS,
            "响应",
            index,
        ),
        recalled_fact_keys=_string_list(
            payload,
            "recalledFactKeys",
            MAX_BENCHMARK_LIST_ITEMS,
            MAX_BENCHMARK_ID_CHARS,
            "响应",
            index,
        ),
    )


def _coerce_blind_responses(
    responses: Sequence[BlindResponse | dict[str, Any]],
    label: str,
) -> list[BlindResponse]:
    if len(responses) > MAX_BENCHMARK_ROWS:
        raise BuildError(f"{label} 盲测响应数量超过上限：{MAX_BENCHMARK_ROWS}")
    return [
        item if isinstance(item, BlindResponse) else _validate_blind_response(item, index)
        for index, item in enumerate(responses, 1)
    ]


def _validate_blind_response(row: Any, index: int) -> BlindResponse:
    payload = _required_object(row, "盲测响应", index)
    _reject_unknown_fields(
        payload,
        _BLIND_RESPONSE_FIELDS,
        "盲测响应",
        index,
    )
    _string_list(
        payload,
        "evidenceIds",
        MAX_BENCHMARK_LIST_ITEMS,
        MAX_BENCHMARK_ID_CHARS,
        "盲测响应",
        index,
    )
    _string_list(
        payload,
        "recalledFactKeys",
        MAX_BENCHMARK_LIST_ITEMS,
        MAX_BENCHMARK_ID_CHARS,
        "盲测响应",
        index,
    )
    return BlindResponse(
        case_id=_required_string(
            payload,
            "caseId",
            MAX_BENCHMARK_ID_CHARS,
            "盲测响应",
            index,
        ),
        question=_required_string(
            payload,
            "question",
            MAX_BENCHMARK_QUESTION_CHARS,
            "盲测响应",
            index,
        ),
        answer=_required_string(
            payload,
            "answer",
            MAX_BENCHMARK_ANSWER_CHARS,
            "盲测响应",
            index,
        ),
    )


def _validate_blind_choice(row: Any, index: int) -> BlindChoice:
    payload = _required_object(row, "盲测选择", index)
    _reject_unknown_fields(payload, _CHOICE_FIELDS, "盲测选择", index)
    choice = _required_string(payload, "choice", 3, "盲测选择", index)
    if choice not in {"A", "B", "TIE"}:
        raise BuildError("盲测 choice 只能是 A、B 或 TIE")
    return BlindChoice(
        pair_id=_required_string(
            payload,
            "pairId",
            MAX_BENCHMARK_ID_CHARS,
            "盲测选择",
            index,
        ),
        choice=choice,
    )


def _read_jsonl(path: Path, label: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    stream, info = _open_regular_binary(path, f"{label} JSONL")
    try:
        with stream:
            if info.st_size > MAX_BENCHMARK_TOTAL_BYTES:
                raise BuildError(f"{label} JSONL 超过总大小上限")
            total_bytes = 0
            line_number = 0
            while True:
                raw_line = stream.readline(MAX_BENCHMARK_LINE_BYTES + 1)
                if not raw_line:
                    break
                line_number += 1
                if line_number > MAX_BENCHMARK_ROWS:
                    raise BuildError(f"{label} JSONL 行数超过上限：{MAX_BENCHMARK_ROWS}")
                if len(raw_line) > MAX_BENCHMARK_LINE_BYTES:
                    raise BuildError(f"{label} JSONL 单行超过大小上限")
                total_bytes += len(raw_line)
                if total_bytes > MAX_BENCHMARK_TOTAL_BYTES:
                    raise BuildError(f"{label} JSONL 超过总大小上限")
                if not raw_line.strip():
                    raise BuildError(f"{label} JSONL 第 {line_number} 行不能为空")
                try:
                    text = raw_line.decode("utf-8")
                except UnicodeDecodeError as error:
                    raise BuildError(
                        f"{label} JSONL 第 {line_number} 行不是严格 UTF-8"
                    ) from error
                try:
                    value = json.loads(
                        text,
                        object_pairs_hook=_unique_object,
                        parse_constant=_reject_json_constant,
                    )
                except BuildError:
                    raise
                except (json.JSONDecodeError, RecursionError, ValueError) as error:
                    raise BuildError(
                        f"{label} JSONL 第 {line_number} 行无法解析：{error}"
                    ) from error
                if not isinstance(value, dict):
                    raise BuildError(f"{label} JSONL 第 {line_number} 行必须是对象")
                rows.append(value)
    except BuildError:
        raise
    except OSError as error:
        raise BuildError(f"{label} JSONL 无法读取：{path}：{error}") from error

    if not rows:
        raise BuildError(f"{label} JSONL 不能为空")
    return rows


def _read_answer_key(path: Path) -> dict[str, Any]:
    stream, info = _open_regular_binary(path, "盲测 answer key")
    try:
        with stream:
            if stat.S_IMODE(info.st_mode) != 0o600:
                raise BuildError("盲测 answer key 权限必须是 0600")
            if info.st_size > MAX_BENCHMARK_TOTAL_BYTES:
                raise BuildError("盲测 answer key 超过大小上限")
            raw = stream.read(MAX_BENCHMARK_TOTAL_BYTES + 1)
            if len(raw) > MAX_BENCHMARK_TOTAL_BYTES:
                raise BuildError("盲测 answer key 超过大小上限")
        text = raw.decode("utf-8")
        value = json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_json_constant,
        )
    except BuildError:
        raise
    except UnicodeDecodeError as error:
        raise BuildError("盲测 answer key 不是严格 UTF-8") from error
    except (OSError, json.JSONDecodeError, RecursionError, ValueError) as error:
        raise BuildError(f"盲测 answer key 无法读取：{error}") from error
    payload = _required_object(value, "盲测 answer key", 1)
    _reject_unknown_fields(payload, _ANSWER_KEY_FIELDS, "盲测 answer key", 1)
    if type(payload["schemaVersion"]) is not int or payload["schemaVersion"] != 1:
        raise BuildError("盲测 answer key schemaVersion 必须是 1")
    _validated_seed(payload["seed"])
    return payload


def _open_regular_binary(
    path: Path,
    label: str,
) -> tuple[BinaryIO, os.stat_result]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise BuildError(f"{label} 无法打开：{path}：{error}") from error
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode):
            raise BuildError(f"{label} 必须是普通文件：{path}")
        return os.fdopen(descriptor, "rb"), info
    except BuildError:
        os.close(descriptor)
        raise
    except OSError as error:
        os.close(descriptor)
        raise BuildError(f"{label} 无法检查：{path}：{error}") from error


def _unique_object(pairs: Iterable[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise BuildError(f"JSON 对象存在重复字段：{key}")
        value[key] = item
    return value


def _reject_json_constant(value: str) -> None:
    raise BuildError(f"JSON 不接受非常量数值：{value}")


def _required_object(value: Any, label: str, index: int) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise BuildError(f"{label}第 {index} 行必须是对象")
    return value


def _reject_unknown_fields(
    value: dict[str, Any],
    allowed: frozenset[str],
    label: str,
    index: int,
) -> None:
    _reject_fields(value, allowed, frozenset(), label, index)


def _reject_fields(
    value: dict[str, Any],
    required: frozenset[str],
    optional: frozenset[str],
    label: str,
    index: int,
) -> None:
    unknown = sorted(set(value) - required - optional)
    if unknown:
        raise BuildError(f"{label}第 {index} 行包含不支持字段：{', '.join(unknown)}")
    missing = sorted(required - set(value))
    if missing:
        raise BuildError(f"{label}第 {index} 行缺少字段：{', '.join(missing)}")


def _reject_blind_version_hint(value: str, label: str) -> None:
    normalized = unicodedata.normalize("NFKC", value)
    if _BLIND_VERSION_HINT_PATTERN.search(normalized):
        raise BuildError(f"盲测公开内容包含版本提示：{label}")


def _required_string(
    value: dict[str, Any],
    field: str,
    max_chars: int,
    label: str,
    index: int,
) -> str:
    raw = value.get(field)
    if not isinstance(raw, str):
        raise BuildError(f"{label}第 {index} 行 {field} 必须是字符串")
    if not raw or raw != raw.strip():
        raise BuildError(f"{label}第 {index} 行 {field} 必须是规范化非空字符串")
    if len(raw) > max_chars:
        raise BuildError(f"{label}第 {index} 行 {field} 超过长度上限")
    return raw


def _string_list(
    value: dict[str, Any],
    field: str,
    max_items: int,
    max_chars: int,
    label: str,
    index: int,
) -> tuple[str, ...]:
    raw = value.get(field)
    if not isinstance(raw, list):
        raise BuildError(f"{label}第 {index} 行 {field} 必须是数组")
    if len(raw) > max_items:
        raise BuildError(f"{label}第 {index} 行 {field} 数量超过上限")
    result = tuple(
        _bounded_list_string(item, field, max_chars, label, index)
        for item in raw
    )
    if len(set(result)) != len(result):
        raise BuildError(f"{label}第 {index} 行 {field} 存在重复值")
    return result


def _bounded_list_string(
    value: Any,
    field: str,
    max_chars: int,
    label: str,
    index: int,
) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        raise BuildError(f"{label}第 {index} 行 {field} 只能包含规范化非空字符串")
    if len(value) > max_chars:
        raise BuildError(f"{label}第 {index} 行 {field} 元素超过长度上限")
    return value


def _index_unique(items, label: str, key):
    indexed = {}
    for item in items:
        item_id = key(item)
        if item_id in indexed:
            raise BuildError(f"{label}存在重复 id：{item_id}")
        indexed[item_id] = item
    return indexed


def _validated_seed(value: Any) -> int:
    if type(value) is not int or not -(2**63) <= value < 2**63:
        raise BuildError("盲测 seed 必须是 64 位整数")
    return value


def _normalized_literal(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return " ".join(normalized.split())
