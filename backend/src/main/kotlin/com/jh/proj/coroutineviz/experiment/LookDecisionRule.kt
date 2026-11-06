package com.jh.proj.coroutineviz.experiment

import java.util.Locale
import kotlin.math.roundToInt

/** Where the experiment stands, per [LookDecisionRule.decide]. */
enum class DecisionStatus { NOT_STARTED, RUNNING, TOO_CLOSE, WINNER, NO_VOTES }

/** What the rule needs to know about one look. */
data class LookStanding(
    val look: String,
    val votes: Int,
    /** Null without votes. */
    val averageRating: Double?,
    /** 0–1; null when nobody saw the look. */
    val switchAwayRate: Double?,
)

data class LookDecision(
    val status: DecisionStatus,
    /** The result in plain English, as the results page shows it. */
    val text: String,
    val winner: String? = null,
    /** The looks to keep: the winner, or the top two when too close. */
    val keep: List<String> = listOfNotNull(winner),
)

/**
 * The look experiment's decision rule: every constant it uses is here, and nowhere else.
 *
 * 1. The experiment runs until every look has [MIN_VOTES_PER_LOOK] votes, or [MAX_DAYS] days have passed
 *    since its first visitor. Every look counts, whatever its weight: a weight only steers new visitors.
 * 2. Then the look with the best average rating wins (equal averages: more votes first, then registry order),
 * 3. unless people switch away from it clearly more often: more than [CLEARLY_WORSE_SWITCH_AWAY_POINTS]
 *    percentage points above the runner-up's rate. Then the runner-up wins.
 * 4. When the top two averages are within [TOO_CLOSE_RATING], nothing wins: keep the top two and run longer.
 */
object LookDecisionRule {
    /** Votes every look needs before the rule decides early. */
    const val MIN_VOTES_PER_LOOK = 30

    /** Days after the first visitor when the rule decides with whatever votes there are. */
    const val MAX_DAYS = 14

    /** How much higher (percentage points) the best look's switch-away rate may be before it loses. */
    const val CLEARLY_WORSE_SWITCH_AWAY_POINTS = 10.0

    /** Top two averages this close (or closer) are too close to call. */
    const val TOO_CLOSE_RATING = 0.3

    const val DAY_MS = 86_400_000L

    /** Absorbs floating-point noise at the 0.3 and 10-point edges, which are inclusive / exclusive as stated. */
    private const val EPSILON = 1e-9
    private const val PERCENT = 100

    /** The 1-based day of the run at [now]; null before the first visitor. */
    fun day(
        startedAt: Long?,
        now: Long,
    ): Int? = startedAt?.let { ((now - it).coerceAtLeast(0) / DAY_MS).toInt() + 1 }

    fun decide(
        looks: List<LookStanding>,
        startedAt: Long?,
        now: Long,
    ): LookDecision {
        val fewest = looks.minByOrNull { it.votes }
        val running = startedAt != null && now - startedAt < MAX_DAYS * DAY_MS && fewest != null && fewest.votes < MIN_VOTES_PER_LOOK
        return when {
            startedAt == null -> LookDecision(DecisionStatus.NOT_STARTED, "Not started: nobody has seen a look in the experiment yet.")
            running && fewest != null ->
                LookDecision(
                    DecisionStatus.RUNNING,
                    "Not decided yet: ${LookIds.label(fewest.look)} has ${fewest.votes} of $MIN_VOTES_PER_LOOK votes, " +
                        "day ${day(startedAt, now)} of $MAX_DAYS.",
                )
            else -> rank(looks)
        }
    }

    private fun rank(looks: List<LookStanding>): LookDecision {
        val ranked =
            looks
                .filter { it.votes > 0 && it.averageRating != null }
                .sortedWith(
                    compareByDescending<LookStanding> { it.averageRating }
                        .thenByDescending { it.votes }
                        .thenBy { LookIds.ALL.indexOf(it.look) },
                )
        val best = ranked.getOrNull(0)
        val runnerUp = ranked.getOrNull(1)
        return when {
            best == null -> LookDecision(DecisionStatus.NO_VOTES, "Ended without votes: no look was rated in $MAX_DAYS days.")
            runnerUp == null ->
                LookDecision(
                    DecisionStatus.WINNER,
                    "${name(best)} wins: it is the only look anyone rated (${rating(best)} from ${best.votes} votes).",
                    winner = best.look,
                )
            best.averageRating!! - runnerUp.averageRating!! <= TOO_CLOSE_RATING + EPSILON ->
                LookDecision(
                    DecisionStatus.TOO_CLOSE,
                    "Too close: ${name(best)} (${rating(best)}) and ${name(runnerUp)} (${rating(runnerUp)}) are within " +
                        "$TOO_CLOSE_RATING. Keep the top two and run longer.",
                    keep = listOf(best.look, runnerUp.look),
                )
            clearlyLeftMore(best, runnerUp) ->
                LookDecision(
                    DecisionStatus.WINNER,
                    "${name(runnerUp)} wins: ${name(best)} is rated higher (${rating(best)} vs ${rating(runnerUp)}), but " +
                        "${percent(best.switchAwayRate)} % switch away from it against ${percent(runnerUp.switchAwayRate)} % " +
                        "from ${name(runnerUp)}.",
                    winner = runnerUp.look,
                )
            else ->
                LookDecision(
                    DecisionStatus.WINNER,
                    "${name(best)} wins: rated ${rating(best)} from ${best.votes} votes, ahead of ${name(runnerUp)} (${rating(runnerUp)}).",
                    winner = best.look,
                )
        }
    }

    /** The best look's switch-away rate is more than the allowed points above the runner-up's (both known). */
    private fun clearlyLeftMore(
        best: LookStanding,
        runnerUp: LookStanding,
    ): Boolean {
        val bestLeaves = best.switchAwayRate
        val runnerUpLeaves = runnerUp.switchAwayRate
        return bestLeaves != null &&
            runnerUpLeaves != null &&
            (bestLeaves - runnerUpLeaves) * PERCENT > CLEARLY_WORSE_SWITCH_AWAY_POINTS + EPSILON
    }

    private fun name(look: LookStanding) = LookIds.label(look.look)

    private fun rating(look: LookStanding) = String.format(Locale.ROOT, "%.1f", look.averageRating)

    private fun percent(rate: Double?) = ((rate ?: 0.0) * PERCENT).roundToInt()
}
