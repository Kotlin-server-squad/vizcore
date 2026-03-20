package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.RetentionPolicy
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureCompression()
    configureHTTP()
    configureAuth()
    configureMonitoring()
    configureSerialization()
    configureErrorHandling()
    configureRouting()
    configureSessionLifecycle()
}

fun Application.configureSessionLifecycle() {
    val config = environment.config

    val maxAgeMs = config.propertyOrNull("session.maxAgeMs")?.getString()?.toLongOrNull() ?: 3_600_000L
    val maxCount = config.propertyOrNull("session.maxCount")?.getString()?.toIntOrNull() ?: 100
    val checkIntervalMs = config.propertyOrNull("session.checkIntervalMs")?.getString()?.toLongOrNull() ?: 60_000L

    val retentionScope = CoroutineScope(SupervisorJob())
    val retentionPolicy = RetentionPolicy(
        maxSessionAgeMs = maxAgeMs,
        maxSessions = maxCount,
        checkIntervalMs = checkIntervalMs,
    )

    retentionPolicy.start(retentionScope, SessionManager)
    logger.info("Session lifecycle configured: maxAge={}ms, maxCount={}, checkInterval={}ms", maxAgeMs, maxCount, checkIntervalMs)

    monitor.subscribe(ApplicationStopped) {
        logger.info("Application stopping — cleaning up sessions")
        retentionPolicy.stop()
        SessionManager.clearAll()
        retentionScope.cancel()
        logger.info("Session cleanup complete")
    }
}
