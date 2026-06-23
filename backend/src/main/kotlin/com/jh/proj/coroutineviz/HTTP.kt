package com.jh.proj.coroutineviz

import com.asyncapi.kotlinasyncapi.context.service.AsyncApiExtension
import com.asyncapi.kotlinasyncapi.ktor.AsyncApiPlugin
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.micrometer.prometheus.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("HTTP")

fun Application.configureHTTP() {
    configureCors()

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("X-XSS-Protection", "1; mode=block")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    }

    // RateLimit is installed once in Application.configureRateLimit() (api +
    // session-create + the config-gated shared scope) — Ktor forbids installing a
    // plugin twice, so it must not also be installed here.

    install(AsyncApiPlugin) {
        extension =
            AsyncApiExtension.builder {
                info {
                    title("Coroutine Visualizer API")
                    version("1.0.0")
                }
            }
    }
    routing {
        swaggerUI(path = "openapi")
    }
    routing {
        openAPI(path = "openapi")
    }
}

// CORS configuration — origins and methods come from application.yaml / env vars. Extracted from
// configureHTTP so each stays within the LongMethod limit.
private fun Application.configureCors() {
    val config = environment.config
    install(CORS) {
        val originsRaw =
            config.propertyOrNull("cors.allowedOrigins")?.getString()
                ?: "http://localhost:3000,http://127.0.0.1:3000"
        val origins = originsRaw.trim('"')
        origins.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { origin ->
            val cleaned = origin.trim('"')
            val host = cleaned.removePrefix("https://").removePrefix("http://")
            if (cleaned.startsWith("https://")) {
                allowHost(host, schemes = listOf("https"))
            } else {
                allowHost(host, schemes = listOf("http"))
            }
        }
        logger.info("CORS origins: $origins")

        val methods =
            config.propertyOrNull("cors.allowedMethods")?.getString()
                ?: "GET,POST,DELETE,OPTIONS"
        methods.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach { method ->
            allowMethod(HttpMethod.parse(method))
        }
        logger.info("CORS methods: $methods")

        // Allow common headers
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-API-Key")

        // Required for SSE (Server-Sent Events)
        allowHeader(HttpHeaders.CacheControl)
        allowHeader(HttpHeaders.Connection)

        // Allow credentials (cookies, authorization headers)
        allowCredentials = true

        // Set max age for preflight requests cache
        maxAgeInSeconds = 3600
    }
}
