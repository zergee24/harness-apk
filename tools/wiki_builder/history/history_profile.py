"""Versioned constants for offline history semantic enrichment."""

PROFILE_ID = "history-retrieval-v1"
PROMPT_VERSION = "history-enrichment-prompt-v1"
OUTPUT_SCHEMA_ID = "history-enrichment-output-v1"
MAX_SOURCE_CHARS = 18_000
MAX_CONTEXT_HEADING_CHARS = 240
HIGH_CONFIDENCE_IDENTITY = 0.9
LOW_CONFIDENCE_LINK = 0.85

CONCEPT_KINDS = frozenset(
    {
        "person",
        "place",
        "polity",
        "office",
        "era",
        "work",
        "event",
    }
)

__all__ = [
    "CONCEPT_KINDS",
    "HIGH_CONFIDENCE_IDENTITY",
    "LOW_CONFIDENCE_LINK",
    "MAX_CONTEXT_HEADING_CHARS",
    "MAX_SOURCE_CHARS",
    "OUTPUT_SCHEMA_ID",
    "PROFILE_ID",
    "PROMPT_VERSION",
]
