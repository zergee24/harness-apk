"""Canonical machine and human build reports for Wiki packages."""

from __future__ import annotations

from pathlib import Path

from tools.package_format import canonical_json_bytes

from .validation import ValidationReport


def build_report_document(
    *,
    manifest: dict[str, object],
    validation: ValidationReport,
    package_name: str,
    package_hash: str,
    package_size: int,
) -> dict[str, object]:
    return {
        "type": "hwiki-build-report",
        "schemaVersion": 1,
        "artifact": {
            "fileName": package_name,
            "sha256": package_hash,
            "sizeBytes": package_size,
        },
        "manifest": manifest,
        "validation": validation.to_dict(),
    }


def write_build_reports(root: Path, report: dict[str, object]) -> tuple[Path, Path]:
    json_path = Path(root) / "build-report.json"
    markdown_path = Path(root) / "build-report.md"
    json_path.write_bytes(canonical_json_bytes(report))
    markdown_path.write_text(_markdown_report(report), encoding="utf-8")
    return json_path, markdown_path


def _markdown_report(report: dict[str, object]) -> str:
    artifact = report["artifact"]
    manifest = report["manifest"]
    validation = report["validation"]
    wiki = manifest["wiki"]
    retrieval = validation["retrieval"]
    category_lines = "\n".join(
        f"- `{category}`: {value:.6f}"
        for category, value in sorted(retrieval["categoryRecall"].items())
    ) or "- 无"
    error_lines = "\n".join(
        f"- `{code}`: {message}"
        for code, message in zip(validation["errorCodes"], validation["errors"])
    ) or "- 无"
    return (
        f"# {wiki['title']} 构建报告\n\n"
        f"- Wiki：`{wiki['id']}` v{wiki['version']}\n"
        f"- 产物：`{artifact['fileName']}`\n"
        f"- 大小：{artifact['sizeBytes']} bytes\n"
        f"- SHA-256：`{artifact['sha256']}`\n"
        f"- 可发布：{'是' if validation['publishable'] else '否'}\n"
        f"- SQLite 完整性：`{validation['integrityCheck']}`\n"
        f"- Recall@20：{retrieval['overallRecallAt20']:.6f}\n\n"
        "## 分类召回\n\n"
        f"{category_lines}\n\n"
        "## 错误\n\n"
        f"{error_lines}\n"
    )


__all__ = ["build_report_document", "write_build_reports"]
