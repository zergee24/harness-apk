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
        ProviderProfileEntity::class,
        ConversationMemoryEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao
    abstract fun providerProfileDao(): ProviderProfileDao
    abstract fun conversationMemoryDao(): ConversationMemoryDao

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
    }
}
