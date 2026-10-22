package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TransitionsTest {
    @Test
    fun `maps lifecycle events to transitions and skips others`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.thread("a", at = 3, name = "main")
        script.suspended("a", at = 4)
        script.resumed("a", at = 5)
        script.bodyCompleted("a", at = 6)
        script.completed("a", at = 7)
        script.created("b", at = 8)

        val transitions = Transitions.forCoroutine(script.events, "a")

        assertEquals(
            listOf(
                TransitionKind.CREATED,
                TransitionKind.STARTED,
                TransitionKind.SUSPENDED,
                TransitionKind.RESUMED,
                TransitionKind.BODY_COMPLETED,
                TransitionKind.COMPLETED,
            ),
            transitions.map { it.kind },
        )
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L, 7L), transitions.map { it.appNanos })
    }
}
