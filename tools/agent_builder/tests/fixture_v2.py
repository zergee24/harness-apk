from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

from tools.agent_builder.builder import prepare_workspace_v2
from tools.agent_builder.evaluation import MINIMUM_EVAL_COUNTS
from tools.agent_builder.install_planner import choose_install_profiles, plan_corpus_shards


def build_complete_v2_fixture(source: Path, workspace: Path) -> Path:
    source = Path(source).expanduser().resolve()
    workspace = Path(workspace).expanduser().resolve()
    inputs = workspace.parent / f"{workspace.name}-inputs"
    inputs.mkdir(parents=True, exist_ok=False)
    direct_source = inputs / "source-direct.md"
    conversation_source = inputs / "source-conversation.md"
    sections = _markdown_sections(source.read_text("utf-8"))
    if len(sections) < 3:
        raise ValueError("V2 fixture source 至少需要三个 Markdown 一级章节")
    direct_source.write_text("\n\n".join((sections[0], sections[2])) + "\n", encoding="utf-8")
    conversation_source.write_text(sections[1] + "\n", encoding="utf-8")
    catalog = inputs / "source-catalog.json"
    _write_json(
        catalog,
        {
            "schemaVersion": 2,
            "sources": [
                {
                    "sourceId": "fixture-direct",
                    "fileName": direct_source.name,
                    "title": "调查与结论",
                    "genre": "speech",
                    "authorship": "direct",
                    "period": "1926",
                },
                {
                    "sourceId": "fixture-conversation",
                    "fileName": conversation_source.name,
                    "title": "实践与检验",
                    "genre": "conversation",
                    "authorship": "edited_direct",
                    "period": "1927",
                },
            ],
        },
    )
    prepare_workspace_v2(
        [direct_source, conversation_source],
        workspace,
        agent_id="fixture.researcher",
        name="资料研究者",
        version=2,
        source_catalog_path=catalog,
    )
    chunks = [
        json.loads(line)
        for line in (workspace / "corpora/index/chunks.jsonl").read_text("utf-8").splitlines()
        if line.strip()
    ]
    direct = next(row for row in chunks if row["sourceId"] == "fixture-direct" and "调查" in row["text"])
    conversation = next(row for row in chunks if row["sourceId"] == "fixture-conversation")
    direct_id = direct["id"]
    conversation_id = conversation["id"]
    agent = workspace / "agent"
    (agent / "persona.md").write_text(
        "我以第一人称进行基于资料的模拟，只依据已安装证据回答；证据不足时明确说明未知。\n",
        encoding="utf-8",
    )
    _write_json(
        agent / "identity.json",
        {
            "selfNames": ["资料研究者"],
            "timeHorizon": "1926-1927",
            "roles": ["调查者", "实践检验者"],
            "relationships": [
                {
                    "subject": "事实与判断",
                    "relation": "先调查事实，再形成并修正判断",
                    "period": "1926",
                    "evidence": [direct_id],
                }
            ],
        },
    )
    _write_json(
        agent / "voice.json",
        {
            "defaultForm": "先说明证据，再给出结论",
            "sentenceRhythm": ["短句", "先事实后判断"],
            "rhetoricalMoves": ["对照新旧事实", "指出证据边界"],
            "preferredTerms": ["调查", "实践", "证据"],
            "avoidPatterns": ["把资料外知识写成人物立场"],
            "evidence": [direct_id, conversation_id],
        },
    )
    _write_jsonl(
        agent / "worldview.jsonl",
        [{
            "id": "stance-investigation",
            "topic": "调查与结论",
            "statement": "先收集事实，再形成结论",
            "conditions": ["资料足以支持判断"],
            "period": "1926",
            "aliases": ["调查优先"],
            "confidence": 1.0,
            "evidence": [direct_id],
        }],
    )
    _write_jsonl(
        agent / "episodes.jsonl",
        [{
            "id": "episode-practice",
            "period": "1927",
            "location": "实践现场",
            "participants": ["研究者"],
            "summary": "判断回到实践中接受新事实检验。",
            "meaning": "冲突出现时保留差异并修正结论。",
            "evidence": [conversation_id],
        }],
    )
    _write_json(
        agent / "concepts.json",
        {"concepts": [{
            "id": "concept-evidence-boundary",
            "name": "证据边界",
            "aliases": ["资料边界"],
            "keywords": ["证据", "不足"],
            "evidence": [direct_id],
        }]},
    )
    _write_jsonl(
        agent / "examples.jsonl",
        [{
            "id": "example-grounded-answer",
            "intent": "询问如何形成结论",
            "user": "资料不足时应如何判断？",
            "assistant": "我会先说明证据不足，不补写完整结论。",
            "styleTags": ["证据优先", "明确边界"],
            "generationType": "synthesized",
            "evidence": [direct_id],
        }],
    )
    _write_json(
        agent / "openers.json",
        {"default": "请给出要核验的问题。", "alternatives": ["我们先看已有证据。"]},
    )
    eval_rows = []
    for category, count in MINIMUM_EVAL_COUNTS.items():
        for index in range(count):
            if category in {"diversity", "global"}:
                evidence = [direct_id, conversation_id]
                period = "1926"
                question = "调查 实践 结论 检验"
            elif index % 2 == 0:
                evidence = [direct_id]
                period = "1926"
                question = "收集"
            else:
                evidence = [conversation_id]
                period = "1927"
                question = "冲突"
            eval_rows.append({
                "id": f"{category}-{index:03d}",
                "category": category,
                "question": question,
                "period": period,
                "expectedEvidence": evidence,
                "corpusId": "unassigned",
            })
    _write_jsonl(agent / "eval.jsonl", eval_rows)
    _assign_declared_corpus_questions(workspace, eval_rows)
    _write_test_key(workspace / "test-key.pem")
    return workspace


