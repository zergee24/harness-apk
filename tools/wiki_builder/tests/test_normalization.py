import unittest

from tools.wiki_builder.normalization import (
    NORMALIZATION_MAP_HASH,
    NORMALIZATION_VERSION,
    chinese_ngrams,
    normalize_for_search,
    original_chinese_ngrams,
)


class NormalizationTest(unittest.TestCase):
    def test_normalization_is_versioned_and_intentionally_limited(self):
        self.assertEqual(1, NORMALIZATION_VERSION)
        self.assertRegex(NORMALIZATION_MAP_HASH, r"^[0-9a-f]{64}$")
        self.assertEqual("司马光曰臣闻天子之职莫大于礼", normalize_for_search(
            "司馬光曰：臣聞天子之職，莫大於禮。"
        ))

    def test_ngrams_are_sorted_unique_and_keep_original_variant_channel(self):
        self.assertEqual(
            ("司马", "司马光", "马光"),
            chinese_ngrams("司馬光"),
        )
        self.assertEqual(
            ("司馬", "司馬光", "馬光"),
            original_chinese_ngrams("司馬光"),
        )

    def test_ngrams_ignore_punctuation_only_tokens(self):
        self.assertEqual((), chinese_ngrams("A-1"))
        self.assertEqual(("甲A", "甲A1"), chinese_ngrams("甲 A-1"))


if __name__ == "__main__":
    unittest.main()
