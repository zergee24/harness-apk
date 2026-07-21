"""Versioned, conservative search normalization for schema-v1 Wiki data."""

from __future__ import annotations

import hashlib
import json
import re
import unicodedata

NORMALIZATION_VERSION = 1

# This is a search aid, not a general Traditional-to-Simplified converter.
TRADITIONAL_VARIANT_MAP = {
    "萬": "万",
    "與": "与",
    "東": "东",
    "書": "书",
    "亂": "乱",
    "於": "于",
    "會": "会",
    "傳": "传",
    "體": "体",
    "來": "来",
    "後": "后",
    "從": "从",
    "徵": "征",
    "時": "时",
    "晉": "晋",
    "國": "国",
    "學": "学",
    "將": "将",
    "歲": "岁",
    "漢": "汉",
    "無": "无",
    "為": "为",
    "禮": "礼",
    "紀": "纪",
    "聞": "闻",
    "臺": "台",
    "號": "号",
    "說": "说",
    "軍": "军",
    "長": "长",
    "門": "门",
    "馬": "马",
    "發": "发",
    "職": "职",
}

_PUNCTUATION = (
    "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    "，。！？；：、（）【】《》〈〉「」『』〔〕…—·﹏"
)
_PUNCTUATION_DELETE_TABLE = str.maketrans("", "", _PUNCTUATION)
_TRADITIONAL_TRANSLATION_TABLE = str.maketrans(TRADITIONAL_VARIANT_MAP)
_WHITESPACE_PATTERN = re.compile(r"[\s\u3000]+")
NORMALIZATION_MAP_HASH = hashlib.sha256(
    json.dumps(
        TRADITIONAL_VARIANT_MAP,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
).hexdigest()


def normalize_for_search(text: str) -> str:
    """Return compact NFKC text with the v1 variant map applied."""

    return _compact_for_search(text, translate_variants=True)


def chinese_ngrams(text: str) -> tuple[str, ...]:
    """Return deterministic 2/3-grams from normalized search text."""

    return _ngrams(normalize_for_search(text))


def original_chinese_ngrams(text: str) -> tuple[str, ...]:
    """Return 2/3-grams without changing Traditional character variants."""

    return _ngrams(_compact_for_search(text, translate_variants=False))


def _compact_for_search(text: str, *, translate_variants: bool) -> str:
    value = unicodedata.normalize("NFKC", text)
    if translate_variants:
        value = value.translate(_TRADITIONAL_TRANSLATION_TABLE)
    value = _WHITESPACE_PATTERN.sub("", value)
    return value.translate(_PUNCTUATION_DELETE_TABLE)


def _ngrams(compact: str) -> tuple[str, ...]:
    tokens = {
        compact[index : index + size]
        for size in (2, 3)
        for index in range(max(0, len(compact) - size + 1))
    }
    return tuple(sorted(token for token in tokens if any(_is_cjk(char) for char in token)))


def _is_cjk(character: str) -> bool:
    return "\u3400" <= character <= "\u9fff"


__all__ = [
    "NORMALIZATION_MAP_HASH",
    "NORMALIZATION_VERSION",
    "TRADITIONAL_VARIANT_MAP",
    "chinese_ngrams",
    "normalize_for_search",
    "original_chinese_ngrams",
]
