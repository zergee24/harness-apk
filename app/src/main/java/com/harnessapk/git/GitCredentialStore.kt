package com.harnessapk.git

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gitSettingsDataStore by preferencesDataStore("git_settings")

data class GitSettings(
    val giteeUsername: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val hasToken: Boolean = false,
) {
    val canAuthenticate: Boolean = giteeUsername.isNotBlank() && hasToken
    val canCommit: Boolean = authorName.isNotBlank() && authorEmail.isNotBlank()
}

data class GitSettingsDraft(
    val giteeUsername: String,
    val token: String?,
    val authorName: String,
    val authorEmail: String,
)

class GitCredentialStore(
    private val context: Context,
    private val cipher: StringCipher,
) {
    val settings: Flow<GitSettings> = context.gitSettingsDataStore.data.map { preferences ->
        GitSettings(
            giteeUsername = preferences[GITEE_USERNAME].orEmpty(),
            authorName = preferences[GIT_AUTHOR_NAME].orEmpty(),
            authorEmail = preferences[GIT_AUTHOR_EMAIL].orEmpty(),
            hasToken = preferences[GIT_TOKEN_CIPHER_TEXT].isNullOrBlank().not() &&
                preferences[GIT_TOKEN_IV].isNullOrBlank().not(),
        )
    }

    suspend fun saveSettings(draft: GitSettingsDraft) {
        context.gitSettingsDataStore.edit { preferences ->
            preferences[GITEE_USERNAME] = draft.giteeUsername.trim()
            preferences[GIT_AUTHOR_NAME] = draft.authorName.trim()
            preferences[GIT_AUTHOR_EMAIL] = draft.authorEmail.trim()
            draft.token?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
                val encrypted = cipher.encrypt(token)
                preferences[GIT_TOKEN_CIPHER_TEXT] = encoder.encodeToString(encrypted.cipherText)
                preferences[GIT_TOKEN_IV] = encoder.encodeToString(encrypted.initializationVector)
            }
        }
    }

    suspend fun clearToken() {
        context.gitSettingsDataStore.edit { preferences ->
            preferences.remove(GIT_TOKEN_CIPHER_TEXT)
            preferences.remove(GIT_TOKEN_IV)
        }
    }

    suspend fun credentials(): GitCredentials? {
        val preferences = context.gitSettingsDataStore.data.first()
        val username = preferences[GITEE_USERNAME]?.trim().orEmpty()
        val cipherText = preferences[GIT_TOKEN_CIPHER_TEXT]?.takeIf { it.isNotBlank() } ?: return null
        val iv = preferences[GIT_TOKEN_IV]?.takeIf { it.isNotBlank() } ?: return null
        if (username.isBlank()) return null
        return GitCredentials(
            username = username,
            token = cipher.decrypt(
                EncryptedValue(
                    cipherText = decoder.decode(cipherText),
                    initializationVector = decoder.decode(iv),
                ),
            ),
        )
    }

    suspend fun commitAuthor(): GitCommitAuthor {
        val preferences = context.gitSettingsDataStore.data.first()
        val name = preferences[GIT_AUTHOR_NAME]?.trim().orEmpty()
        val email = preferences[GIT_AUTHOR_EMAIL]?.trim().orEmpty()
        require(name.isNotBlank()) { "Git 作者名不能为空" }
        require(email.isNotBlank()) { "Git 作者邮箱不能为空" }
        return GitCommitAuthor(name = name, email = email)
    }

    private companion object {
        val GITEE_USERNAME = stringPreferencesKey("gitee_username")
        val GIT_AUTHOR_NAME = stringPreferencesKey("git_author_name")
        val GIT_AUTHOR_EMAIL = stringPreferencesKey("git_author_email")
        val GIT_TOKEN_CIPHER_TEXT = stringPreferencesKey("git_token_cipher_text")
        val GIT_TOKEN_IV = stringPreferencesKey("git_token_iv")
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
    }
}
