package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.RetentionPolicy
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

private val moduleLogger = LoggerFactory.getLogger("com.jh.proj.coroutineviz.ApplicationModule")

// Session-lifecycle defaults (used when not overridden via application.yaml / env).
private const val DEFAULT_SESSION_MAX_AGE_MS = 3_600_000L // 1 hour
private const val DEFAULT_SESSION_MAX_COUNT = 100
private const val DEFAULT_SESSION_CHECK_INTERVAL_MS = 60_000L // 1 minute

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
    io.ktor.server.netty.EngineMain
        .main(args)
}

fun Application.module() {
    configureCompression()
    configureHTTP()
    configureAuth()
    configureMonitoring()
    configureSerialization()

    // Configure bounded EventStore before any sessions are created (FND-02)
    val maxEvents =
        environment.config
            .propertyOrNull("session.maxEvents")
            ?.getString()
            ?.toIntOrNull() ?: 10_000
    SessionManager.configure(maxEventsPerSession = maxEvents)

    configureStorage(maxEvents)

    configureRateLimit()

    configureErrorHandling()
    configureRouting()
    configureSessionLifecycle()
}

fun Application.configureSessionLifecycle() {
    val config = environment.config

    // The in-memory RetentionPolicy evicts via SessionManager.deleteSession, which in
    // DB mode would delete PERSISTED sessions after the 1h default — fighting the
    // 30-day storage.retention.* policy that startDbRetention already wires. Run the
    // in-memory lifecycle ONLY in memory mode; DB mode is covered separately (PERS-03,
    // "do not double-wire").
    val storageType = config.propertyOrNull("storage.type")?.getString() ?: "memory"
    if (storageType.equals("database", ignoreCase = true)) {
        moduleLogger.info("Session lifecycle: DB mode — retention handled by storage.retention.* (DbRetentionPolicy)")
        return
    }

    val maxAgeMs =
        config.propertyOrNull("session.maxAgeMs")?.getString()?.toLongOrNull() ?: DEFAULT_SESSION_MAX_AGE_MS
    val maxCount =
        config.propertyOrNull("session.maxCount")?.getString()?.toIntOrNull() ?: DEFAULT_SESSION_MAX_COUNT
    val checkIntervalMs =
        config.propertyOrNull("session.checkIntervalMs")?.getString()?.toLongOrNull()
            ?: DEFAULT_SESSION_CHECK_INTERVAL_MS

    val retentionScope = CoroutineScope(SupervisorJob())
    val retentionPolicy =
        RetentionPolicy(
            maxSessionAgeMs = maxAgeMs,
            maxSessions = maxCount,
            checkIntervalMs = checkIntervalMs,
        )

    retentionPolicy.start(retentionScope, SessionManager)
    moduleLogger.info("Session lifecycle configured: maxAge={}ms, maxCount={}, checkInterval={}ms", maxAgeMs, maxCount, checkIntervalMs)

    monitor.subscribe(ApplicationStopped) {
        moduleLogger.info("Application stopping — cleaning up sessions")
        retentionPolicy.stop()
        SessionManager.clearAll()
        retentionScope.cancel()
        moduleLogger.info("Session cleanup complete")
    }
}

/**
 * Install the single [RateLimit] plugin and register all per-IP scopes (Ktor
 * forbids installing the plugin twice, so this is the ONE install site):
 *  - `api` (60/min) and `session-create` (10/min) — production hardening (ADR-029),
 *    always registered, keyed on `remoteAddress`.
 *  - `shared` (SHAR-02, D-12) — the public shared read, keyed on `remoteHost`,
 *    config-gated by `share.rateLimit.enabled` and sized by `requestsPerMinute`.
 *
 * Behind a reverse proxy, install `XForwardedHeaders` so the client IP (not the
 * proxy) is used — otherwise all viewers share one bucket. That is DEPLOY config.
 * Limits are read at install time; changing them requires a restart.
 */
private fun Application.configureRateLimit() {
    val cfg = environment.config
    val sharedEnabled = cfg.propertyOrNull("share.rateLimit.enabled")?.getString()?.toBoolean() ?: true
    attributes.put(SharedRateLimitEnabledKey, sharedEnabled)
    val sharedRpm = cfg.propertyOrNull("share.rateLimit.requestsPerMinute")?.getString()?.toIntOrNull() ?: 60

    install(RateLimit) {
        register(RateLimitName("api")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteAddress }
        }
        register(RateLimitName("session-create")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteAddress }
        }
        if (sharedEnabled) {
            register(RateLimitName(SHARED_RATE_LIMIT_NAME)) {
                rateLimiter(limit = sharedRpm, refillPeriod = 1.minutes)
                requestKey { call -> call.request.local.remoteHost }
            }
        }
    }
    moduleLogger.info(
        "Rate limiting installed (api=60/min, session-create=10/min, shared={})",
        if (sharedEnabled) "$sharedRpm/min" else "disabled",
    )
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
