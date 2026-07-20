import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from tools.agent_builder.cli import main as agent_builder_main
from tools.agent_builder.models import BuildError
from tools.agent_builder.persona_benchmark import (
    MAX_BENCHMARK_ANSWER_CHARS,
    MAX_BENCHMARK_LINE_BYTES,
    MIN_BLIND_PAIR_COUNT,
    build_blind_pairs,
    canonical_json_bytes,
    load_benchmark_cases,
    load_benchmark_responses,
    load_blind_bundle,
    load_blind_choices,
    prepare_blind_bundle,
    score_benchmark,
    score_blind_choices,
    _read_answer_key,
)


class PersonaBenchmarkScoreTest(unittest.TestCase):
    def test_score_reports_five_metrics_with_integer_denominators(self):
        cases = [
            case("grounding-001", "grounding", evidence=["e-grounding"]),
            case("stance-001", "stance", evidence=["e-stance"]),
            case("voice-001", "voice", forbidden_patterns=["current data", "three points"]),
            case(
                "temporal-001",
                "temporal",
                evidence=["e-temporal"],
                periods=["1926"],
                forbidden_patterns=["today is 1926"],
            ),
            case(
                "continuity-001",
                "continuity",
                required_recall=["recall-current"],
                forbidden_recall=["recall-other-agent"],
            ),
            case("blind-001", "blind"),
        ]
        responses = [
            response("grounding-001", evidence=["e-grounding"]),
            response("stance-001", evidence=["e-stance"]),
            response("voice-001", answer="CURRENT   DATA should be checked."),
            response("temporal-001", answer="The source is historical.", evidence=["e-temporal"]),
            response("continuity-001", recalled=["recall-current"]),
            response("blind-001", answer="Blind answer"),
        ]

        report = score_benchmark(cases, responses)

        self.assertEqual(
            {"grounding", "stance", "voice", "temporal", "continuity"},
            set(report.metrics),
        )
        self.assertEqual((1, 1), metric_counts(report, "grounding"))
        self.assertEqual((1, 1), metric_counts(report, "stance"))
        self.assertEqual((0, 1), metric_counts(report, "voice"))
        self.assertEqual((1, 1), metric_counts(report, "temporal"))
        self.assertEqual((1, 1), metric_counts(report, "continuity"))
        self.assertEqual((1, 3), counts(report.avoid_pattern_hits))
        self.assertEqual((1, 2), counts(report.avoid_pattern_case_hits))
        self.assertEqual(6, report.case_count)

    def test_stance_and_temporal_require_real_grounding(self):
        cases = [
            case("stance-empty", "stance"),
            case("stance-missing", "stance", evidence=["required"]),
            case("temporal-empty", "temporal", periods=["1927"]),
        ]
        responses = [
            response("stance-empty"),
            response("stance-missing", evidence=["other"]),
            response("temporal-empty"),
        ]

        report = score_benchmark(cases, responses)

        self.assertEqual((0, 2), metric_counts(report, "stance"))
        self.assertEqual((0, 1), metric_counts(report, "temporal"))

    def test_evidence_and_recall_use_all_required_and_no_forbidden_semantics(self):
        cases = [
            case("grounding", "grounding", evidence=["e1", "e2"]),
            case(
                "continuity-missing",
                "continuity",
                required_recall=["r1", "r2"],
                forbidden_recall=["agent-b-secret"],
            ),
            case(
                "continuity-leak",
                "continuity",
                required_recall=["r1"],
                forbidden_recall=["agent-b-secret"],
            ),
        ]
        responses = [
            response("grounding", evidence=["e1"]),
            response("continuity-missing", recalled=["r1"]),
            response("continuity-leak", recalled=["r1", "agent-b-secret"]),
        ]

        report = score_benchmark(cases, responses)

        self.assertEqual((0, 1), metric_counts(report, "grounding"))
        self.assertEqual((0, 2), metric_counts(report, "continuity"))

    def test_patterns_are_unicode_case_and_whitespace_normalized_literals(self):
        cases = [
            case("normalized", "voice", forbidden_patterns=["ＡＢＣ  DEF"]),
            case("regex-literal", "voice", forbidden_patterns=["a.*b"]),
            case("literal-hit", "voice", forbidden_patterns=["a.*b"]),
        ]
        responses = [
            response("normalized", answer="abc\t\n def"),
            response("regex-literal", answer="axxxb"),
            response("literal-hit", answer="prefix a.*b suffix"),
        ]

        report = score_benchmark(cases, responses)

        self.assertEqual((1, 3), metric_counts(report, "voice"))
        self.assertEqual((2, 3), counts(report.avoid_pattern_hits))
        self.assertEqual((2, 3), counts(report.avoid_pattern_case_hits))

    def test_empty_pattern_denominators_are_zero_with_zero_rate(self):
        report = score_benchmark(
            [case("voice-empty", "voice")],
            [response("voice-empty")],
        )

        self.assertEqual((1, 1), metric_counts(report, "voice"))
        self.assertEqual((0, 0), counts(report.avoid_pattern_hits))
        self.assertEqual(0.0, report.avoid_pattern_hits.rate)
        self.assertEqual((0, 0), counts(report.avoid_pattern_case_hits))
        self.assertEqual(0.0, report.avoid_pattern_case_hits.rate)
        self.assertFalse(report.thresholds["avoidPatternHitRate"])
        self.assertFalse(report.passed)

    def test_case_response_sets_must_match_exactly_and_ids_must_be_unique(self):
        valid_case = case("case-1", "grounding", evidence=["e1"])

        with self.assertRaisesRegex(BuildError, "重复"):
            score_benchmark([valid_case, valid_case], [response("case-1", evidence=["e1"])])
        with self.assertRaisesRegex(BuildError, "缺失"):
            score_benchmark([valid_case], [])
        with self.assertRaisesRegex(BuildError, "额外"):
            score_benchmark(
                [valid_case],
                [response("case-1", evidence=["e1"]), response("extra")],
            )
        with self.assertRaisesRegex(BuildError, "重复"):
            score_benchmark(
                [valid_case],
                [response("case-1", evidence=["e1"]), response("case-1", evidence=["e1"])],
            )

    def test_unknown_types_and_self_reported_scores_fail_closed(self):
        with self.assertRaisesRegex(BuildError, "category"):
            score_benchmark(
                [case("unknown", "unknown")],
                [response("unknown")],
            )
        malformed = case("wrong-type", "voice")
        malformed["forbiddenPatterns"] = "not-a-list"
        with self.assertRaisesRegex(BuildError, "forbiddenPatterns"):
            score_benchmark([malformed], [response("wrong-type")])
        self_reported = response("self-report")
        self_reported["passed"] = True
        with self.assertRaisesRegex(BuildError, "不支持字段"):
            score_benchmark([case("self-report", "voice")], [self_reported])
        period_reported = response("period-report")
        period_reported["usedPeriods"] = ["1926"]
        with self.assertRaisesRegex(BuildError, "不支持字段"):
            score_benchmark([case("period-report", "voice")], [period_reported])

    def test_response_question_is_allowed_metadata_and_does_not_affect_score(self):
        benchmark_case = case("shared-response", "grounding", evidence=["e1"])
        with_question = {
            **response("shared-response", evidence=["e1"]),
            "question": "This is carried only so the same file can enter blind prepare.",
        }

        report = score_benchmark([benchmark_case], [with_question])

        self.assertEqual((1, 1), metric_counts(report, "grounding"))
        self.assertNotIn("question", report.to_dict())

    def test_jsonl_loader_rejects_invalid_utf8_oversized_lines_and_answers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            invalid_utf8 = root / "invalid.jsonl"
            invalid_utf8.write_bytes(b"\xff\n")
            with self.assertRaisesRegex(BuildError, "UTF-8"):
                load_benchmark_cases(invalid_utf8)

            oversized_line = root / "oversized.jsonl"
            oversized_line.write_bytes(b"{" + b"x" * MAX_BENCHMARK_LINE_BYTES + b"}\n")
            with self.assertRaisesRegex(BuildError, "单行"):
                load_benchmark_cases(oversized_line)

            long_answer = root / "responses.jsonl"
            long_answer.write_text(
                json.dumps(
                    response("long", answer="x" * (MAX_BENCHMARK_ANSWER_CHARS + 1)),
                    ensure_ascii=False,
                )
                + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(BuildError, "answer"):
                load_benchmark_responses(long_answer)

    def test_jsonl_total_bound_is_enforced_if_file_grows_after_open(self):
        raw = b"".join(
            canonical_json_bytes(item) + b"\n"
            for item in [case("case-1", "voice"), case("case-2", "voice")]
        )
        fake_info = SimpleNamespace(st_mode=0o100644, st_size=0)
        with (
            patch(
                "tools.agent_builder.persona_benchmark.MAX_BENCHMARK_TOTAL_BYTES",
                len(raw) - 1,
            ),
            patch(
                "tools.agent_builder.persona_benchmark._open_regular_binary",
                return_value=(BytesIO(raw), fake_info),
            ),
            self.assertRaisesRegex(BuildError, "总大小"),
        ):
            load_benchmark_cases(Path("growing-cases.jsonl"))


class PersonaBenchmarkBlindTest(unittest.TestCase):
    def test_fixed_seed_is_deterministic_hidden_and_independent_per_case(self):
        v1, v2 = blind_response_sets()

        first = build_blind_pairs(v1, v2, seed=20260718)
        second = build_blind_pairs(v1, v2, seed=20260718)
        changed = build_blind_pairs(v1, v2, seed=20260719)

        self.assertEqual(first, second)
        self.assertTrue(any(left.version_a != right.version_a for left, right in zip(first, changed)))
        self.assertEqual(
            [f"pair-{index:04d}" for index in range(1, MIN_BLIND_PAIR_COUNT + 1)],
            [pair.pair_id for pair in first],
        )
        public_json = json.dumps(
            [pair.to_public_dict() for pair in first],
            ensure_ascii=False,
            sort_keys=True,
        )
        self.assertNotIn("caseId", public_json)
        self.assertNotIn("version", public_json)
        self.assertNotIn('"v1"', public_json)
        self.assertNotIn('"v2"', public_json)

    def test_blind_inputs_require_exact_unique_case_sets_questions_and_minimum(self):
        v1, v2 = blind_response_sets()

        with self.assertRaisesRegex(BuildError, "至少"):
            build_blind_pairs(v1[:-1], v2[:-1], seed=1)
        with self.assertRaisesRegex(BuildError, "重复"):
            build_blind_pairs(v1 + [v1[0]], v2, seed=1)
        with self.assertRaisesRegex(BuildError, "缺失|额外"):
            build_blind_pairs(v1, v2[:-1] + [blind_response("other", "Other", "Other")], seed=1)
        changed_question = [*v2]
        changed_question[0] = {**changed_question[0], "question": "Changed question"}
        with self.assertRaisesRegex(BuildError, "question"):
            build_blind_pairs(v1, changed_question, seed=1)

    def test_blind_public_content_rejects_explicit_version_hints(self):
        v1, v2 = blind_response_sets()
        v1[0] = {**v1[0], "answer": "V1 generated this answer"}

        with self.assertRaisesRegex(BuildError, "版本提示"):
            build_blind_pairs(v1, v2, seed=1)

    def test_prepare_writes_deterministic_public_pairs_and_private_key(self):
        v1, v2 = blind_response_sets()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = prepare_blind_bundle(v1, v2, root / "first", seed=20260718)
            second = prepare_blind_bundle(v1, v2, root / "second", seed=20260718)

            self.assertEqual(first.pairs_path.read_bytes(), second.pairs_path.read_bytes())
            self.assertEqual(first.answer_key_path.read_bytes(), second.answer_key_path.read_bytes())
            self.assertEqual(0o600, first.answer_key_path.stat().st_mode & 0o777)
            self.assertEqual(
                {"answer-key.json", "pairs.jsonl"},
                {path.name for path in first.output_dir.iterdir()},
            )
            public_rows = [
                json.loads(line)
                for line in first.pairs_path.read_text("utf-8").splitlines()
            ]
            self.assertTrue(public_rows)
            self.assertTrue(
                all(
                    set(row) == {"pairId", "question", "answerA", "answerB"}
                    for row in public_rows
                )
            )

            loaded = load_blind_bundle(first.pairs_path, first.answer_key_path)
            self.assertEqual(build_blind_pairs(v1, v2, seed=20260718), loaded)
            with self.assertRaisesRegex(BuildError, "已存在"):
                prepare_blind_bundle(v1, v2, first.output_dir, seed=20260718)

    def test_blind_score_counts_ties_in_all_pair_denominator(self):
        v1, v2 = blind_response_sets()
        pairs = build_blind_pairs(v1, v2, seed=20260718)
        choices = []
        for index, pair in enumerate(pairs):
            if index < 14:
                choice = "A" if pair.version_a == "v2" else "B"
            elif index < 18:
                choice = "A" if pair.version_a == "v1" else "B"
            else:
                choice = "TIE"
            choices.append({"pairId": pair.pair_id, "choice": choice})

        report = score_blind_choices(pairs, choices)

        self.assertEqual((14, 20), counts(report.v2_wins))
        self.assertEqual((4, 20), counts(report.v2_losses))
        self.assertEqual((2, 20), counts(report.ties))
        self.assertEqual(0.7, report.v2_wins.rate)
        self.assertTrue(report.passed)
        report_json = json.dumps(report.to_dict(), ensure_ascii=False, sort_keys=True)
        self.assertNotIn("answerA", report_json)
        self.assertNotIn("answerB", report_json)
        self.assertNotIn("versionA", report_json)
        self.assertNotIn("versionB", report_json)

    def test_choices_reject_duplicate_missing_extra_and_invalid_values(self):
        v1, v2 = blind_response_sets()
        pairs = build_blind_pairs(v1, v2, seed=20260718)
        choices = [{"pairId": pair.pair_id, "choice": "TIE"} for pair in pairs]

        with self.assertRaisesRegex(BuildError, "重复"):
            score_blind_choices(pairs, choices + [choices[0]])
        with self.assertRaisesRegex(BuildError, "缺失"):
            score_blind_choices(pairs, choices[:-1])
        with self.assertRaisesRegex(BuildError, "额外"):
            score_blind_choices(pairs, choices + [{"pairId": "pair-extra", "choice": "A"}])
        invalid = [*choices]
        invalid[0] = {**invalid[0], "choice": "V2"}
        with self.assertRaisesRegex(BuildError, "A、B 或 TIE"):
            score_blind_choices(pairs, invalid)

    def test_choice_loader_rejects_self_reported_scores(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "choices.jsonl"
            path.write_text(
                json.dumps(
                    {"pairId": "pair-0001", "choice": "A", "score": 1},
                    ensure_ascii=False,
                )
                + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(BuildError, "不支持字段"):
                load_blind_choices(path)

    def test_answer_key_rejects_duplicate_case_ids(self):
        v1, v2 = blind_response_sets()
        with tempfile.TemporaryDirectory() as directory:
            bundle = prepare_blind_bundle(v1, v2, Path(directory) / "blind")
            key = json.loads(bundle.answer_key_path.read_text("utf-8"))
            key["pairs"][1]["caseId"] = key["pairs"][0]["caseId"]
            bundle.answer_key_path.write_bytes(canonical_json_bytes(key) + b"\n")
            bundle.answer_key_path.chmod(0o600)

            with self.assertRaisesRegex(BuildError, "重复 caseId"):
                load_blind_bundle(bundle.pairs_path, bundle.answer_key_path)

    def test_answer_key_read_is_bounded_if_file_grows_after_open(self):
        raw = (
            canonical_json_bytes(
                {
                    "pairs": [{} for _ in range(20)],
                    "schemaVersion": 1,
                    "seed": 20260718,
                }
            )
            + b"\n"
        )
        fake_info = SimpleNamespace(st_mode=0o100600, st_size=0)
        with (
            patch(
                "tools.agent_builder.persona_benchmark.MAX_BENCHMARK_TOTAL_BYTES",
                len(raw) - 1,
            ),
            patch(
                "tools.agent_builder.persona_benchmark._open_regular_binary",
                return_value=(BytesIO(raw), fake_info),
            ),
            self.assertRaisesRegex(BuildError, "大小上限"),
        ):
            _read_answer_key(Path("growing-answer-key.json"))


class PersonaBenchmarkCliTest(unittest.TestCase):
    def test_benchmark_score_cli_is_canonical_and_refuses_existing_output(self):
        cases, responses = passing_benchmark_rows()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            responses_path = root / "responses.jsonl"
            output_path = root / "report.json"
            write_jsonl(cases_path, cases)
            write_jsonl(responses_path, responses)

            code, stdout, stderr = run_cli(
                "benchmark-score",
                str(cases_path),
                "--responses",
                str(responses_path),
            )
            self.assertEqual(0, code, stderr)
            payload = json.loads(stdout)
            self.assertTrue(payload["passed"])
            self.assertNotIn("Answer for", stdout)

            code, stdout, stderr = run_cli(
                "benchmark-score",
                str(cases_path),
                "--responses",
                str(responses_path),
                "--output",
                str(output_path),
            )
            self.assertEqual(0, code, stderr)
            self.assertEqual(str(output_path), stdout.strip())
            self.assertEqual(canonical_json_bytes(payload) + b"\n", output_path.read_bytes())

            original = output_path.read_bytes()
            code, _, stderr = run_cli(
                "benchmark-score",
                str(cases_path),
                "--responses",
                str(responses_path),
                "--output",
                str(output_path),
            )
            self.assertEqual(1, code)
            self.assertIn("拒绝覆盖", stderr)
            self.assertEqual(original, output_path.read_bytes())

    def test_benchmark_score_quality_failure_is_reported_but_parse_failure_is_not_published(self):
        cases, responses = passing_benchmark_rows()
        responses[1] = {**responses[1], "evidenceIds": []}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            responses_path = root / "responses.jsonl"
            quality_report = root / "quality-failed.json"
            malformed_report = root / "malformed.json"
            write_jsonl(cases_path, cases)
            write_jsonl(responses_path, responses)

            code, _, stderr = run_cli(
                "benchmark-score",
                str(cases_path),
                "--responses",
                str(responses_path),
                "--output",
                str(quality_report),
            )
            self.assertEqual(2, code, stderr)
            self.assertFalse(json.loads(quality_report.read_text("utf-8"))["passed"])

            responses_path.write_bytes(b"\xff\n")
            code, _, stderr = run_cli(
                "benchmark-score",
                str(cases_path),
                "--responses",
                str(responses_path),
                "--output",
                str(malformed_report),
            )
            self.assertEqual(1, code)
            self.assertIn("UTF-8", stderr)
            self.assertFalse(malformed_report.exists())

    def test_blind_prepare_and_score_cli_keep_mapping_private_and_ties_in_denominator(self):
        v1, v2 = blind_response_sets()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            v1_path = root / "v1.jsonl"
            v2_path = root / "v2.jsonl"
            blind_dir = root / "blind"
            choices_path = root / "choices.jsonl"
            report_path = root / "blind-report.json"
            tie_report_path = root / "tie-report.json"
            write_jsonl(v1_path, v1)
            write_jsonl(v2_path, v2)

            code, stdout, stderr = run_cli(
                "benchmark-blind",
                "prepare",
                "--v1",
                str(v1_path),
                "--v2",
                str(v2_path),
                "--output",
                str(blind_dir),
                "--seed",
                "20260718",
            )
            self.assertEqual(0, code, stderr)
            self.assertEqual(str(blind_dir), stdout.strip())
            pairs = load_blind_bundle(
                blind_dir / "pairs.jsonl",
                blind_dir / "answer-key.json",
            )
            choices = [
                {
                    "pairId": pair.pair_id,
                    "choice": "A" if pair.version_a == "v2" else "B",
                }
                for pair in pairs
            ]
            write_jsonl(choices_path, choices)

            code, stdout, stderr = run_cli(
                "benchmark-blind",
                "score",
                "--pairs",
                str(blind_dir / "pairs.jsonl"),
                "--answer-key",
                str(blind_dir / "answer-key.json"),
                "--choices",
                str(choices_path),
                "--output",
                str(report_path),
            )
            self.assertEqual(0, code, stderr)
            self.assertEqual(str(report_path), stdout.strip())
            report = json.loads(report_path.read_text("utf-8"))
            self.assertEqual(20, report["v2Wins"]["denominator"])
            self.assertEqual(20, report["v2Wins"]["numerator"])
            self.assertNotIn("pairId", json.dumps(report))
            self.assertNotIn("versionA", json.dumps(report))

            write_jsonl(
                choices_path,
                [{"pairId": pair.pair_id, "choice": "TIE"} for pair in pairs],
            )
            code, _, stderr = run_cli(
                "benchmark-blind",
                "score",
                "--pairs",
                str(blind_dir / "pairs.jsonl"),
                "--answer-key",
                str(blind_dir / "answer-key.json"),
                "--choices",
                str(choices_path),
                "--output",
                str(tie_report_path),
            )
            self.assertEqual(2, code, stderr)
            tie_report = json.loads(tie_report_path.read_text("utf-8"))
            self.assertEqual(20, tie_report["ties"]["numerator"])
            self.assertEqual(20, tie_report["v2Wins"]["denominator"])

    def test_blind_score_parse_failure_leaves_no_partial_report(self):
        v1, v2 = blind_response_sets()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            blind_dir = prepare_blind_bundle(v1, v2, root / "blind").output_dir
            choices_path = root / "choices.jsonl"
            report_path = root / "report.json"
            write_jsonl(choices_path, [{"pairId": "pair-0001", "choice": "A"}])

            code, _, stderr = run_cli(
                "benchmark-blind",
                "score",
                "--pairs",
                str(blind_dir / "pairs.jsonl"),
                "--answer-key",
                str(blind_dir / "answer-key.json"),
                "--choices",
                str(choices_path),
                "--output",
                str(report_path),
            )

            self.assertEqual(1, code)
            self.assertIn("缺失", stderr)
            self.assertFalse(report_path.exists())


class PersonaBenchmarkFixtureTest(unittest.TestCase):
    def test_committed_fixture_has_stable_ids_minimum_counts_and_passes_scorer(self):
        fixture_root = Path(__file__).parent / "fixtures"
        cases_path = fixture_root / "persona-regression.jsonl"
        responses_path = fixture_root / "persona-regression-passing-responses.jsonl"

        cases = load_benchmark_cases(cases_path)
        responses = load_benchmark_responses(responses_path)
        report = score_benchmark(cases, responses)

        expected_ids = (
            ["grounding-001"]
            + [f"stance-{index:03d}" for index in range(1, 31)]
            + [f"voice-{index:03d}" for index in range(1, 21)]
            + [f"temporal-{index:03d}" for index in range(1, 13)]
            + [f"continuity-{index:03d}" for index in range(1, 21)]
            + [f"blind-{index:03d}" for index in range(1, 21)]
        )
        self.assertEqual(expected_ids, [item.case_id for item in cases])
        self.assertEqual(expected_ids, [item.case_id for item in responses])
        self.assertEqual(103, report.case_count)
        self.assertEqual((1, 1), metric_counts(report, "grounding"))
        self.assertEqual((30, 30), metric_counts(report, "stance"))
        self.assertEqual((20, 20), metric_counts(report, "voice"))
        self.assertEqual((12, 12), metric_counts(report, "temporal"))
        self.assertEqual((20, 20), metric_counts(report, "continuity"))
        self.assertTrue(report.passed)

        for path in (cases_path, responses_path):
            for line in path.read_bytes().splitlines():
                self.assertEqual(
                    line,
                    canonical_json_bytes(json.loads(line.decode("utf-8"))),
                )


def case(
    case_id,
    category,
    *,
    evidence=None,
    periods=None,
    required_recall=None,
    forbidden_recall=None,
    forbidden_patterns=None,
):
    return {
        "id": case_id,
        "category": category,
        "question": f"Question for {case_id}",
        "requiredEvidence": evidence or [],
        "requiredPeriods": periods or [],
        "requiredRecallKeys": required_recall or [],
        "forbiddenRecallKeys": forbidden_recall or [],
        "forbiddenPatterns": forbidden_patterns or [],
    }


def response(case_id, *, answer="Answer", evidence=None, recalled=None):
    return {
        "caseId": case_id,
        "answer": answer,
        "evidenceIds": evidence or [],
        "recalledFactKeys": recalled or [],
    }


def counts(metric):
    return metric.numerator, metric.denominator


def metric_counts(report, name):
    return counts(report.metrics[name])


def blind_response_sets():
    v1 = []
    v2 = []
    for index in range(1, MIN_BLIND_PAIR_COUNT + 1):
        case_id = f"blind-{index:03d}"
        question = f"Blind question {index}"
        v1.append(blind_response(case_id, question, f"Old answer {index}"))
        v2.append(blind_response(case_id, question, f"New answer {index}"))
    return v1, v2


def blind_response(case_id, question, answer):
    return {
        "caseId": case_id,
        "question": question,
        "answer": answer,
        "evidenceIds": [],
        "recalledFactKeys": [],
    }


def passing_benchmark_rows():
    cases = [case("grounding-001", "grounding", evidence=["e-grounding-001"])]
    responses = [response("grounding-001", evidence=["e-grounding-001"])]
    for index in range(1, 31):
        case_id = f"stance-{index:03d}"
        evidence_id = f"e-{case_id}"
        cases.append(case(case_id, "stance", evidence=[evidence_id]))
        responses.append(response(case_id, answer=f"Answer for {case_id}", evidence=[evidence_id]))
    for index in range(1, 21):
        case_id = f"voice-{index:03d}"
        cases.append(case(case_id, "voice", forbidden_patterns=["current data unavailable"]))
        responses.append(response(case_id, answer=f"Answer for {case_id}"))
    for index in range(1, 13):
        case_id = f"temporal-{index:03d}"
        evidence_id = f"e-{case_id}"
        cases.append(
            case(
                case_id,
                "temporal",
                evidence=[evidence_id],
                periods=[f"period-{index:03d}"],
                forbidden_patterns=["today this history is current"],
            )
        )
        responses.append(response(case_id, answer=f"Answer for {case_id}", evidence=[evidence_id]))
    for index in range(1, 21):
        case_id = f"continuity-{index:03d}"
        recall_key = f"recall-{index:03d}"
        cases.append(
            case(
                case_id,
                "continuity",
                required_recall=[recall_key],
                forbidden_recall=[f"other-agent-{index:03d}"],
            )
        )
        responses.append(response(case_id, answer=f"Answer for {case_id}", recalled=[recall_key]))
    for index in range(1, 21):
        case_id = f"blind-{index:03d}"
        cases.append(case(case_id, "blind"))
        responses.append(response(case_id, answer=f"Answer for {case_id}"))
    return cases, responses


def write_jsonl(path, rows):
    path.write_bytes(
        b"".join(canonical_json_bytes(row) + b"\n" for row in rows)
    )


def run_cli(*arguments):
    stdout = StringIO()
    stderr = StringIO()
    try:
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = agent_builder_main(list(arguments))
    except SystemExit as error:
        code = int(error.code)
    return code, stdout.getvalue(), stderr.getvalue()


if __name__ == "__main__":
    unittest.main()
