from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


class BuildError(RuntimeError):
    pass


@dataclass(frozen=True)
class ExtractedSection:
    location: str
    text: str


@dataclass(frozen=True)
class ExtractedDocument:
    title: str
    source_path: Path
    source_hash: str
    sections: list[ExtractedSection]


@dataclass(frozen=True)
class BuildReport:
    publishable: bool
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    metrics: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class PackResult:
    agent_package: Path
    corpus_packages: list[Path]
    source_packages: list[Path]
    bundle_package: Path
    report_path: Path
