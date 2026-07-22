package com.harnessapk.session

import com.harnessapk.wiki.WikiRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WikiEvidenceCoverageTest {
    @Test
    fun missingComparisonRefsExcludesMissingWikiOutsideTheRequestedComparison() {
        val first = WikiRef("history.24", 2)
        val second = WikiRef("history.zztj", 3)
        val unrelated = WikiRef("history.misc", 1)
        val coverage = WikiEvidenceCoverage(
            requestedComparisonRefs = setOf(first, second),
            missingRefs = setOf(second, unrelated),
        )

        assertEquals(setOf(second), coverage.missingComparisonRefs)
    }

    @Test
    fun noneHasNoComparisonContext() {
        assertFalse(WikiEvidenceCoverage.NONE.hasComparisonContext)
    }
}
