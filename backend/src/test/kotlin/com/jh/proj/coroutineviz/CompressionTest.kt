package com.jh.proj.coroutineviz

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressionTest {
    @Test
    fun `large response includes Content-Encoding gzip when client sends Accept-Encoding gzip`() =
        testApplication {
            application { module() }
            val client = createClient { }

            val response =
                client.get("/api/scenarios") {
                    header(HttpHeaders.AcceptEncoding, "gzip")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        }

    @Test
    fun `small response is not compressed`() =
        testApplication {
            application { module() }
            val client = createClient { }

            val response =
                client.get("/health") {
                    header(HttpHeaders.AcceptEncoding, "gzip")
                }

            // /health flips to 503/DEGRADED under shared-JVM heap pressure (WR-11),
            // which depends on suite ordering. This test's subject is the absence of
            // Content-Encoding on a small body, so accept either verdict (mirrors
            // HealthRoutesTest.assertHealthReachable) instead of a strict 200 that flakes.
            assertTrue(
                response.status == HttpStatusCode.OK || response.status == HttpStatusCode.ServiceUnavailable,
                "Unexpected /health status: ${response.status}",
            )
            val encoding = response.headers[HttpHeaders.ContentEncoding]
            assertNull(encoding, "Small responses should not be compressed")
        }
}
