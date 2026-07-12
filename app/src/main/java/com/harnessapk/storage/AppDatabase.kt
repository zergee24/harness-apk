package com.harnessapk.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageAttachmentEntity::class,
        MessagePartEntity::class,
        ProviderProfileEntity::class,
        ConversationMemoryEntity::class,
        ChatExecutionEntryEntity::class,
        ConversationMarkdownLinkEntity::class,
        MarkdownChangeDraftEntity::class,
        MarkdownChangeDraftItemEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao
    abstract fun messagePartDao(): MessagePartDao
    abstract fun providerProfileDao(): ProviderProfileDao
    abstract fun conversationMemoryDao(): ConversationMemoryDao
    abstract fun chatExecutionEntryDao(): ChatExecutionEntryDao
    abstract fun conversationMarkdownLinkDao(): ConversationMarkdownLinkDao
    abstract fun markdownChangeDraftDao(): MarkdownChangeDraftDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_memory (
                        conversationId TEXT NOT NULL PRIMARY KEY,
                        summary TEXT NOT NULL,
                        coveredThroughMessageId TEXT,
                        coveredThroughCreatedAt INTEGER NOT NULL,
                        compressedMessageCount INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN availableModels TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptOriginal TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptOptimized TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptFinal TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN nativeWebSearchMode TEXT NOT NULL DEFAULT 'DISABLED'",
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN projectId TEXT")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_parts (
                        id TEXT NOT NULL PRIMARY KEY,
                        messageId TEXT NOT NULL,
                        partIndex INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        metadataJson TEXT NOT NULL,
                        stable INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_parts_messageId ON message_parts(messageId)",
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_message_parts_messageId_partIndex
                    ON message_parts(messageId, partIndex)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN customHeadersJson TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN customBodyJson TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_execution_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        userMessageId TEXT NOT NULL,
                        assistantMessageId TEXT,
                        targetAssistantMessageId TEXT,
                        sequence INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        providerId TEXT,
                        model TEXT,
                        reasoningEffort TEXT NOT NULL,
                        requestContextJson TEXT NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE,
                        FOREIGN KEY(userMessageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_execution_entries_conversationId ON chat_execution_entries(conversationId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_execution_entries_userMessageId ON chat_execution_entries(userMessageId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_execution_entries_conversationId_sequence ON chat_execution_entries(conversationId, sequence)",
                )
            }
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_markdown_links (
                        conversationId TEXT NOT NULL,
                        projectId TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        linkedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(conversationId, projectId, relativePath),
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_markdown_links_conversationId ON conversation_markdown_links(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_markdown_links_projectId ON conversation_markdown_links(projectId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS markdown_change_drafts (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        projectId TEXT NOT NULL,
                        sourceUserMessageId TEXT NOT NULL,
                        assistantMessageId TEXT,
                        status TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        rawResponse TEXT,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_drafts_conversationId ON markdown_change_drafts(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_drafts_projectId ON markdown_change_drafts(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_drafts_sourceUserMessageId ON markdown_change_drafts(sourceUserMessageId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS markdown_change_draft_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        draftId TEXT NOT NULL,
                        itemIndex INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        title TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        proposedMarkdown TEXT NOT NULL,
                        retained INTEGER NOT NULL,
                        baselineSha256 TEXT,
                        expectedAbsent INTEGER NOT NULL,
                        applyStatus TEXT,
                        applyErrorMessage TEXT,
                        FOREIGN KEY(draftId) REFERENCES markdown_change_drafts(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_draft_items_draftId ON markdown_change_draft_items(draftId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_markdown_change_draft_items_draftId_itemIndex ON markdown_change_draft_items(draftId, itemIndex)")
            }
        }
    }
}
