"""Shared errors and small value helpers for the Wiki builder."""


class WikiBuildError(ValueError):
    """Raised when source data cannot produce a valid Wiki package."""


class ManifestError(WikiBuildError):
    """Raised when a ``.hwiki`` manifest violates schema v1."""
