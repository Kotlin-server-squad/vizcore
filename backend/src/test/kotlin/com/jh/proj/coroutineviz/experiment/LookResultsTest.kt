package com.jh.proj.coroutineviz.experiment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookResultsTest {
    private val config =
        LookExperimentConfig(enabled = true, weights = LookExperimentConfig.parseWeights("classic:25,a:25,b:50"), surveyUrl = null)

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun results(data: ExperimentData) = LookResults.compute(data, config, storage = "memory", now = 10_000L)

    private fun row(
        response: LookResultsResponse,
        look: String,
    ) = response.looks.single { it.look == look }

    @Test
    fun `one row per known look, with labels, weights and the split`() {
        val response = results(ExperimentData())

        assertEquals(LookIds.ALL, response.looks.map { it.look })
        assertEquals("A · Compact", row(response, "a").label)
        assertEquals(50, row(response, "b").weight)
        assertFalse(row(response, "c").inSplit)
        assertNull(response.startedAt)
        assertTrue(response.tooLittleData)
        assertEquals(DecisionStatus.NOT_STARTED.name, response.decision.status)
    }

    @Test
    fun `counts assigned and chose by the entry look`() {
        val data =
            ExperimentData(
                visitors =
                    listOf(
                        VisitorRow(id(1), "b", EntryKind.ASSIGNED, 500),
                        VisitorRow(id(2), "b", EntryKind.CHOSE, 300),
                        VisitorRow(id(3), "b", EntryKind.ASSIGNED, 700),
                        VisitorRow(id(4), null, null, 900),
                    ),
            )

        val response = results(data)

        assertEquals(2, row(response, "b").assigned)
        assertEquals(1, row(response, "b").chose)
        assertEquals(300L, response.startedAt)
        assertEquals(1, response.day)
    }

    @Test
    fun `sessions seen, the median time and the switch-away rate per look`() {
        val data =
            ExperimentData(
                exposures =
                    listOf(
                        ExposureRow(id(1), "a", sessionsSeen = 2, visibleMs = 1_000),
                        ExposureRow(id(2), "a", sessionsSeen = 1, visibleMs = 3_000),
                        ExposureRow(id(3), "a", sessionsSeen = 1, visibleMs = 10_000),
                        ExposureRow(id(4), "a", sessionsSeen = 1, visibleMs = 0),
                        ExposureRow(id(1), "b", sessionsSeen = 1, visibleMs = 2_000),
                        ExposureRow(id(2), "b", sessionsSeen = 0, visibleMs = 4_000),
                    ),
                switches =
                    listOf(
                        SwitchRow(id(1), "a", "b", 2),
                        SwitchRow(id(2), "a", "b", 1),
                        SwitchRow(id(2), "a", "c", 1),
                    ),
            )

        val response = results(data)
        val a = row(response, "a")

        assertEquals(5, a.sessionsSeen)
        assertEquals(4, a.visitorsSeen)
        assertEquals(3_000L, a.medianTimeMs, "the median of 1, 3 and 10 s; a visitor with no time is left out")
        assertEquals(0.5, a.switchAwayRate, "2 of the 4 visitors who saw A")
        assertEquals(mapOf("b" to 2, "c" to 1), a.switchedTo)
        assertEquals(3_000L, row(response, "b").medianTimeMs, "the mean of the middle two")
        assertNull(row(response, "c").switchAwayRate)
        assertNull(row(response, "c").medianTimeMs)
    }

    @Test
    fun `votes, the average and the comments newest first without ids`() {
        val data =
            ExperimentData(
                votes =
                    listOf(
                        VoteRow(id(1), "c", 5, "Love it", 100),
                        VoteRow(id(2), "c", 4, null, 300),
                        VoteRow(id(3), "c", 2, "Too dark", 200),
                    ),
            )

        val c = row(results(data), "c")

        assertEquals(3, c.votes)
        assertEquals(3.67, c.averageRating)
        assertEquals(listOf("Too dark", "Love it"), c.comments.map { it.comment })
        assertEquals(listOf(2, 5), c.comments.map { it.rating })
    }

    @Test
    fun `at most 200 comments per look`() {
        val votes = (1..250).map { VoteRow(id(it), "a", 3, "c$it", it.toLong()) }

        val a = row(results(ExperimentData(votes = votes)), "a")

        assertEquals(250, a.votes)
        assertEquals(200, a.comments.size)
        assertEquals("c250", a.comments.first().comment)
    }

    @Test
    fun `the response carries the rule and a warning while there is too little data`() {
        val response = results(ExperimentData(visitors = listOf(VisitorRow(id(1), "a", EntryKind.ASSIGNED, 0))))

        assertEquals(30, response.rule.minVotesPerLook)
        assertEquals(DecisionStatus.RUNNING.name, response.decision.status)
        assertTrue(response.tooLittleData)
        assertEquals("Too little data yet: these numbers can still change a lot.", response.warning)
    }
}
