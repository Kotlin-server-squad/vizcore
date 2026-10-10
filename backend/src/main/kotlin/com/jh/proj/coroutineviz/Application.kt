package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyPrincipal
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.observability.configureObservability
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.RetentionPolicy
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.http.HttpMethod
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimiter
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
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

/** The `api` scope: tiered read / write / stream buckets per client ([RateLimitPolicy]). */
const val API_RATE_LIMIT_NAME = "api"

/** The scope around POST /api/sessions (nested inside [API_RATE_LIMIT_NAME]). */
const val SESSION_CREATE_RATE_LIMIT_NAME = "session-create"

/** The scope around the public login endpoint POST /api/auth/token. */
const val LOGIN_RATE_LIMIT_NAME = "login"

/** The limits [configureRateLimit] installed, read from the `rateLimit.*` config block. */
internal val RateLimitPolicyKey = AttributeKey<RateLimitPolicy>("RateLimitPolicy")

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
    // Install the WebSockets plugin before configureRouting() — the ingest
    // webSocket route (RCO-05) cannot be registered until the plugin is present.
    configureWebSockets()

    // Configure bounded EventStore before any sessions are created (FND-02)
    val maxEvents =
        environment.config
            .propertyOrNull("session.maxEvents")
            ?.getString()
            ?.toIntOrNull() ?: 10_000
    SessionManager.configure(maxEventsPerSession = maxEvents)

    configureStorage(maxEvents)

    // OTEL-01 construction gate: builds the OTel SDK + per-session span exporter ONLY when
    // observability.otel.enabled=true; returns immediately (constructs nothing) when off.
    configureObservability()

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
 * Install the single [RateLimit] plugin and register all scopes (Ktor forbids
 * installing the plugin twice, so this is the ONE install site). Limits come from
 * the `rateLimit.*` config block ([RateLimitPolicy]); every 429 carries the
 * `Retry-After` header that Ktor's default response modifier sets.
 *  - `api` ([API_RATE_LIMIT_NAME]) — one provider with three independent buckets per
 *    client: cheap reads (GET/HEAD), writes, and SSE/ingest-WebSocket connects
 *    ([ApiTier], [classifyApiTier]). The client is the authenticated principal when
 *    present, else the remote address ([rateLimitClientKey]).
 *  - `session-create` ([SESSION_CREATE_RATE_LIMIT_NAME]) — POST /api/sessions, nested
 *    inside `api`, so a create draws from both the write bucket and this one.
 *  - `login` ([LOGIN_RATE_LIMIT_NAME]) — the public POST /api/auth/token, by remote address.
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
    val policy = RateLimitPolicy.fromConfig(cfg)
    attributes.put(RateLimitPolicyKey, policy)

    install(RateLimit) {
        register(RateLimitName(API_RATE_LIMIT_NAME)) {
            // One bucket per (tier, client): Ktor caches a limiter per request key, so the
            // tier in the key keeps reads, writes and stream connects from draining each other.
            requestKey { call -> ApiBucketKey(classifyApiTier(call), call.rateLimitClientKey()) }
            rateLimiter { _, key ->
                val tier = (key as? ApiBucketKey)?.tier ?: ApiTier.WRITE
                RateLimiter.default(limit = policy.limitFor(tier), refillPeriod = 1.minutes)
            }
        }
        register(RateLimitName(SESSION_CREATE_RATE_LIMIT_NAME)) {
            rateLimiter(limit = policy.sessionCreate, refillPeriod = 1.minutes)
            requestKey { call -> call.rateLimitClientKey() }
        }
        register(RateLimitName(LOGIN_RATE_LIMIT_NAME)) {
            rateLimiter(limit = policy.login, refillPeriod = 1.minutes)
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
        "Rate limiting installed (read={}/min, write={}/min, stream={}/min, login={}/min, " +
            "session-create={}/min, shared={})",
        policy.read,
        policy.write,
        policy.stream,
        policy.login,
        policy.sessionCreate,
        if (sharedEnabled) "$sharedRpm/min" else "disabled",
    )
}

/** Which `api` bucket a request draws from. */
internal enum class ApiTier { READ, WRITE, STREAM }

/** Request key of the `api` provider: one bucket per tier per client. */
internal data class ApiBucketKey(
    val tier: ApiTier,
    val client: String,
)

private val STREAM_PATH = Regex("^/api/sessions/[^/]+/(stream|ingest)$")

/**
 * SSE stream and ingest-WebSocket connects are [ApiTier.STREAM]; any other GET/HEAD
 * is a cheap [ApiTier.READ]; everything else is a [ApiTier.WRITE].
 */
internal fun classifyApiTier(call: ApplicationCall): ApiTier {
    val method = call.request.httpMethod
    return when {
        STREAM_PATH.matches(call.request.path()) -> ApiTier.STREAM
        method == HttpMethod.Get || method == HttpMethod.Head -> ApiTier.READ
        else -> ApiTier.WRITE
    }
}

/**
 * Rate-limit client identity: the authenticated principal when there is one (so
 * clients behind one address do not share a bucket), else the remote address.
 */
internal fun ApplicationCall.rateLimitClientKey(): String =
    when (val principal = currentPrincipal()) {
        is ApiKeyPrincipal -> "key:${principal.name}"
        is UserPrincipal -> "user:${principal.userId}"
        else -> "ip:${request.local.remoteAddress}"
    }

/** Per-minute request limits for each rate-limit bucket, from the `rateLimit.*` config block. */
internal data class RateLimitPolicy(
    val read: Int = DEFAULT_READ_RPM,
    val write: Int = DEFAULT_WRITE_RPM,
    val stream: Int = DEFAULT_STREAM_RPM,
    val login: Int = DEFAULT_LOGIN_RPM,
    val sessionCreate: Int = DEFAULT_SESSION_CREATE_RPM,
) {
    fun limitFor(tier: ApiTier): Int =
        when (tier) {
            ApiTier.READ -> read
            ApiTier.WRITE -> write
            ApiTier.STREAM -> stream
        }

    companion object {
        const val DEFAULT_READ_RPM = 1200
        const val DEFAULT_WRITE_RPM = 300
        const val DEFAULT_STREAM_RPM = 120
        const val DEFAULT_LOGIN_RPM = 10
        const val DEFAULT_SESSION_CREATE_RPM = 10

        fun fromConfig(config: ApplicationConfig): RateLimitPolicy {
            fun rpm(
                key: String,
                default: Int,
            ): Int =
                config
                    .propertyOrNull("rateLimit.$key.requestsPerMinute")
                    ?.getString()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: default
            return RateLimitPolicy(
                read = rpm("read", DEFAULT_READ_RPM),
                write = rpm("write", DEFAULT_WRITE_RPM),
                stream = rpm("stream", DEFAULT_STREAM_RPM),
                login = rpm("login", DEFAULT_LOGIN_RPM),
                sessionCreate = rpm("sessionCreate", DEFAULT_SESSION_CREATE_RPM),
            )
        }
    }
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
