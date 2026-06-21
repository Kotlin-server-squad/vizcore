package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

private val moduleLogger = LoggerFactory.getLogger("com.jh.proj.coroutineviz.ApplicationModule")

/**
 * Set when `storage.type=database`: the connected Exposed [Database] handle.
 * `configureRouting()` reads it to build the DB-backed share service (Plan 04).
 * Absent in memory mode (sharing requires persistence per ADR-019).
 */
val DatabaseKey = AttributeKey<Database>("Database")

/**
 * The [RateLimitName] scope for the public shared read (SHAR-02, D-12). The route
 * `GET /api/shared/{token}` is wrapped in `rateLimit(RateLimitName(SHARED_RATE_LIMIT_NAME))`.
 */
const val SHARED_RATE_LIMIT_NAME = "shared"

/** True when the shared-read [RateLimit] scope was installed (config-gated). */
val SharedRateLimitEnabledKey = AttributeKey<Boolean>("SharedRateLimitEnabled")

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

    configureRateLimit()

    configureRouting()
}

/**
 * Install the per-IP [RateLimit] scope for the public shared read (SHAR-02, D-12).
 *
 * The bucket is keyed on `origin.remoteHost` (the client IP). Behind a reverse
 * proxy, install `XForwardedHeaders` so `remoteHost` is the real client and not
 * the proxy — otherwise ALL viewers share one bucket. That is a DEPLOY config
 * (not wired here). The limit value is read at install time; changing it requires
 * a restart (consistent with the auth/storage toggles).
 */
private fun Application.configureRateLimit() {
    val cfg = environment.config
    val enabled = cfg.propertyOrNull("share.rateLimit.enabled")?.getString()?.toBoolean() ?: true
    attributes.put(SharedRateLimitEnabledKey, enabled)
    if (!enabled) {
        moduleLogger.info("Shared-read rate limiting disabled (share.rateLimit.enabled=false)")
        return
    }
    val rpm = cfg.propertyOrNull("share.rateLimit.requestsPerMinute")?.getString()?.toIntOrNull() ?: 60

    install(RateLimit) {
        register(RateLimitName(SHARED_RATE_LIMIT_NAME)) {
            rateLimiter(limit = rpm, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteHost }
        }
    }
    moduleLogger.info("Shared-read rate limiting enabled (perIp={}/min)", rpm)
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
