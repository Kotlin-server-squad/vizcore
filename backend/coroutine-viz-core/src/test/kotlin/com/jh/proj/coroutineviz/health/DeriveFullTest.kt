package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `deriveFull` keeps the uncapped lists behind the health's capped ones (spec C-3 `health/items`, plan E-11). */
class DeriveFullTest {
    private val watermark = WatermarkObservation(appNanos = sec(200), appEpochMs = 1_792_000_200_000, receivedEpochMs = 1_792_000_200_100)

    private fun script(): EventScript =
        EventScript().apply {
            // 60 failed rounds: a root and a failing child each.
            for (i in 1..60) {
                val root = "r$i"
                created(root, at = sec(i.toLong()))
                started(root, at = sec(i.toLong()))
                created("$root-c", at = sec(i.toLong()), parent = root)
                started("$root-c", at = sec(i.toLong()))
                failed("$root-c", at = sec(i.toLong()) + ms(10))
                completed(root, at = sec(i.toLong()) + ms(20))
            }
            // 55 long-lived active coroutines (suspended, observed again at the end).
            for (i in 1..55) {
                created("ll$i", at = sec(1))
                started("ll$i", at = sec(1))
                suspended("ll$i", at = sec(2))
            }
            // One big failed round: 3 failures, then 4 cancellations after the first failure.
            created("big", at = sec(100))
            started("big", at = sec(100))
            for (i in 1..7) {
                created("big-$i", at = sec(100), parent = "big")
                started("big-$i", at = sec(100))
            }
            failed("big-1", at = sec(101))
            failed("big-2", at = sec(102))
            for (i in 4..7) cancelled("big-$i", at = sec(103))
            failed("big-3", at = sec(104))
            failed("big", at = sec(105))
        }

    private fun input(script: EventScript): HealthInput =
        HealthInput(
            summaries = script.table(),
            kind = SessionKind.ATTACHED,
            watermark = watermark,
            transitionsFor = { id -> Transitions.forCoroutine(script.events, id) },
        )

    @Test
    fun `derive is deriveFull's health, and the full lists start with the capped ones`() {
        val input = input(script())
        val full = SessionHealthDerivation.deriveFull(input)

        assertEquals(SessionHealthDerivation.derive(input), full.health)
        assertEquals(full.health.incidents.total, full.incidents.size)
        assertEquals(61, full.incidents.size)
        assertEquals(full.incidents.take(50), full.health.incidents.items)
        assertEquals(full.health.problemRounds.total, full.problemRounds.size)
        assertEquals(full.problemRounds.take(50), full.health.problemRounds.items)
        assertEquals(full.health.observations.longLived.total, full.longLived.size)
        assertEquals(full.longLived.take(50), full.health.observations.longLived.items)
        assertEquals(full.health.observations.longSuspended.total, full.longSuspended.size)
        assertEquals(full.longSuspended.take(50), full.health.observations.longSuspended.items)
    }

    @Test
    fun `round members are the failures newest first, then the cancellations after the first failure`() {
        val full = SessionHealthDerivation.deriveFull(input(script()))

        val members = full.roundMembers("failed:round:big")!!
        assertEquals(listOf("big", "big-3", "big-2", "big-1"), members.filter { it.role == MemberRole.FAILED }.map { it.coroutineId })
        assertEquals(4, members.count { it.role == MemberRole.CANCELLED_AFTER })
        assertEquals(MemberRole.FAILED, members.first().role)
        assertEquals("java.lang.IllegalStateException", members.first().exceptionType)
        assertNull(full.roundMembers("failure:x"), "not an incident of this health")
        assertNull(full.roundMembers("failed:round:nope"))
    }
}
