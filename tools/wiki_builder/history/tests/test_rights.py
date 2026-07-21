import unittest

from tools.wiki_builder.history.rights import (
    RightsConfirmation,
    RightsError,
    verify_build_rights,
)
from tools.wiki_builder.history.source_inventory import SourceLock


def fixture_lock() -> SourceLock:
    return SourceLock.from_dict(
        {
            "type": "hwiki-history-source-lock",
            "schemaVersion": 1,
            "sources": [
                {
                    "sourceId": "twenty-four-histories",
                    "path": "/sources/twenty",
                    "gitRemote": "https://example.com/twenty.git",
                    "gitRevision": "a" * 40,
                    "dirty": False,
                    "relevantFileCount": 2482,
                    "relevantByteCount": 100,
                    "supportFileCount": 24,
                    "supportByteCount": 10,
                    "treeHash": "1" * 64,
                    "licenses": [],
                },
                {
                    "sourceId": "zizhi-tongjian",
                    "path": "/sources/zizhi",
                    "gitRemote": "https://example.com/zizhi.git",
                    "gitRevision": "b" * 40,
                    "dirty": False,
                    "relevantFileCount": 294,
                    "relevantByteCount": 200,
                    "supportFileCount": 1,
                    "supportByteCount": 20,
                    "treeHash": "2" * 64,
                    "licenses": [
                        {"path": "LICENSE", "sha256": "3" * 64, "sizeBytes": 20}
                    ],
                },
            ],
        }
    )


def private_confirmation() -> RightsConfirmation:
    return RightsConfirmation.from_dict(
        {
            "type": "hwiki-rights-confirmation",
            "schemaVersion": 1,
            "purpose": "private-local-install",
            "distributionAllowed": False,
            "sources": [
                {
                    "sourceId": "twenty-four-histories",
                    "gitRevision": "a" * 40,
                    "userConfirmed": True,
                    "basis": "user-provided local source for private installation",
                    "distributionAllowed": False,
                    "semanticProcessingApproved": False,
                },
                {
                    "sourceId": "zizhi-tongjian",
                    "gitRevision": "b" * 40,
                    "userConfirmed": True,
                    "basis": "user-provided local source for private installation",
                    "distributionAllowed": False,
                    "semanticProcessingApproved": True,
                },
            ],
        }
    )


class RightsTest(unittest.TestCase):
    def test_private_build_requires_explicit_confirmation_for_every_source(self):
        raw = private_confirmation().to_dict()
        raw["sources"] = raw["sources"][:1]
        confirmation = RightsConfirmation.from_dict(raw)

        with self.assertRaisesRegex(RightsError, "zizhi-tongjian"):
            verify_build_rights(confirmation, fixture_lock())

    def test_private_build_accepts_exact_confirmed_revisions(self):
        result = verify_build_rights(private_confirmation(), fixture_lock())

        self.assertEqual("private-local-install", result["purpose"])
        self.assertFalse(result["distributionAllowed"])
        self.assertEqual(
            ["twenty-four-histories", "zizhi-tongjian"], result["verifiedSourceIds"]
        )

    def test_distribution_requires_top_level_source_flags_and_evidence(self):
        with self.assertRaisesRegex(RightsError, "distributionAllowed=false"):
            verify_build_rights(private_confirmation(), fixture_lock(), distribution=True)

        raw = private_confirmation().to_dict()
        raw["distributionAllowed"] = True
        for source in raw["sources"]:
            source["distributionAllowed"] = True
        confirmation = RightsConfirmation.from_dict(raw)
        with self.assertRaisesRegex(RightsError, "evidence"):
            verify_build_rights(confirmation, fixture_lock(), distribution=True)

    def test_revision_mismatch_false_confirmation_and_blank_basis_fail(self):
        for field, value, pattern in (
            ("gitRevision", "c" * 40, "revision|版本"),
            ("userConfirmed", False, "userConfirmed"),
            ("basis", " ", "basis"),
        ):
            with self.subTest(field=field):
                raw = private_confirmation().to_dict()
                raw["sources"][0][field] = value
                confirmation = RightsConfirmation.from_dict(raw)
                with self.assertRaisesRegex(RightsError, pattern):
                    verify_build_rights(confirmation, fixture_lock())

    def test_semantic_processing_requires_explicit_per_source_approval(self):
        with self.assertRaisesRegex(RightsError, "semanticProcessingApproved"):
            verify_build_rights(
                private_confirmation(),
                fixture_lock(),
                semantic_processing=True,
            )

    def test_schema_is_strict_and_never_defaults_user_confirmation(self):
        raw = private_confirmation().to_dict()
        del raw["sources"][0]["userConfirmed"]
        with self.assertRaisesRegex(RightsError, "userConfirmed"):
            RightsConfirmation.from_dict(raw)

        raw = private_confirmation().to_dict()
        raw["confirmedByBuilder"] = True
        with self.assertRaisesRegex(RightsError, "未知字段"):
            RightsConfirmation.from_dict(raw)


if __name__ == "__main__":
    unittest.main()