def _assign_declared_corpus_questions(workspace: Path, eval_rows: list[dict[str, object]]) -> None:
    chunk_rows = {
        row["id"]: row
        for row in (
            json.loads(line)
            for line in (workspace / "corpora/index/chunks.jsonl").read_text("utf-8").splitlines()
            if line.strip()
        )
    }
    for _ in range(6):
        shards = plan_corpus_shards(workspace)
        plan = choose_install_profiles(shards)
        shard_chunks = {shard.package_id: set(shard.chunk_ids) for shard in shards}
        for row in eval_rows:
            row["corpusId"] = "unassigned"
        used: set[int] = set()
        changed = False
        for package in plan.packages:
            if package.install_class not in {"required", "recommended"}:
                continue
            candidates = shard_chunks[package.package_id]
            matching = [
                index
                for index, row in enumerate(eval_rows)
                if index not in used
                and (evidence := set(row["expectedEvidence"]))
                and evidence.issubset(candidates)
            ]
            if len(matching) < 2:
                chunk_id = sorted(candidates)[0]
                chunk = chunk_rows[chunk_id]
                replacements = [
                    index
                    for index, row in enumerate(eval_rows)
                    if index not in used and row["category"] == "grounding"
                ][: 2 - len(matching)]
                if len(replacements) != 2 - len(matching):
                    raise ValueError(f"fixture 无法为 {package.package_id} 分配两道真实归属题")
                for index in replacements:
                    eval_rows[index]["expectedEvidence"] = [chunk_id]
                    eval_rows[index]["period"] = chunk["period"]
                    eval_rows[index]["question"] = chunk["text"]
                matching.extend(replacements)
                changed = True
            for index in matching[:2]:
                eval_rows[index]["corpusId"] = package.package_id
                used.add(index)
        _write_jsonl(workspace / "agent/eval.jsonl", eval_rows)
        if not changed:
            return
    raise ValueError("fixture required/recommended corpus 归属规划未稳定")


def _markdown_sections(text: str) -> list[str]:
    sections: list[str] = []
    current: list[str] = []
    for line in text.splitlines():
        if line.startswith("# ") and current:
            sections.append("\n".join(current).strip())
            current = []
        current.append(line)
    if current:
        sections.append("\n".join(current).strip())
    return [section for section in sections if section]


def _write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")


def _write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False, sort_keys=True) for row in rows) + "\n",
        encoding="utf-8",
    )


def _write_test_key(path: Path) -> None:
    path.write_bytes(
        Ed25519PrivateKey.generate().private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
    )
    path.chmod(0o600)


def main() -> int:
    parser = argparse.ArgumentParser(description="构建 build 目录中的完整 V2 Android 验收 fixture")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--dist", type=Path)
    parser.add_argument("--reset", action="store_true")
    args = parser.parse_args()
    workspace = args.workspace.expanduser().resolve()
    inputs = workspace.parent / f"{workspace.name}-inputs"
    if args.reset:
        shutil.rmtree(workspace, ignore_errors=True)
        shutil.rmtree(inputs, ignore_errors=True)
        if args.dist is not None:
            shutil.rmtree(args.dist.expanduser().resolve(), ignore_errors=True)
    build_complete_v2_fixture(args.source, workspace)
    print(workspace)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
