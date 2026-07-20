import tempfile
import unittest
from pathlib import Path

from tools.agent_builder.builder import validate_workspace_v2
from tools.agent_builder.install_planner import choose_install_profiles, plan_corpus_shards
from tools.agent_builder.tests.fixture_v2 import build_complete_v2_fixture


class CompleteV2FixtureTest(unittest.TestCase):
    def test_builds_publishable_nine_asset_fixture_with_all_profiles_and_optional_corpus(self):
        source = Path("app/src/test/resources/agent/source.md").resolve()
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory) / "workspace"

            build_complete_v2_fixture(source, workspace)

            report = validate_workspace_v2(workspace)
            self.assertTrue(report.publishable, report.errors)
            assets = (
                "persona.md",
                "identity.json",
                "voice.json",
                "worldview.jsonl",
                "episodes.jsonl",
                "concepts.json",
                "examples.jsonl",
                "openers.json",
                "eval.jsonl",
            )
            self.assertTrue(all((workspace / "agent" / name).read_text("utf-8").strip() for name in assets))
            plan = choose_install_profiles(plan_corpus_shards(workspace))
            self.assertEqual(["lite", "balanced", "complete", "source"], [profile.profile_id for profile in plan.profiles])
            self.assertTrue(any(package.install_class == "optional" for package in plan.packages))


if __name__ == "__main__":
    unittest.main()
