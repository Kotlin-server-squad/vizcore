package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

private val moduleLogger = LoggerFactory.getLogger("com.jh.proj.coroutineviz.ApplicationModule")

/**
 * Set when `storage.type=database`: the connected Exposed [Database] handle.
 * `configureRouting()` reads it to build the DB-backed share service (Plan 04).
 * Absent in memory mode (sharing requires persistence per ADR-019).
 */
val DatabaseKey = AttributeKey<Database>("Database")

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
    val maxEvents =
        environment.config.propertyOrNull("session.maxEvents")
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
        // Expose the handle so configureRouting() can build the DB-backed ShareService (Plan 04).
        attributes.put(DatabaseKey, db)
        moduleLogger.info("Persistence enabled (storage.type=database)")

        // PERS-03: DB-aware retention runs ONLY when persistence is on (the in-memory
        // RetentionPolicy covers memory mode separately — do not double-wire). The loop
        // launches in the application coroutine scope (NEVER GlobalScope; CLAUDE.md) and
        // is stopped on ApplicationStopping.
        startDbRetention(db)

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

/**
 * Construct and start the [DbRetentionPolicy] using the ADR-015 retention config
 * keys (`storage.retention.*`, with ADR-015 defaults), launching in the
 * application coroutine scope and stopping it on `ApplicationStopping`.
 */
private fun Application.startDbRetention(db: org.jetbrains.exposed.v1.jdbc.Database) {
    val cfg = environment.config
    val maxAgeDays =
        cfg.propertyOrNull("storage.retention.maxAgeDays")?.getString()?.toIntOrNull() ?: 30
    val maxEventsPerSession =
        cfg.propertyOrNull("storage.retention.maxEventsPerSession")?.getString()?.toIntOrNull() ?: 100_000
    val cleanupIntervalMinutes =
        cfg.propertyOrNull("storage.retention.cleanupIntervalMinutes")?.getString()?.toLongOrNull() ?: 60

    val policy =
        DbRetentionPolicy(
            db = db,
            maxAgeDays = maxAgeDays,
            maxEventsPerSession = maxEventsPerSession,
            cleanupIntervalMinutes = cleanupIntervalMinutes,
        )
    policy.start(this)
    monitor.subscribe(ApplicationStopping) { policy.stop() }
    moduleLogger.info(
        "DB retention wired (maxAgeDays={}, maxEventsPerSession={}, intervalMin={})",
        maxAgeDays,
        maxEventsPerSession,
        cleanupIntervalMinutes,
    )
}
