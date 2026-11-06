package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.Incident
import com.jh.proj.coroutineviz.health.IncidentKind
import com.jh.proj.coroutineviz.health.ObservedAge
import com.jh.proj.coroutineviz.health.RoundMember
import com.jh.proj.coroutineviz.health.SummaryView
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/** A page of `health/items?list=incidents` (spec C-3): the derivation's own incidents, in the health's order. */
@Serializable
data class IncidentsPage(
    val list: String,
    val asOfSeq: Long,
    val total: Int,
    val items: List<Incident>,
    val nextCursor: String?,
    val unknownAtSeq: Int,
)

/** A page of `health/items?list=round-members&incidentId=` (spec C-3): a failed round's failures, then its cancellations after. */
@Serializable
data class RoundMembersPage(
    val list: String,
    val asOfSeq: Long,
    val total: Int,
    val items: List<RoundMember>,
    val nextCursor: String?,
    val unknownAtSeq: Int,
)

/** A page of `health/items?list=long-lived` or `list=long-suspended` (spec C-3), newest first. */
@Serializable
data class ObservedAgesPage(
    val list: String,
    val asOfSeq: Long,
    val total: Int,
    val items: List<ObservedAge>,
    val nextCursor: String?,
    val unknownAtSeq: Int,
)

/**
 * `GET /api/sessions/{id}/health/items?list=&cursor=&limit=` with the view's binding (spec C-3, plan E-10): one page of a
 * health list past its first 50. Every list derives from the whole view, so a strict read answers `STALE` while any
 * coroutine is unknown at its seq.
 */
internal suspend fun ApplicationCall.respondHealthItems(view: SessionView) {
    val params = request.queryParameters
    val list = params["list"] ?: badParameter("list is required")
    val limit = pageLimit(params)
    val cursor = params["cursor"]
    val kind =
        params["kind"]?.let { k -> IncidentKind.entries.firstOrNull { it.name == k } ?: badParameter("unknown kind $k") }
    if (list !in LISTS) badParameter("list must be one of ${LISTS.joinToString()}")
    view.requireNoUnknown()
    val derived = view.derive()
    val summaries = view.input.summaries
    when (list) {
        "incidents" -> {
            val all = derived.incidents.filter { kind == null || it.kind == kind }
            val page = Keyset.page(all, ::incidentKey, cursor, limit)
            respond(IncidentsPage(list, view.seq, all.size, page.items, page.nextCursor, view.unknownAtSeq))
        }
        "round-members" -> {
            val incidentId = params["incidentId"] ?: badParameter("incidentId is required")
            val members = derived.roundMembers(incidentId)
            if (members == null) {
                respond(HttpStatusCode.NotFound, ErrorResponse("No failed round $incidentId in this view", code = "incident-not-found"))
                return
            }
            val page = Keyset.page(members, ::memberKey, cursor, limit)
            respond(RoundMembersPage(list, view.seq, members.size, page.items, page.nextCursor, view.unknownAtSeq))
        }
        else -> {
            val longLived = list == "long-lived"
            val all = if (longLived) derived.longLived else derived.longSuspended
            val page = Keyset.page(all, ageKey(summaries, longLived), cursor, limit)
            respond(ObservedAgesPage(list, view.seq, all.size, page.items, page.nextCursor, view.unknownAtSeq))
        }
    }
}

/** The page key of an incident: the health's order (kind, newest first, then id; plan E-9). */
internal fun incidentKey(i: Incident) = Keyset.Key(i.kind.ordinal.toLong(), i.sortSeq, 0, i.id)

/** The page key of a failed round's member: failures first, newest terminal first, then id. */
internal fun memberKey(m: RoundMember) = Keyset.Key(m.role.ordinal.toLong(), m.terminalSeq, 0, m.coroutineId)

/** The page key of an observed age: newest first by first observation (long-lived) or by suspension, then id. */
internal fun ageKey(
    summaries: SummaryView,
    longLived: Boolean,
): (ObservedAge) -> Keyset.Key =
    { age ->
        val s = summaries.get(age.coroutineId)
        Keyset.Key(0, (if (longLived) s?.firstObserved?.seq else s?.lastTransition?.seq) ?: 0L, 0, age.coroutineId)
    }

private val LISTS = listOf("incidents", "round-members", "long-lived", "long-suspended")
