package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_wiki_mounts",
    primaryKeys = ["conversationId", "wikiId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WikiVersionEntity::class,
            parentColumns = ["wikiId", "version"],
            childColumns = ["wikiId", "wikiVersion"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["wikiId", "wikiVersion"])],
)
data class ConversationWikiMountEntity(
    val conversationId: String,
    val wikiId: String,
    val wikiVersion: Int,
    val enabled: Boolean,
    val mountedAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "wiki_retrieval_runs",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WikiRetrievalRunEntity(
    @PrimaryKey val messageId: String,
    val allowedScopeJson: String,
    val explicitOverrideJson: String?,
    val routerVersion: String,
    val retrieverVersion: String,
    val status: String,
    val candidateCount: Int,
    val evidenceCount: Int,
    val elapsedMillis: Long,
    val errorCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "message_wiki_usages",
    primaryKeys = ["messageId", "wikiId", "wikiVersion"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["wikiId", "wikiVersion"])],
)
data class MessageWikiUsageEntity(
    val messageId: String,
    val wikiId: String,
    val wikiVersion: Int,
    val scoutRank: Int?,
    val deepHitCount: Int,
    val selectedEvidenceCount: Int,
    val enteredContext: Boolean,
)

@Entity(
    tableName = "message_wiki_citations",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId"),
        Index(value = ["messageId", "displayOrdinal"], unique = true),
        Index(value = ["wikiId", "wikiVersion"]),
        Index("chunkId"),
    ],
)
data class MessageWikiCitationEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val displayOrdinal: Int,
    val wikiId: String,
    val wikiVersion: Int,
    val wikiTitle: String,
    val documentId: String,
    val sectionId: String,
    val chunkId: String,
    val sourceTitle: String,
    val sectionPath: String,
    val locatorLabel: String,
    val originalTextSnapshot: String,
    val originalTextSha256: String,
    val answerRangesJson: String,
    val verificationState: String,
    val createdAt: Long,
)
