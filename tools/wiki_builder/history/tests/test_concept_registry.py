import tempfile
import unittest
from pathlib import Path

from tools.package_format import canonical_json_bytes
from tools.wiki_builder.history.concept_registry import (
    install_shared_registry,
    merge_concept_candidates,
    validate_pair_registry,
    write_concept_registry,
)
from tools.wiki_builder.history.tests.helpers import build_history_workspace
from tools.wiki_builder.models import BuildError


class ConceptRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_conflicting_duplicate_key_and_high_confidence_alias_are_rejected(self):
        duplicate = [
            self._candidate("left", "person:sima-guang", "司马光", aliases=["君实"]),
            self._candidate("right", "person:sima-guang", "司马君实", aliases=["君实"]),
        ]
        with self.assertRaisesRegex(BuildError, "conceptKey|重复|冲突"):
            merge_concept_candidates(duplicate, "cn-history-v1")

        aliases = [
            self._candidate("left", "person:sima-guang", "司马光", aliases=["君实"]),
            self._candidate("right", "person:other", "他人", aliases=["君实"]),
        ]
        with self.assertRaisesRegex(BuildError, "别名|alias|冲突"):
            merge_concept_candidates(aliases, "cn-history-v1")

    def test_cross_wiki_high_confidence_identity_requires_one_key(self):
        candidates = [
            self._candidate("left", "person:sima-guang", "司马光"),
            self._candidate("right", "person:si-ma-guang", "司马光"),
        ]
        with self.assertRaisesRegex(BuildError, "跨 Wiki|conceptKey|同一"):
            merge_concept_candidates(candidates, "cn-history-v1")

    def test_low_confidence_candidates_remain_separate_and_unresolved(self):
        candidates = [
            self._candidate(
                "left",
                "place:chang-an-a",
                "长安",
                confidence=0.6,
                review_state="unresolved",
            ),
            self._candidate(
                "right",
                "place:chang-an-b",
                "长安",
                confidence=0.55,
                review_state="unresolved",
            ),
        ]
        registry = merge_concept_candidates(candidates, "cn-history-v1")

        self.assertEqual(2, len(registry))
        self.assertEqual({"unresolved"}, {row["reviewState"] for row in registry})

    def test_shared_registry_install_is_exact_and_pair_hash_mismatch_fails(self):
        left = build_history_workspace(self.root / "left", wiki_id="fixture.left")
        right = build_history_workspace(self.root / "right", wiki_id="fixture.right")
        rows = merge_concept_candidates(
            [self._candidate("left", "person:sima-guang", "司马光")],
            "cn-history-v1",
        )
        registry_path = write_concept_registry(self.root / "registry.jsonl", rows)

        result = install_shared_registry(registry_path, (left, right))

        left_registry = left / "enrichment" / "concept-registry.jsonl"
        right_registry = right / "enrichment" / "concept-registry.jsonl"
        self.assertEqual(registry_path.read_bytes(), left_registry.read_bytes())
        self.assertEqual(left_registry.read_bytes(), right_registry.read_bytes())
        self.assertEqual(result["registryHash"], validate_pair_registry(left, right)["registryHash"])

        right_registry.write_bytes(
            canonical_json_bytes(
                {
                    "conceptKey": "cn-history-v1:person:other",
                    "kind": "person",
                    "canonicalText": "他人",
                    "aliases": [],
                    "reviewState": "reviewed",
                    "metadata": {},
                    "provenance": {"sources": []},
                }
            )
            + b"\n"
        )
        with self.assertRaisesRegex(BuildError, "registry.*hash|哈希|一致"):
            validate_pair_registry(left, right)

    def _candidate(
        self,
        wiki_id: str,
        key_suffix: str,
        canonical_text: str,
        *,
        aliases: list[str] | None = None,
        confidence: float = 0.96,
        review_state: str = "auto-high-confidence",
    ) -> dict[str, object]:
        return {
            "wikiId": wiki_id,
            "conceptKey": f"cn-history-v1:{key_suffix}",
            "kind": key_suffix.split(":", 1)[0],
            "canonicalText": canonical_text,
            "aliases": aliases or [],
            "confidence": confidence,
            "reviewState": review_state,
            "evidence": [f"{wiki_id}-chunk"],
        }


if __name__ == "__main__":
    unittest.main()
