"""Shared errors and value objects for the Wiki builder."""

from dataclasses import dataclass
from pathlib import Path


class WikiBuildError(ValueError):
    """Raised when source data cannot produce a valid Wiki package."""


class ManifestError(WikiBuildError):
    """Raised when a ``.hwiki`` manifest violates schema v1."""


class BuildError(WikiBuildError):
    """Raised when source material cannot produce a valid workspace."""


@dataclass(frozen=True)
class PreparedDocument:
    document_id: str
    title: str
    source_path: Path
    source_hash: str
    size_bytes: int
    ordinal: int
