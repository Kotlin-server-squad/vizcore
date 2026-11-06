package com.jh.proj.coroutineviz.experiment

import com.jh.proj.coroutineviz.experiment.LookDecisionRule.DAY_MS
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LookDecisionRuleTest {
    private val start = 1_000_000L

    private fun standing(
        look: String,
        votes: Int = 30,
        average: Double? = 3.0,
        switchAway: Double? = 0.1,
    ) = LookStanding(look, votes, if (votes == 0) null else average, switchAway)

    private fun allFour(vararg overrides: LookStanding): List<LookStanding> {
        val byLook = overrides.associateBy { it.look }
        return LookIds.ALL.map { byLook[it] ?: standing(it) }
    }

    @Test
    fun `the constants are the ones the user chose`() {
        assertEquals(30, LookDecisionRule.MIN_VOTES_PER_LOOK)
        assertEquals(14, LookDecisionRule.MAX_DAYS)
        assertEquals(10.0, LookDecisionRule.CLEARLY_WORSE_SWITCH_AWAY_POINTS)
        assertEquals(0.3, LookDecisionRule.TOO_CLOSE_RATING)
    }

    @Test
    fun `not started before the first visitor`() {
        val decision = LookDecisionRule.decide(allFour(), startedAt = null, now = start)

        assertEquals(DecisionStatus.NOT_STARTED, decision.status)
        assertEquals("Not started: nobody has seen a look in the experiment yet.", decision.text)
    }

    @Test
    fun `running names the look with the fewest votes and the day`() {
        val decision =
            LookDecisionRule.decide(
                allFour(standing("b", votes = 12), standing("c", votes = 12)),
                startedAt = start,
                now = start + 3 * DAY_MS + 5,
            )

        assertEquals(DecisionStatus.RUNNING, decision.status)
        assertEquals("Not decided yet: B · Calm has 12 of 30 votes, day 4 of 14.", decision.text)
        assertNull(decision.winner)
    }

    @Test
    fun `every look counts, whatever its weight`() {
        val decision =
            LookDecisionRule.decide(allFour(standing("a", votes = 0)), startedAt = start, now = start + DAY_MS)

        assertEquals(DecisionStatus.RUNNING, decision.status)
        assertEquals("Not decided yet: A · Compact has 0 of 30 votes, day 2 of 14.", decision.text)
    }

    @Test
    fun `the best average wins once every look has 30 votes`() {
        val decision =
            LookDecisionRule.decide(
                allFour(standing("b", votes = 31, average = 4.5), standing("c", average = 4.0)),
                startedAt = start,
                now = start + DAY_MS,
            )

        assertEquals(DecisionStatus.WINNER, decision.status)
        assertEquals("b", decision.winner)
        assertEquals("B · Calm wins: rated 4.5 from 31 votes, ahead of C · Bold (4.0).", decision.text)
    }

    @Test
    fun `fourteen days end the run even with few votes`() {
        val looks = allFour(standing("a", votes = 3, average = 4.9), standing("b", votes = 2))

        assertEquals(DecisionStatus.RUNNING, LookDecisionRule.decide(looks, start, start + 14 * DAY_MS - 1).status)
        val decided = LookDecisionRule.decide(looks, start, start + 14 * DAY_MS)
        assertEquals(DecisionStatus.WINNER, decided.status)
        assertEquals("a", decided.winner)
    }

    @Test
    fun `within 0_3 is too close, and keeps the top two`() {
        val decision =
            LookDecisionRule.decide(
                allFour(standing("b", average = 4.2), standing("c", average = 3.9)),
                startedAt = start,
                now = start + DAY_MS,
            )

        assertEquals(DecisionStatus.TOO_CLOSE, decision.status)
        assertEquals(listOf("b", "c"), decision.keep)
        assertEquals(
            "Too close: B · Calm (4.2) and C · Bold (3.9) are within 0.3. Keep the top two and run longer.",
            decision.text,
        )
    }

    @Test
    fun `just over 0_3 apart is not too close`() {
        val decision =
            LookDecisionRule.decide(allFour(standing("b", average = 4.21), standing("c", average = 3.9)), start, start + DAY_MS)

        assertEquals(DecisionStatus.WINNER, decision.status)
    }

    @Test
    fun `a best look that people clearly leave more loses to the runner-up`() {
        val decision =
            LookDecisionRule.decide(
                allFour(standing("b", average = 4.5, switchAway = 0.32), standing("c", average = 4.0, switchAway = 0.15)),
                startedAt = start,
                now = start + DAY_MS,
            )

        assertEquals(DecisionStatus.WINNER, decision.status)
        assertEquals("c", decision.winner)
        assertEquals(
            "C · Bold wins: B · Calm is rated higher (4.5 vs 4.0), but 32 % switch away from it against 15 % from C · Bold.",
            decision.text,
        )
    }

    @Test
    fun `exactly 10 points worse is not clearly worse`() {
        val decision =
            LookDecisionRule.decide(
                allFour(standing("b", average = 4.5, switchAway = 0.25), standing("c", average = 4.0, switchAway = 0.15)),
                startedAt = start,
                now = start + DAY_MS,
            )

        assertEquals("b", decision.winner)
    }

    @Test
    fun `an unknown switch-away rate never overturns the rating`() {
        val decision =
            LookDecisionRule.decide(
                allFour(standing("b", average = 4.5, switchAway = null), standing("c", average = 4.0, switchAway = 0.0)),
                startedAt = start,
                now = start + DAY_MS,
            )

        assertEquals("b", decision.winner)
    }

    @Test
    fun `the only rated look wins after 14 days`() {
        val looks =
            LookIds.ALL.map { if (it == "b") standing(it, votes = 12, average = 4.2) else standing(it, votes = 0) }

        val decision = LookDecisionRule.decide(looks, start, start + 14 * DAY_MS)

        assertEquals(DecisionStatus.WINNER, decision.status)
        assertEquals("B · Calm wins: it is the only look anyone rated (4.2 from 12 votes).", decision.text)
    }

    @Test
    fun `no votes at all after 14 days`() {
        val decision = LookDecisionRule.decide(LookIds.ALL.map { standing(it, votes = 0) }, start, start + 15 * DAY_MS)

        assertEquals(DecisionStatus.NO_VOTES, decision.status)
        assertEquals("Ended without votes: no look was rated in 14 days.", decision.text)
    }

    @Test
    fun `equal averages are ordered by votes, then by the registry`() {
        val decision =
            LookDecisionRule.decide(
                allFour(
                    standing("classic", average = 2.0),
                    standing("a", average = 4.0, votes = 30),
                    standing("b", average = 4.0, votes = 40),
                ),
                start,
                start + DAY_MS,
            )

        assertEquals(DecisionStatus.TOO_CLOSE, decision.status)
        assertEquals(listOf("b", "a"), decision.keep)
    }
}
