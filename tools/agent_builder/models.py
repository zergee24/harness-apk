import re
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


class BuildError(RuntimeError):
    pass


@dataclass(frozen=True)
class ExtractedSection:
    location: str
    text: str
    conflict_key: str = ""


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


@dataclass(frozen=True)
class CorpusShard:
    package_id: str
    package_type: str
    title: str
    install_class: str
    source_ids: tuple[str, ...] = ()
    source_hashes: tuple[str, ...] = ()
    periods: tuple[str, ...] = ()
    top_level_ids: tuple[str, ...] = ()
    chunk_ids: tuple[str, ...] = ()
    node_ids: tuple[str, ...] = ()
    coverage: frozenset[str] = frozenset()
    dependencies: tuple[str, ...] = ()
    file_name: str = ""
    size_bytes: int | None = None
    sha256: str | None = None

    @classmethod
    def source(
        cls,
        package_id: str,
        source_ids: tuple[str, ...],
        source_hashes: tuple[str, ...],
        *,
        file_name: str = "",
        size_bytes: int | None = None,
        sha256: str | None = None,
    ) -> "CorpusShard":
        return cls(
            package_id=package_id,
            package_type="hsource",
            title=package_id,
            install_class="source",
            source_ids=source_ids,
            source_hashes=source_hashes,
            file_name=file_name,
            size_bytes=size_bytes,
            sha256=sha256,
        )

    def with_artifact(self, file_name: str, size_bytes: int, sha256: str) -> "CorpusShard":
        return CorpusShard(
            **{
                **self.__dict__,
                "file_name": file_name,
                "size_bytes": size_bytes,
                "sha256": sha256,
            }
        )


@dataclass(frozen=True)
class InstallPackage:
    package_id: str
    package_type: str
    file_name: str
    install_class: str
    dependencies: tuple[str, ...]
    size_bytes: int | None
    sha256: str | None

    def to_dict(self, *, require_artifact: bool = False) -> dict[str, Any]:
        if require_artifact and (
            not self.file_name
            or isinstance(self.size_bytes, bool)
            or not isinstance(self.size_bytes, int)
            or self.size_bytes <= 0
            or not isinstance(self.sha256, str)
            or re.fullmatch(r"[0-9a-f]{64}", self.sha256) is None
        ):
            raise BuildError(f"安装包缺少真实大小或哈希：{self.package_id}")
        return {
            "dependencies": list(self.dependencies),
            "fileName": self.file_name,
            "id": self.package_id,
            "installClass": self.install_class,
            "sha256": self.sha256,
            "sizeBytes": self.size_bytes,
            "type": self.package_type,
        }


@dataclass(frozen=True)
class InstallProfile:
    profile_id: str
    package_ids: tuple[str, ...]
    recommended: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.profile_id,
            "packageIds": list(self.package_ids),
            "recommended": self.recommended,
        }


@dataclass(frozen=True)
class InstallPlan:
    packages: tuple[InstallPackage, ...]
    profiles: tuple[InstallProfile, ...]
    required_corpus_ids: tuple[str, ...]
    recommended_profile_id: str = "balanced"

    def profile(self, profile_id: str) -> InstallProfile:
        for profile in self.profiles:
            if profile.profile_id == profile_id:
                return profile
        raise BuildError(f"未知 profile：{profile_id}")

    def to_dict(self, *, require_artifacts: bool = False) -> dict[str, Any]:
        package_ids = [package.package_id for package in self.packages]
        file_names = [package.file_name for package in self.packages if package.file_name]
        if len(package_ids) != len(set(package_ids)):
            raise BuildError("安装计划存在重复 package ID")
        if len(file_names) != len(set(file_names)):
            raise BuildError("安装计划存在重复 fileName")
        known = set(package_ids)
        if tuple(profile.profile_id for profile in self.profiles) != (
            "lite",
            "balanced",
            "complete",
            "source",
        ):
            raise BuildError("安装计划 profiles 必须依次为 lite、balanced、complete、source")
        recommended = [profile.profile_id for profile in self.profiles if profile.recommended]
        if recommended != [self.recommended_profile_id] or self.recommended_profile_id != "balanced":
            raise BuildError("balanced 必须是唯一推荐 profile")
        for package in self.packages:
            if package.package_type not in {"hcorpus", "hsource"}:
                raise BuildError(f"安装包类型无效：{package.package_id}")
            if package.install_class not in {"required", "recommended", "optional", "source"}:
                raise BuildError(f"安装包 installClass 无效：{package.package_id}")
            if any(dependency not in known for dependency in package.dependencies):
                raise BuildError(f"安装包依赖不存在：{package.package_id}")
        for required in self.required_corpus_ids:
            package = next(
                (item for item in self.packages if item.package_id == required),
                None,
            )
            if package is None or package.package_type != "hcorpus":
                raise BuildError(f"缺少 required corpus：{required}")
            if package.install_class != "required":
                raise BuildError(f"required corpus installClass 不一致：{required}")
        if self.required_corpus_ids != ("core-evidence",):
            raise BuildError("required corpus 集合必须且只能是 core-evidence")
        profile_by_id = {profile.profile_id: profile for profile in self.profiles}
        corpus_ids = {
            package.package_id
            for package in self.packages
            if package.package_type == "hcorpus"
        }
        source_ids = {
            package.package_id
            for package in self.packages
            if package.package_type == "hsource"
        }
        if set(profile_by_id["lite"].package_ids) != set(self.required_corpus_ids):
            raise BuildError("lite profile 集合必须等于 required corpora")
        balanced_ids = set(profile_by_id["balanced"].package_ids)
        if (
            not set(self.required_corpus_ids).issubset(balanced_ids)
            or not balanced_ids.issubset(corpus_ids)
        ):
            raise BuildError("balanced profile 集合必须是包含 required 的 corpus 子集")
        if set(profile_by_id["complete"].package_ids) != corpus_ids:
            raise BuildError("complete profile 集合必须包含全部非 source corpus")
        if set(profile_by_id["source"].package_ids) != corpus_ids | source_ids:
            raise BuildError("source profile 集合必须包含 complete 和全部 source")
        for profile in self.profiles:
            if len(profile.package_ids) != len(set(profile.package_ids)):
                raise BuildError(f"profile 存在重复 package ID：{profile.profile_id}")
            if any(package_id not in known for package_id in profile.package_ids):
                raise BuildError(f"profile 引用了未声明安装包：{profile.profile_id}")
            if any(required not in profile.package_ids for required in self.required_corpus_ids):
                raise BuildError(f"profile 缺少 required corpus：{profile.profile_id}")
            selected = set(profile.package_ids)
            for package_id in profile.package_ids:
                package = next(item for item in self.packages if item.package_id == package_id)
                if any(dependency not in selected for dependency in package.dependencies):
                    raise BuildError(
                        f"profile 依赖不完整：{profile.profile_id} / {package_id}"
                    )
        for package in self.packages:
            expected_class = (
                "source"
                if package.package_type == "hsource"
                else (
                    "required"
                    if package.package_id in self.required_corpus_ids
                    else (
                        "recommended"
                        if package.package_id in balanced_ids
                        else "optional"
                    )
                )
            )
            if package.install_class != expected_class:
                raise BuildError(
                    f"安装包 installClass 与 profile 集合不一致：{package.package_id}"
                )
        return {
            "packages": [
                package.to_dict(require_artifact=require_artifacts)
                for package in self.packages
            ],
            "profiles": [profile.to_dict() for profile in self.profiles],
            "recommendedProfileId": self.recommended_profile_id,
            "requiredCorpusIds": list(self.required_corpus_ids),
            "schemaVersion": 2,
        }
