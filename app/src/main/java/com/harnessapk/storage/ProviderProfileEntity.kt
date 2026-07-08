package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_profiles")
data class ProviderProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val apiKeyAlias: String,
    val encryptedApiKey: ByteArray?,
    val apiKeyIv: ByteArray?,
    val defaultModel: String,
    val availableModels: String,
    val defaultVisionModel: String?,
    val supportsVision: Boolean,
    val nativeWebSearchMode: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
