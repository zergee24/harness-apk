import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

from tools.agent_builder.builder import (
    BuildError,
    pack_workspace,
    prepare_workspace,
    validate_workspace,
)


class AgentBuilderTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.source = self.root / "source.md"
        self.source.write_text(
            "# 调查研究\n\n没有调查，没有发言权。研究问题必须从事实出发。\n\n"
            "# 实践\n\n认识来源于实践，并回到实践中接受检验。",
            encoding="utf-8",
        )
        self.private_key_path = self.root / "publisher-key.pem"
        self.private_key_path.write_bytes(
            Ed25519PrivateKey.generate().private_bytes(
                Encoding.PEM,
                PrivateFormat.PKCS8,
                NoEncryption(),
            ),
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_prepare_is_deterministic_and_generates_chinese_ngrams(self):
        first = prepare_workspace(
            [self.source],
            self.root / "first",
            agent_id="fixture.researcher",
            name="资料研究代理",
            version=1,
        )
        second = prepare_workspace(
            [self.source],
            self.root / "second",
            agent_id="fixture.researcher",
            name="资料研究代理",
            version=1,
        )

        first_chunks = (first / "corpora" / "fixture.researcher.corpus" / "chunks.jsonl").read_bytes()
        second_chunks = (second / "corpora" / "fixture.researcher.corpus" / "chunks.jsonl").read_bytes()
        chunk = json.loads(first_chunks.decode("utf-8").splitlines()[0])

        self.assertEqual(first_chunks, second_chunks)
        self.assertIn("调查", chunk["ngrams"])
        self.assertRegex(chunk["id"], r"^chunk-[0-9a-f]{16}$")

    def test_prepare_extracts_epub_in_spine_order(self):
        epub = self.root / "book.epub"
        with zipfile.ZipFile(epub, "w") as archive:
            archive.writestr(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            )
            archive.writestr(
                "OEBPS/content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            )
            archive.writestr("OEBPS/one.xhtml", "<html><body><h1>第一章</h1><p>先读这一章。</p></body></html>")
            archive.writestr("OEBPS/two.xhtml", "<html><body><h1>第二章</h1><p>再读这一章。</p></body></html>")

        workspace = prepare_workspace(
            [epub],
            self.root / "epub-workspace",
            agent_id="fixture.epub",
            name="EPUB 代理",
            version=1,
        )
        chunks = (workspace / "corpora" / "fixture.epub.corpus" / "chunks.jsonl").read_text("utf-8")

        self.assertLess(chunks.index("第一章"), chunks.index("第二章"))

    def test_prepare_rejects_source_without_extractable_text(self):
        empty_source = self.root / "empty.txt"
        empty_source.write_text("  \n\n", encoding="utf-8")

        with self.assertRaisesRegex(BuildError, "没有可提取文本"):
            prepare_workspace(
                [empty_source],
                self.root / "empty-workspace",
                agent_id="fixture.empty",
                name="空代理",
                version=1,
            )

    def test_validate_rejects_unknown_evidence_chunk(self):
        workspace = prepare_workspace(
            [self.source],
            self.root / "invalid-workspace",
            agent_id="fixture.invalid",
            name="无效代理",
            version=1,
        )
        (workspace / "agent" / "worldview.jsonl").write_text(
            json.dumps(
                {
                    "id": "view-1",
                    "statement": "从事实出发",
                    "evidence": ["chunk-missing"],
                    "scope": "方法论",
                    "confidence": 1.0,
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )

        report = validate_workspace(workspace)

        self.assertFalse(report.publishable)
        self.assertTrue(any("chunk-missing" in error for error in report.errors))

    def test_pack_emits_all_package_layers_and_is_deterministic(self):
        workspace = prepare_workspace(
            [self.source],
            self.root / "workspace",
            agent_id="fixture.researcher",
            name="资料研究代理",
            version=1,
        )
        self._complete_semantics(workspace)
        first = pack_workspace(
            workspace,
            self.root / "dist-first",
            self.private_key_path,
            include_sources=True,
        )
        second = pack_workspace(
            workspace,
            self.root / "dist-second",
            self.private_key_path,
            include_sources=True,
        )

        self.assertTrue(first.agent_package.name.endswith(".hagent"))
        self.assertEqual(1, len(first.corpus_packages))
        self.assertEqual(1, len(first.source_packages))
        self.assertTrue(first.bundle_package.name.endswith(".hbundle"))
        self.assertEqual(self._sha256(first.bundle_package), self._sha256(second.bundle_package))
        with zipfile.ZipFile(first.bundle_package) as archive:
            self.assertEqual(
                ["Ed25519", "checksums.json"],
                [
                    json.loads(archive.read("signature.json"))["algorithm"],
                    json.loads(archive.read("signature.json"))["signedFile"],
                ],
            )
            self.assertIn("bundle-manifest.json", archive.namelist())
            self.assertIn("agent/persona.md", archive.namelist())

    def _complete_semantics(self, workspace: Path):
        chunk_path = workspace / "corpora" / "fixture.researcher.corpus" / "chunks.jsonl"
        chunk_ids = [json.loads(line)["id"] for line in chunk_path.read_text("utf-8").splitlines()]
        evidence = [chunk_ids[0]]
        (workspace / "agent" / "persona.md").write_text(
            "我是基于所选资料构建的研究模拟代理。只根据资料回答。",
            encoding="utf-8",
        )
        (workspace / "agent" / "worldview.jsonl").write_text(
            json.dumps(
                {
                    "id": "view-investigation",
                    "statement": "调查应先于结论",
                    "evidence": evidence,
                    "scope": "方法论",
                    "confidence": 1.0,
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        eval_rows = [
            {
                "id": f"eval-{index:02d}",
                "question": "为什么应当先调查事实",
                "expectedEvidence": evidence,
            }
            for index in range(20)
        ]
        (workspace / "agent" / "eval.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in eval_rows),
            encoding="utf-8",
        )

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
