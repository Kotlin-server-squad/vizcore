package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SummaryFoldTest {
    @Test
    fun `health at a seq sees only the events up to it`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        script.created("a", at = sec(1), parent = "req")
        val beforeFailure = script.lastSeq
        script.failed("a", at = sec(2))

        fun healthAt(seq: Long) =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = SummaryFold.foldTo(script.events, seq),
                    kind = SessionKind.ATTACHED,
                    mode = HealthMode.Replay(seq),
                ),
            )

        assertEquals(0, healthAt(beforeFailure).problemCount)
        val after = healthAt(script.lastSeq)
        assertEquals(1, after.problemCount)
        assertIs<FailedRoundIncident>(after.incidents.items.single())
        assertEquals(script.lastSeq, after.asOfSeq)
    }
}
