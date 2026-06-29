package com.jh.coroutinevisualizer.toolwindow

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * URL-format proofs for [VizcoreViewUrl] (the single source of truth for the live-view URL).
 *
 * Verifies the exact `http://127.0.0.1:<port>/?correlation=<corr>` shape plus the defensive
 * URL-encoding of a correlation containing reserved characters. Task 2 ([JcefFallbackTest])
 * additionally proves the JCEF and fallback paths consume this identical string.
 */
class VizcoreViewUrlTest {
    @Test
    fun `builds the loopback view url with the given port and a plain correlation`() {
        assertEquals(
            "http://127.0.0.1:54321/?correlation=corr-abc",
            VizcoreViewUrl.build(54321, "corr-abc"),
        )
    }

    @Test
    fun `url-encodes a correlation containing reserved characters`() {
        // A value with a space and reserved chars must be percent-encoded so it cannot
        // break out of the query string. URLEncoder encodes space as '+', '/' as %2F, '&' as %26.
        assertEquals(
            "http://127.0.0.1:8080/?correlation=a+b%2Fc%26d",
            VizcoreViewUrl.build(8080, "a b/c&d"),
        )
    }
}
