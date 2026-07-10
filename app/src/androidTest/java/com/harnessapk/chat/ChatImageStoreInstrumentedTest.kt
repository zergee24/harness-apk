package com.harnessapk.chat

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.common.AppDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatImageStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val managedDirectory = File(context.filesDir, "chat-images")
    private val source = File(context.cacheDir, "chat-image-store-picker.jpg")
    private val store = ChatImageStore(
        context = context,
        httpClient = OkHttpClient(),
        dispatchers = AppDispatchers(io = Dispatchers.IO),
    )

    @Before
    fun setUp() {
        managedDirectory.deleteRecursively()
        source.delete()
    }

    @After
    fun tearDown() {
        managedDirectory.deleteRecursively()
        source.delete()
    }

    @Test
    fun persistCopiesPickerImageAndReturnsManagedFileProviderUri() = runBlocking {
        source.writeText("picker-bytes")

        val persisted = store.persist(source.toUri(), "image/jpeg")

        assertEquals("content", persisted.uri.scheme)
        assertEquals("${context.packageName}.fileprovider", persisted.uri.authority)
        assertEquals("image/jpeg", persisted.mimeType)
        assertEquals(
            "picker-bytes",
            context.contentResolver.openInputStream(persisted.uri)!!.bufferedReader().use { it.readText() },
        )
        assertTrue(managedDirectory.listFiles()!!.single().extension == "jpg")
    }
}
