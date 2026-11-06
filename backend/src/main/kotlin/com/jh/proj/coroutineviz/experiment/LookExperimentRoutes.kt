package com.jh.proj.coroutineviz.experiment

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.jdbc.Database

/** The look experiment as the routes use it: its settings, its store, and the per-browser write limits. */
class LookExperiment(
    val config: LookExperimentConfig,
    val store: LookExperimentStore,
    /** `database` or `memory`, for the results page. */
    val storage: String,
    val now: () -> Long = System::currentTimeMillis,
    val eventLimiter: AnonRateLimiter = AnonRateLimiter(EVENT_BATCHES_PER_MINUTE),
    val voteLimiter: AnonRateLimiter = AnonRateLimiter(VOTES_PER_MINUTE),
) {
    companion object {
        const val EVENT_BATCHES_PER_MINUTE = 30
        const val VOTES_PER_MINUTE = 10

        /** Built from `lookExperiment.*`; kept in [db] when the server has one, else in memory. */
        fun from(
            config: ApplicationConfig,
            db: Database?,
        ): LookExperiment =
            LookExperiment(
                config = LookExperimentConfig.fromConfig(config),
                store = db?.let { ExposedLookExperimentStore(it) } ?: InMemoryLookExperimentStore(),
                storage = if (db != null) "database" else "memory",
            )
    }
}

/**
 * At most [perMinute] writes per anonymous id in a fixed one-minute window, on top of the per-client `api`
 * bucket. Remembers at most [maxIds] ids (least recently used go first), so random ids cannot grow it.
 */
class AnonRateLimiter(
    private val perMinute: Int,
    private val maxIds: Int = DEFAULT_MAX_IDS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val windows =
        object : LinkedHashMap<String, LongArray>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LongArray>?) = size > maxIds
        }

    /** Null when [id] may write now, else the seconds until it may again. */
    fun tryAcquire(id: String): Long? =
        synchronized(windows) {
            val at = now()
            val window = windows[id]
            when {
                window == null || at - window[0] >= WINDOW_MS -> {
                    windows[id] = longArrayOf(at, 1)
                    null
                }
                window[1] < perMinute -> {
                    window[1]++
                    null
                }
                else -> ((window[0] + WINDOW_MS - at + MS_PER_SECOND - 1) / MS_PER_SECOND).coerceAtLeast(1)
            }
        }

    private companion object {
        const val DEFAULT_MAX_IDS = 10_000
        const val WINDOW_MS = 60_000L
        const val MS_PER_SECOND = 1_000L
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}

@Serializable
private data class EventBatchDto(
    val anonId: String,
    val events: List<EventDto>,
)

@Serializable
private data class EventDto(
    val type: String,
    val look: String? = null,
    val kind: String? = null,
    val ms: Long? = null,
    val from: String? = null,
    val to: String? = null,
)

@Serializable
private data class VoteDto(
    val anonId: String,
    val look: String,
    val rating: Int,
    val comment: String? = null,
)

private const val MAX_EVENTS_BODY_BYTES = 16 * 1024
private const val MAX_VOTE_BODY_BYTES = 4 * 1024
private const val MAX_EVENTS_PER_BATCH = 50
private const val MAX_VISIBLE_MS = 12 * 60 * 60 * 1000L
private const val MAX_COMMENT_CHARS = 500
private val RATINGS = 1..5
private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val lenientJson = Json { ignoreUnknownKeys = true }

private class Invalid(
    message: String,
) : Exception(message)

/**
 * The look experiment's endpoints (plan 2026-11-06-look-experiment). Registered inside `authenticatedApi`
 * and the `api` rate limit, like the sessions list. The two writes take only the anonymous events and votes,
 * and nothing at all while the experiment is off.
 */
