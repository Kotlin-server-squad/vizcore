package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The address anonymous rate limits count a caller by (`rateLimit.trustedProxyHops`, env
 * `TRUSTED_PROXY_HOPS`): the socket's unless the operator says how many proxies stand in front,
 * and then only the entry of `X-Forwarded-For` that the outermost of those proxies wrote.
 */
class ClientAddressTest {
    @BeforeEach
    fun setUp() = SessionManager.clearAll()

    @AfterEach
    fun tearDown() = SessionManager.clearAll()

    @Test
    fun `one proxy - the last entry is the visitor, whatever the visitor sent before it`() {
        assertEquals("203.0.113.7", forwardedClientAddress(listOf("203.0.113.7"), hops = 1))
        assertEquals("203.0.113.7", forwardedClientAddress(listOf("1.1.1.1, 2.2.2.2, 203.0.113.7"), hops = 1))
    }

    @Test
    fun `two proxies - the entry before the last is the visitor`() {
        assertEquals("203.0.113.7", forwardedClientAddress(listOf("1.1.1.1, 203.0.113.7, 10.0.0.2"), hops = 2))
    }

    @Test
    fun `repeated headers count as one list`() {
        assertEquals("203.0.113.7", forwardedClientAddress(listOf("1.1.1.1", "203.0.113.7, 10.0.0.2"), hops = 2))
        assertEquals("10.0.0.2", forwardedClientAddress(listOf("1.1.1.1", "203.0.113.7", "10.0.0.2"), hops = 1))
    }

    @Test
    fun `IPv6 addresses are read, in one spelling`() {
        assertEquals("2001:db8::a", forwardedClientAddress(listOf("2001:DB8::A"), hops = 1))
        assertEquals("2001:db8::a", forwardedClientAddress(listOf("[2001:db8::a]"), hops = 1))
    }

    @Test
    fun `no answer when the header is missing, too short or not an address`() {
        assertNull(forwardedClientAddress(emptyList(), hops = 1))
        assertNull(forwardedClientAddress(listOf(""), hops = 1))
        assertNull(forwardedClientAddress(listOf("203.0.113.7"), hops = 2))
        assertNull(forwardedClientAddress(listOf("unknown"), hops = 1))
        assertNull(forwardedClientAddress(listOf("x".repeat(500)), hops = 1))
        assertNull(forwardedClientAddress(listOf("203.0.113.7"), hops = 0))
    }

    private fun ApplicationTestBuilder.backend(vararg settings: Pair<String, String>) {
        environment {
            config = MapApplicationConfig("rateLimit.sessionCreate.requestsPerMinute" to "2", *settings)
        }
        application { module() }
    }

    private suspend fun ApplicationTestBuilder.createSession(forwardedFor: String): HttpResponse =
        client.post("/api/sessions?name=s") {
            header("X-Forwarded-For", forwardedFor)
        }

    @Test
    fun `by default the forwarded header is not read - every caller on one socket address shares a bucket`() =
        testApplication {
            backend()
            assertEquals(HttpStatusCode.Created, createSession("203.0.113.1").status)
            assertEquals(HttpStatusCode.Created, createSession("203.0.113.2").status)
            // A third caller cannot buy a bucket of its own by naming another address.
            assertEquals(HttpStatusCode.TooManyRequests, createSession("203.0.113.3").status)
        }

    @Test
    fun `behind a trusted proxy each visitor has a bucket of their own`() =
        testApplication {
            backend("rateLimit.trustedProxyHops" to "1")
            assertEquals(HttpStatusCode.Created, createSession("203.0.113.1").status)
            assertEquals(HttpStatusCode.Created, createSession("203.0.113.1").status)
            assertEquals(HttpStatusCode.TooManyRequests, createSession("203.0.113.1").status)
            // Entries the visitor put in front of the proxy's do not make a new bucket.
            assertEquals(HttpStatusCode.TooManyRequests, createSession("198.51.100.9, 203.0.113.1").status)
            assertEquals(HttpStatusCode.Created, createSession("203.0.113.2").status)
        }

    @Test
    fun `behind a trusted proxy a request without the header is counted by its socket address`() =
        testApplication {
            backend("rateLimit.trustedProxyHops" to "1")
            repeat(2) { assertEquals(HttpStatusCode.Created, client.post("/api/sessions?name=s").status) }
            assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/sessions?name=s").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
        }

    @Test
    fun `a setting that is not a count of proxies trusts nothing`() =
        testApplication {
            backend("rateLimit.trustedProxyHops" to "yes")
            assertEquals(HttpStatusCode.Created, createSession("203.0.113.1").status)
            assertEquals(HttpStatusCode.Created, createSession("203.0.113.2").status)
            assertEquals(HttpStatusCode.TooManyRequests, createSession("203.0.113.3").status)
        }
}
