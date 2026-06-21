package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.server.application.*
import org.slf4j.LoggerFactory

private val moduleLogger = LoggerFactory.getLogger("com.jh.proj.coroutineviz.ApplicationModule")

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureCompression()
    configureHTTP()
    configureAuth()
    configureMonitoring()
    configureSerialization()

    // Configure bounded EventStore before any sessions are created (FND-02)
    val maxEvents = environment.config.propertyOrNull("session.maxEvents")
        ?.getString()?.toIntOrNull() ?: 10_000
    SessionManager.configure(maxEventsPerSession = maxEvents)

    configureStorage(maxEvents)

    configureRouting()
}

/**
 * Select the session/event storage backend (PERS-01). `storage.type=database`
 * installs the Exposed store behind the existing SessionStoreInterface seam;
 * the default `memory` keeps the in-memory SessionManager unchanged (D-04a).
 *
 * Persistence does NOT force auth on (D-04b); auth wiring is Plan 02's concern.
 */
private fun Application.configureStorage(maxEvents: Int) {
    val storageType = environment.config.propertyOrNull("storage.type")?.getString() ?: "memory"
    if (storageType.equals("database", ignoreCase = true)) {
        val db = DatabaseFactory.init(environment.config)
        SessionManager.useStore(ExposedSessionStore(db, maxEvents = maxEvents))
        moduleLogger.info("Persistence enabled (storage.type=database)")

        val apiKey = environment.config.propertyOrNull("auth.apiKey")?.getString()
        if (apiKey.isNullOrBlank()) {
            // D-04b: surface the open-access risk, but do not auto-enable auth.
            moduleLogger.warn(
                "Persistence is enabled without an API key configured — " +
                    "persisted sessions are publicly visible until auth lands (Plan 02).",
            )
        }
    } else {
        moduleLogger.info("Persistence disabled (storage.type=memory, in-memory ephemeral)")
    }
}
