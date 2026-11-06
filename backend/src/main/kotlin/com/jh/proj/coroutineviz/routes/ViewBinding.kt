package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.Base64

/** How a follow-up read is bound (spec C-3 `asOfSeq`, "Modes"; plan E-3). */
internal sealed interface ViewBinding {
    /** The session as it is now. */
    data object Current : ViewBinding

    /** A replay view at [atSeq]: lenient, says what it does not know, possibly blocked not evaluated. */
    data class Replay(
        val atSeq: Long,
    ) : ViewBinding

    /**
     * A live or shared view bound to the health it fetched: strict, answering `STALE` when it cannot answer exactly.
     * [watermarkNanos] is the health's `asOfWatermark.appNanos`, [transportRevision] its `transport.revision`.
     */
    data class Bound(
        val asOfSeq: Long,
        val watermarkNanos: Long?,
        val transportRevision: Long?,
    ) : ViewBinding
}

/** A query parameter that does not parse; answered with 400. */
internal class BadReadParameter(
    message: String,
) : IllegalArgumentException(message)

/** The view has moved on since its `asOfSeq` (spec C-3 `STALE`); answered with 409, code `stale`. */
internal class StaleViewException(
    message: String,
) : IllegalStateException(message)

/** Rethrows [e] inside [respondReadErrors], which answers it. */
internal fun rethrow(e: Exception): Nothing = throw e

/** Answers the read with 400 and [message]. */
internal fun badParameter(message: String): Nothing = throw BadReadParameter(message)

/** The binding of a follow-up read: `atSeq` (replay) or `asOfSeq[&asOfWatermark][&asOfTransport]` (bound), exclusive. */
internal fun parseViewBinding(params: Parameters): ViewBinding {
    fun nonNegative(name: String): Long? =
        params[name]?.let { raw -> raw.toLongOrNull()?.takeIf { it >= 0 } ?: badParameter("$name must be a non-negative integer") }
    val atSeq = nonNegative("atSeq")
    val asOfSeq = nonNegative("asOfSeq")
    val watermark = params["asOfWatermark"]?.let { it.toLongOrNull() ?: badParameter("asOfWatermark must be an integer") }
    val transport = nonNegative("asOfTransport")
    if (atSeq != null && asOfSeq != null) badParameter("atSeq and asOfSeq are exclusive")
    if (asOfSeq == null && (watermark != null || transport != null)) badParameter("asOfWatermark and asOfTransport need asOfSeq")
    return when {
        atSeq != null -> ViewBinding.Replay(atSeq)
        asOfSeq != null -> ViewBinding.Bound(asOfSeq, watermark, transport)
        else -> ViewBinding.Current
    }
}

internal const val DEFAULT_PAGE_LIMIT = 50
internal const val MAX_PAGE_LIMIT = 200

/** The `limit` of a paged read: 1..[MAX_PAGE_LIMIT], [DEFAULT_PAGE_LIMIT] when absent (plan E-9). */
internal fun pageLimit(params: Parameters): Int =
    params["limit"]?.let { raw ->
        raw.toIntOrNull()?.takeIf { it in 1..MAX_PAGE_LIMIT } ?: badParameter("limit must be 1..$MAX_PAGE_LIMIT")
    } ?: DEFAULT_PAGE_LIMIT

/** Keyset paging over a list sorted by [Key] (plan E-9): group ascending, then a and b descending, then id ascending. */
internal object Keyset {
    private const val PARTS = 4

    data class Key(
        val group: Long,
        val a: Long,
        val b: Long,
        val id: String,
    ) : Comparable<Key> {
        override fun compareTo(other: Key): Int =
            compareValues(group, other.group).takeIf { it != 0 }
                ?: compareValues(other.a, a).takeIf { it != 0 }
                ?: compareValues(other.b, b).takeIf { it != 0 }
                ?: id.compareTo(other.id)

        fun encode(): String = "$group.$a.$b." + Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())
    }

    fun decode(cursor: String): Key {
        val parts = cursor.split('.', limit = PARTS)
        val numbers = parts.take(PARTS - 1).map { it.toLongOrNull() }
        val id = parts.getOrNull(PARTS - 1)?.let { runCatching { String(Base64.getUrlDecoder().decode(it)) }.getOrNull() }
        if (parts.size != PARTS || id == null || numbers.any { it == null }) badParameter("cursor does not parse")
        return Key(numbers[0]!!, numbers[1]!!, numbers[2]!!, id)
    }

    data class Page<T>(
        val items: List<T>,
        val nextCursor: String?,
    )

    /** One page of [sorted] (in [Key] order), starting strictly after [cursor]. */
    fun <T> page(
        sorted: List<T>,
        keyOf: (T) -> Key,
        cursor: String?,
        limit: Int,
    ): Page<T> {
        val after = cursor?.let(::decode)
        val start =
            if (after == null) 0 else sorted.indexOfFirst { keyOf(it) > after }.let { if (it < 0) sorted.size else it }
        val items = sorted.subList(start, minOf(sorted.size, start + limit)).toList()
        val next = if (start + items.size < sorted.size) items.lastOrNull()?.let { keyOf(it).encode() } else null
        return Page(items, next)
    }
}

/** Runs [block], answering this API's read errors: 400, 409 `stale`, 503 `summaries-unavailable` / `history-trimmed`. */
internal suspend fun ApplicationCall.respondReadErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: BadReadParameter) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad parameter"))
    } catch (_: StaleViewException) {
        respond(HttpStatusCode.Conflict, ErrorResponse("This view has moved on; fetch the health again", code = "stale"))
    } catch (_: SummariesUnavailableException) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse("Health is unavailable for this session", code = "summaries-unavailable"),
        )
    } catch (_: HistoryTrimmedException) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse("Health is unavailable: this session's early events were trimmed", code = "history-trimmed"),
        )
    }
}
