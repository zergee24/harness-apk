package com.harnessapk.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownFenceScannerTest {
    @Test
    fun unwrapsSingleLineChineseTextFences() {
        assertEquals(
            "左侧布局",
            unwrapSingleLineGluedTextFence("```text左侧布局```"),
        )
        assertEquals(
            "关系记忆",
            unwrapSingleLineGluedTextFence("~~~TEXT关系记忆~~~"),
        )
    }

    @Test
    fun preservesAsciiAndUnclosedTextFences() {
        assertNull(unwrapSingleLineGluedTextFence("```textplain text```"))
        assertNull(unwrapSingleLineGluedTextFence("```text未闭合"))
    }
}
