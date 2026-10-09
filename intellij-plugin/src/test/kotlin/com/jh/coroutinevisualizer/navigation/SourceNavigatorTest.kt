package com.jh.coroutinevisualizer.navigation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceNavigatorTest {
    @Test fun `no candidates returns null`() {
        assertNull(SourceNavigator.bestMatch("X.kt", null, emptyList()))
    }

    @Test fun `single candidate resolves`() {
        assertEquals("/a/OrderService.kt", SourceNavigator.bestMatch("OrderService.kt", null, listOf("/a/OrderService.kt")))
    }

    @Test fun `className disambiguates among candidates`() {
        val candidates = listOf("/x/foo/OrderService.kt", "/x/com/jh/OrderService.kt")
        assertEquals("/x/com/jh/OrderService.kt", SourceNavigator.bestMatch("OrderService.kt", "com.jh.OrderService", candidates))
    }

    @Test fun `ambiguous without className falls back to first`() {
        val candidates = listOf("/a/X.kt", "/b/X.kt")
        assertEquals("/a/X.kt", SourceNavigator.bestMatch("X.kt", null, candidates))
    }

    @Test fun `className with no package match falls back to first`() {
        val candidates = listOf("/a/X.kt", "/b/X.kt")
        assertEquals("/a/X.kt", SourceNavigator.bestMatch("X.kt", "totally.different.X", candidates))
    }
}
