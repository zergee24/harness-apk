import copy
import unittest

from tools.wiki_builder.schema import ManifestError, WikiManifest


def fixture_manifest() -> dict[str, object]:
    return {
        "type": "hwiki",
        "schemaVersion": 1,
        "wiki": {
            "id": "fixture.history",
            "version": 1,
            "title": "史料测试库",
            "language": ["zh-Hant", "zh-Hans"],
            "description": "用于协议测试",
            "contentHash": "0" * 64,
        },
        "publisher": {"keyId": "fixture", "name": "测试发布者"},
        "capabilities": {
            "sourceHierarchy": True,
            "sourceSearch": True,
            "hierarchicalSummaries": True,
            "termIndex": True,
            "temporalAnnotations": True,
            "crossWikiLinks": False,
            "generatedPages": "none",
            "claimGraph": False,
            "vectorIndex": False,
            "sourceAttachments": False,
        },
        "conceptNamespace": "fixture-v1",
        "conceptRegistryHash": "0" * 64,
        "builder": {
            "name": "harness-wiki-builder",
            "version": "1",
            "profile": "generic-v1",
        },
    }


class WikiManifestTest(unittest.TestCase):
    def test_manifest_round_trips_every_v1_field(self):
        raw = fixture_manifest()

        manifest = WikiManifest.from_dict(raw)

        self.assertEqual("fixture.history", manifest.wiki_id)
        self.assertEqual(("zh-Hant", "zh-Hans"), manifest.languages)
        self.assertEqual("none", manifest.capabilities.generated_pages)
        self.assertEqual(raw, manifest.to_dict())

    def test_manifest_rejects_unsupported_or_unsafe_values(self):
        cases = {
            "type": ("type", "hagent"),
            "schema": ("schemaVersion", 2),
            "wiki-id": ("wiki.id", "../history"),
            "version": ("wiki.version", 0),
            "content-hash": ("wiki.contentHash", "bad"),
            "language": ("wiki.language", []),
            "generated-pages": ("capabilities.generatedPages", "all"),
            "vector": ("capabilities.vectorIndex", True),
            "attachments": ("capabilities.sourceAttachments", True),
            "namespace": ("conceptNamespace", "bad namespace"),
            "registry-hash": ("conceptRegistryHash", "f" * 63),
        }
        for label, (path, value) in cases.items():
            with self.subTest(label=label):
                raw = fixture_manifest()
                set_path(raw, path, value)
                with self.assertRaises(ManifestError):
                    WikiManifest.from_dict(raw)

    def test_manifest_rejects_unknown_fields_and_boolean_integer(self):
        unknown = fixture_manifest()
        unknown["wiki"]["extra"] = "not allowed"  # type: ignore[index]
        with self.assertRaisesRegex(ManifestError, "未知字段"):
            WikiManifest.from_dict(unknown)

        boolean_version = fixture_manifest()
        boolean_version["wiki"]["version"] = True  # type: ignore[index]
        with self.assertRaisesRegex(ManifestError, "正整数"):
            WikiManifest.from_dict(boolean_version)

    def test_manifest_rejects_duplicate_languages(self):
        raw = copy.deepcopy(fixture_manifest())
        raw["wiki"]["language"] = ["zh-Hans", "zh-Hans"]  # type: ignore[index]

        with self.assertRaisesRegex(ManifestError, "language"):
            WikiManifest.from_dict(raw)


def set_path(root: dict[str, object], path: str, value: object) -> None:
    segments = path.split(".")
    current = root
    for segment in segments[:-1]:
        current = current[segment]  # type: ignore[assignment]
    current[segments[-1]] = value


if __name__ == "__main__":
    unittest.main()
