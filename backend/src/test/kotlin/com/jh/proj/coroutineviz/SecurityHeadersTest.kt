package com.jh.proj.coroutineviz

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SecurityHeadersTest {
    @Test
    fun `responses include X-Content-Type-Options header`() = testApplication {
        application { module() }

        val response = client.get("/")
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `responses include X-Frame-Options header`() = testApplication {
        application { module() }

        val response = client.get("/")
        assertEquals("DENY", response.headers["X-Frame-Options"])
    }

    @Test
    fun `responses include X-XSS-Protection header`() = testApplication {
        application { module() }

        val response = client.get("/")
        assertEquals("1; mode=block", response.headers["X-XSS-Protection"])
    }

    @Test
    fun `responses include Referrer-Policy header`() = testApplication {
        application { module() }

        val response = client.get("/")
        assertEquals("strict-origin-when-cross-origin", response.headers["Referrer-Policy"])
    }

    @Test
    fun `responses include Permissions-Policy header`() = testApplication {
        application { module() }

        val response = client.get("/")
        assertEquals("camera=(), microphone=(), geolocation=()", response.headers["Permissions-Policy"])
    }

    @Test
    fun `all security headers present on API endpoints`() = testApplication {
        application { module() }

        val response = client.get("/api/sessions")
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("strict-origin-when-cross-origin", response.headers["Referrer-Policy"])
    }
}
