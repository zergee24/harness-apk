import sqlite3
import tempfile
import unittest
from pathlib import Path

from tools.wiki_builder.sqlite_schema import (
    CONTENT_SCHEMA_VERSION,
    REQUIRED_FTS_TABLES,
    REQUIRED_TABLES,
    create_content_database,
    validate_sqlite_shape,
)


class SqliteSchemaTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_database_has_required_tables_indices_and_fts4_channels(self):
        path = self.root / "content.sqlite"
        connection = create_content_database(path)
        try:
            names = {
                row[0]
                for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type IN ('table', 'index')"
                )
            }
            self.assertTrue(REQUIRED_TABLES.issubset(names))
            self.assertTrue(REQUIRED_FTS_TABLES.issubset(names))
            self.assertTrue(
                {
                    "index_sections_document_ordinal",
                    "index_sections_parent_ordinal",
                    "index_chunks_section_ordinal",
                    "index_terms_concept_key",
                    "index_annotations_kind",
                    "index_links_target",
                }.issubset(names)
            )
            self.assertEqual(
                CONTENT_SCHEMA_VERSION,
                connection.execute("PRAGMA user_version").fetchone()[0],
            )
            validate_sqlite_shape(connection)
        finally:
            connection.close()

    def test_fts4_finds_pretokenized_chinese_ngrams(self):
        connection = create_content_database(self.root / "fts.sqlite")
        try:
            connection.execute(
                "INSERT INTO chunks_original_fts(chunk_id, original_text, original_ngrams) VALUES (?, ?, ?)",
                ("chunk-1", "司马光", "司马 马光 司马光"),
            )

            row = connection.execute(
                "SELECT chunk_id FROM chunks_original_fts WHERE chunks_original_fts MATCH ?",
                ("司马",),
            ).fetchone()

            self.assertEqual(("chunk-1",), row)
        finally:
            connection.close()

    def test_shape_validation_rejects_wrong_version_trigger_and_missing_table(self):
        connection = create_content_database(self.root / "invalid.sqlite")
        try:
            connection.execute("PRAGMA user_version=2")
            with self.assertRaisesRegex(ValueError, "user_version"):
                validate_sqlite_shape(connection)
            connection.execute(f"PRAGMA user_version={CONTENT_SCHEMA_VERSION}")
            connection.execute(
                "CREATE TRIGGER forbidden AFTER INSERT ON documents BEGIN SELECT 1; END"
            )
            with self.assertRaisesRegex(ValueError, "trigger"):
                validate_sqlite_shape(connection)
            connection.execute("DROP TRIGGER forbidden")
            connection.execute("DROP TABLE links")
            with self.assertRaisesRegex(ValueError, "links"):
                validate_sqlite_shape(connection)
        finally:
            connection.close()

    def test_creation_refuses_to_replace_existing_database(self):
        path = self.root / "existing.sqlite"
        path.write_bytes(b"keep")

        with self.assertRaises(FileExistsError):
            create_content_database(path)

        self.assertEqual(b"keep", path.read_bytes())

    def test_foreign_keys_reject_orphan_chunks(self):
        connection = create_content_database(self.root / "foreign-key.sqlite")
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute(
                    """
                    INSERT INTO chunks(
                        chunk_id, section_id, ordinal, original_text, normalized_text,
                        original_ngrams, normalized_ngrams, locator_json, content_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    ("chunk", "missing", 0, "原文", "原文", "原文", "原文", "{}", "0" * 64),
                )
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
