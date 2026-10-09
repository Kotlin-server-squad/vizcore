package com.jh.proj.coroutineviz

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize

fun Application.configureCompression() {
    install(Compression) {
        // SSE (text/event-stream) MUST NEVER be compressed (PERF-03/D-06, T-10-11): a gzip
        // encoder buffers and reframes the byte stream, which breaks the incremental-flush
        // contract EventSource depends on. ContentType.Text.Any below would otherwise MATCH
        // text/event-stream, so the global excludeContentType wins decisively over any match
        // (it is checked first and short-circuits the encoder for the /stream route).
        excludeContentType(ContentType.Text.EventStream)
        gzip {
            priority = 1.0
            minimumSize(1024)
            matchContentType(
                ContentType.Text.Any,
                ContentType.Application.Json,
                ContentType.Application.JavaScript,
            )
        }
        deflate {
            priority = 0.5
            minimumSize(1024)
            matchContentType(
                ContentType.Text.Any,
                ContentType.Application.Json,
                ContentType.Application.JavaScript,
            )
        }
    }
}
