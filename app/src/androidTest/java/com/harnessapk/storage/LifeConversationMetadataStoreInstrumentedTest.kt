package com.harnessapk.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeConversationMetadataStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val conversationId = "life-metadata-${UUID.randomUUID()}"
    private val failurePreferencesName = "life-metadata-failure-${UUID.randomUUID()}"

    @Before
    fun clearFixture() {
        removeFixture()
    }

    @After
    fun removeFixtureAfterTest() {
        removeFixture()
    }

    @Test
    fun sidecarCommitSurvivesASecondStoreInstance() {
        val first = LifeConversationMetadataStore(context)
        first.recordOrigin(conversationId, LifeConversationOrigin.LIFE_PHOTO)
        first.markUserRetained(conversationId)
        first.setCustomTitle(conversationId, "周末出门清单")
        first.setUndoDeadline(conversationId, 5_000L)

        val reopened = LifeConversationMetadataStore(context)
        val metadata = reopened.get(conversationId)

        assertEquals(LifeConversationOrigin.LIFE_PHOTO, metadata?.origin)
        assertTrue(metadata?.userRetained == true)
        assertEquals("周末出门清单", metadata?.customTitle)
        assertEquals(5_000L, metadata?.undoDeadlineMillis)
    }

    @Test
    fun failedCommitThrowsAndDoesNotPublishEntries() {
        val backingPreferences = context.getSharedPreferences(
            failurePreferencesName,
            Context.MODE_PRIVATE,
        )
        val failingPreferences = CommitFailingSharedPreferences(backingPreferences)
        val store = LifeConversationMetadataStore(
            context = context,
            preferencesOverride = failingPreferences,
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            store.recordOrigin(conversationId, LifeConversationOrigin.LIFE_TEXT)
        }

        assertTrue(failure.message.orEmpty().contains("commit()"))
        assertFalse(store.entries.value.containsKey(conversationId))
        assertEquals(null, backingPreferences.getString("conversation::$conversationId", null))
        assertTrue(backingPreferences.getStringSet("conversation_ids", emptySet()).orEmpty().isEmpty())
    }

    private fun removeFixture() {
        val preferences = context.getSharedPreferences(
            "life_conversation_metadata",
            Context.MODE_PRIVATE,
        )
        val ids = preferences.getStringSet("conversation_ids", emptySet()).orEmpty().toMutableSet()
        ids.remove(conversationId)
        preferences.edit()
            .remove("conversation::$conversationId")
            .putStringSet("conversation_ids", ids)
            .commit()
        context.getSharedPreferences(failurePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}

private class CommitFailingSharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor =
        CommitFailingEditor(delegate.edit())
}

private class CommitFailingEditor(
    private val delegate: SharedPreferences.Editor,
) : SharedPreferences.Editor by delegate {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
        delegate.putString(key, value)
        return this
    }

    override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
        delegate.putStringSet(key, values)
        return this
    }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
        delegate.putInt(key, value)
        return this
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
        delegate.putLong(key, value)
        return this
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
        delegate.putFloat(key, value)
        return this
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
        delegate.putBoolean(key, value)
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        delegate.remove(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        delegate.clear()
        return this
    }

    override fun commit(): Boolean = false
}