fun Route.registerLookExperimentRoutes(experiment: LookExperiment) {
    get("/api/experiments/looks/config") {
        val config = experiment.config
        call.respond(
            buildJsonObject {
                put("enabled", config.enabled)
                if (config.enabled) {
                    putJsonObject("weights") { config.weights.forEach { (look, weight) -> put(look, weight) } }
                    put("surveyUrl", config.surveyUrl)
                }
            },
        )
    }

    post("/api/experiments/looks/events") {
        val body = call.writeBody(experiment, MAX_EVENTS_BODY_BYTES) ?: return@post
        val parsed =
            try {
                parseEvents(body)
            } catch (e: Invalid) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        if (!call.withinLimit(experiment.eventLimiter, parsed.first)) return@post
        experiment.store.record(parsed.first, parsed.second, experiment.now())
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/experiments/looks/votes") {
        val body = call.writeBody(experiment, MAX_VOTE_BODY_BYTES) ?: return@post
        val vote =
            try {
                parseVote(body)
            } catch (e: Invalid) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        if (!call.withinLimit(experiment.voteLimiter, vote.anonId)) return@post
        experiment.store.putVote(vote.anonId, vote.look, vote.rating, vote.comment, experiment.now())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/experiments/looks/results") {
        call.respond(LookResults.compute(experiment.store.snapshot(), experiment.config, experiment.storage, experiment.now()))
    }
}

/**
 * The write's body, or null once this answered: 404 while the experiment is off (nothing is read or kept),
 * 413 past [limit] bytes. The limit is counted while reading, so a chunked body is cut off too.
 */
private suspend fun ApplicationCall.writeBody(
    experiment: LookExperiment,
    limit: Int,
): String? {
    if (!experiment.config.enabled) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "The look experiment is off"))
        return null
    }
    val bytes = readBounded(limit)
    if (bytes == null) respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Request too large"))
    return bytes?.decodeToString()
}

/** The body when it has at most [limit] bytes, else null; never reads more than one byte past the limit. */
private suspend fun ApplicationCall.readBounded(limit: Int): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val bytes = if (declared != null && declared > limit) null else receiveChannel().readRemaining(limit + 1L).readByteArray()
    return bytes?.takeIf { it.size <= limit }
}

private suspend fun ApplicationCall.withinLimit(
    limiter: AnonRateLimiter,
    anonId: String,
): Boolean {
    val waitSeconds = limiter.tryAcquire(anonId) ?: return true
    response.header(HttpHeaders.RetryAfter, waitSeconds.toString())
    respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many requests"))
    return false
}

private inline fun <reified T> decode(body: String): T =
    try {
        lenientJson.decodeFromString<T>(body)
    } catch (_: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        throw Invalid("Not a valid request body")
    }

private fun anonIdOf(raw: String): String = raw.lowercase().takeIf { UUID_PATTERN.matches(it) } ?: throw Invalid("anonId must be a UUID")

private fun lookOf(raw: String?): String = raw?.takeIf { LookIds.isKnown(it) } ?: throw Invalid("Unknown look '$raw'")

private fun parseEvents(body: String): Pair<String, List<LookEvent>> {
    val batch = decode<EventBatchDto>(body)
    val anonId = anonIdOf(batch.anonId)
    if (batch.events.size > MAX_EVENTS_PER_BATCH) throw Invalid("At most $MAX_EVENTS_PER_BATCH events per batch")
    return anonId to batch.events.map(::eventOf)
}

private fun eventOf(event: EventDto): LookEvent =
    when (event.type) {
        "entry" -> {
            val kind =
                EntryKind.entries.firstOrNull { it.name.equals(event.kind, ignoreCase = true) } ?: throw Invalid("Unknown entry kind")
            LookEvent.Entry(lookOf(event.look), kind)
        }
        "seen" -> LookEvent.Seen(lookOf(event.look))
        "time" -> {
            val ms = event.ms?.takeIf { it > 0 } ?: throw Invalid("time needs ms above 0")
            LookEvent.Time(lookOf(event.look), ms.coerceAtMost(MAX_VISIBLE_MS))
        }
        "switch" -> {
            val from = lookOf(event.from)
            val to = lookOf(event.to)
            if (from == to) throw Invalid("A switch goes to another look")
            LookEvent.Switch(from, to)
        }
        else -> throw Invalid("Unknown event type '${event.type}'")
    }

private fun parseVote(body: String): VoteDto {
    val vote = decode<VoteDto>(body)
    if (vote.rating !in RATINGS) throw Invalid("rating must be 1 to 5")
    val comment = vote.comment?.trim()?.takeIf { it.isNotEmpty() }
    if (comment != null && comment.length > MAX_COMMENT_CHARS) throw Invalid("A comment has at most $MAX_COMMENT_CHARS characters")
    return VoteDto(anonIdOf(vote.anonId), lookOf(vote.look), vote.rating, comment)
}
