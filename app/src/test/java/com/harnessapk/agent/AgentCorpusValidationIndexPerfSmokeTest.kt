package com.harnessapk.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class AgentCorpusValidationIndexPerfSmokeTest {
    @Test
    fun tenThousandRecordsRoundTrip() {
        val parent = File(System.getProperty("java.io.tmpdir"))
        AgentCorpusValidationIndex(parent).use { index ->
            val start = System.currentTimeMillis()
            val keys = List(10_000) { "chunk:${UUID.randomUUID()}" }
            keys.forEach { key ->
                assertTrue("duplicate insert: $key", index.putUnique(key, encodeIndexFields("src", "period", key)))
            }
            val insertMillis = System.currentTimeMillis() - start
            keys.forEach { key -> assertEquals(3, decodeIndexFields(index.get(key)!!).size) }
            val readMillis = System.currentTimeMillis() - start - insertMillis
            assertEquals(10_000L, index.records())
            assertTrue(index.diskBytes() > 0L)
            assertTrue("insert too slow: ${insertMillis}ms", insertMillis < 15_000)
            assertTrue("read too slow: ${readMillis}ms", readMillis < 15_000)
        }
    }
}
