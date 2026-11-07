package com.jh.proj.coroutineviz.experiment

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
data class LookComment(
    val rating: Int,
    val comment: String,
    /** Epoch ms. */
    val at: Long,
)

/** One look's row on the results page. Never carries an anonymous id. */
@Serializable
data class LookResultRow(
    val look: String,
    val label: String,
    val weight: Int,
    val inSplit: Boolean,
    /** Browsers whose first look this was, given at random. */
    val assigned: Int,
    /** Browsers whose first look this was because they had picked it (or came by a link to it). */
    val chose: Int,
    /** Browsers that saw a session page in this look. */
    val visitorsSeen: Int,
    /** Session pages shown in this look. */
    val sessionsSeen: Int,
    /** The median, over browsers, of their visible time on session pages in this look. */
    val medianTimeMs: Long?,
    /** The share (0–1) of [visitorsSeen] who switched away from this look in the menu. */
    val switchAwayRate: Double?,
    /** Where they went: look → browsers. */
    val switchedTo: Map<String, Int>,
    val votes: Int,
    val averageRating: Double?,
    /** Newest first, at most [LookResults.MAX_COMMENTS]. */
    val comments: List<LookComment>,
)

@Serializable
data class LookRuleDto(
    val minVotesPerLook: Int = LookDecisionRule.MIN_VOTES_PER_LOOK,
    val maxDays: Int = LookDecisionRule.MAX_DAYS,
    val clearlyWorseSwitchAwayPoints: Double = LookDecisionRule.CLEARLY_WORSE_SWITCH_AWAY_POINTS,
    val tooCloseRating: Double = LookDecisionRule.TOO_CLOSE_RATING,
)

@Serializable
data class LookDecisionDto(
    val status: String,
    val text: String,
    val winner: String?,
    val keep: List<String>,
)

/** `GET /api/experiments/looks/results`. */
@Serializable
data class LookResultsResponse(
    val enabled: Boolean,
    /** `database` or `memory` (lost on restart). */
    val storage: String,
    /** The first visitor (epoch ms), the experiment's start; null before. */
    val startedAt: Long?,
    val day: Int?,
    val rule: LookRuleDto,
    val decision: LookDecisionDto,
    val tooLittleData: Boolean,
    val warning: String?,
    val looks: List<LookResultRow>,
)

object LookResults {
    const val MAX_COMMENTS = 200
    private const val TWO_DECIMALS = 100.0

    fun compute(
        data: ExperimentData,
        config: LookExperimentConfig,
        storage: String,
        now: Long,
    ): LookResultsResponse {
        val rows = LookIds.ALL.map { row(it, data, config) }
        val startedAt = data.visitors.minOfOrNull { it.firstSeenAt }
        val decision =
            LookDecisionRule.decide(
                // The exact averages: the rows' are rounded for display.
                rows.map { row ->
                    val exact =
                        data.votes
                            .filter { it.look == row.look }
                            .map { it.rating }
                            .takeIf { it.isNotEmpty() }
                            ?.average()
                    LookStanding(row.look, row.votes, exact, row.switchAwayRate)
                },
                startedAt,
                now,
            )
        val tooLittle = decision.status == DecisionStatus.NOT_STARTED || decision.status == DecisionStatus.RUNNING
        return LookResultsResponse(
            enabled = config.enabled,
            storage = storage,
            startedAt = startedAt,
            day = LookDecisionRule.day(startedAt, now),
            rule = LookRuleDto(),
            decision = LookDecisionDto(decision.status.name, decision.text, decision.winner, decision.keep),
            tooLittleData = tooLittle,
            warning = if (tooLittle) "Too little data yet: these numbers can still change a lot." else null,
            looks = rows,
        )
    }

    private fun row(
        look: String,
        data: ExperimentData,
        config: LookExperimentConfig,
    ): LookResultRow {
        val entries = data.visitors.filter { it.entryLook == look }
        val exposures = data.exposures.filter { it.look == look }
        val seenBy = exposures.filter { it.sessionsSeen > 0 }.map { it.anonId }.toSet()
        val awayFrom = data.switches.filter { it.from == look }
        val leavers = awayFrom.map { it.anonId }.toSet()
        val votes = data.votes.filter { it.look == look }
        val weight = config.weights[look] ?: 0
        return LookResultRow(
            look = look,
            label = LookIds.label(look),
            weight = weight,
            inSplit = weight > 0,
            assigned = entries.count { it.entryKind == EntryKind.ASSIGNED },
            chose = entries.count { it.entryKind == EntryKind.CHOSE },
            visitorsSeen = seenBy.size,
            sessionsSeen = exposures.sumOf { it.sessionsSeen },
            medianTimeMs = median(exposures.filter { it.visibleMs > 0 }.map { it.visibleMs }),
            switchAwayRate = if (seenBy.isEmpty()) null else leavers.count { it in seenBy }.toDouble() / seenBy.size,
            switchedTo =
                awayFrom
                    .groupBy { it.to }
                    .mapValues { (_, rows) -> rows.map { it.anonId }.toSet().size }
                    .toSortedMap(compareBy { LookIds.ALL.indexOf(it) }),
            votes = votes.size,
            averageRating = if (votes.isEmpty()) null else (votes.map { it.rating }.average() * TWO_DECIMALS).roundToLong() / TWO_DECIMALS,
            comments =
                votes
                    .filter { !it.comment.isNullOrBlank() }
                    .sortedByDescending { it.votedAt }
                    .take(MAX_COMMENTS)
                    .map { LookComment(it.rating, it.comment!!, it.votedAt) },
        )
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
