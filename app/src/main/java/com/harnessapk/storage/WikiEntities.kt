package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "wikis",
    indices = [Index("activeVersion")],
)
data class WikiEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val description: String,
    val activeVersion: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "wiki_versions",
    primaryKeys = ["wikiId", "version"],
    foreignKeys = [
        ForeignKey(
            entity = WikiEntity::class,
            parentColumns = ["id"],
            childColumns = ["wikiId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["wikiId", "version"], unique = true),
        Index("publisherFingerprint"),
        Index(value = ["wikiId", "enabledForNewConversations"]),
    ],
)
data class WikiVersionEntity(
    val wikiId: String,
    val version: Int,
    val contentPath: String,
    val schemaVersion: Int,
    val contentHash: String,
    val packageHash: String,
    val publisherKeyId: String,
    val publisherFingerprint: String,
    val manifestJson: String,
    val sizeBytes: Long,
    val enabledForNewConversations: Boolean,
    val state: String,
    val installedAt: Long,
    val invalidReason: String? = null,
)
