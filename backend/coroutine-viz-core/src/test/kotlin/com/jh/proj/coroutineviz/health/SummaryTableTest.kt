package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryTableTest {
    /** A never-finishing root "app" with three children; two of them finish. */
    private fun containerScript(): EventScript =
        EventScript().apply {
            created("app", at = sec(0))
            started("app", at = sec(0))
            listOf("r1", "r2", "r3").forEachIndexed { i, id ->
                created(id, at = sec(1L + i), parent = "app")
                started(id, at = sec(1L + i))
            }
            completed("r1", at = sec(5))
            completed("r2", at = sec(6))
        }

    @Test
    fun `a root is promoted the first time it qualifies, at that event's seq`() {
        val script = containerScript()
        val table = script.table()

        val app = assertNotNull(table.get("app"))
        assertEquals(script.lastSeq, app.promotedAtSeq)
        assertEquals(script.lastSeq, app.lastModifiedSeq)
    }

    @Test
    fun `promotion is sticky when new active children arrive`() {
        val script = containerScript()
        val promotedAt = script.table().get("app")?.promotedAtSeq
        listOf("r4", "r5", "r6").forEach { script.created(it, at = sec(7), parent = "app") }

        assertEquals(promotedAt, script.table().get("app")?.promotedAtSeq)
    }

    @Test
    fun `growth after promotion is recorded on the summary and survives reduction`() {
        val script = containerScript()
        assertFalse(script.table().get("app")!!.grewAfterPromotion)
        script.created("r4", at = sec(7), parent = "app")

        val app = assertNotNull(script.table().get("app"))
        assertTrue(app.grewAfterPromotion)
        assertEquals(script.lastSeq, app.lastModifiedSeq)
        assertTrue(app.toStub().grewAfterPromotion)
    }

    @Test
    fun `the latest time is the greatest seen, not the last appended`() {
        val script = EventScript()
        script.created("a", at = sec(61))
        script.created("b", at = sec(59))

        assertEquals(sec(61), script.table().latestEventAppNanos)
    }

    @Test
    fun `events for unknown coroutines are ignored, last event is still tracked`() {
        val script = EventScript()
        script.started("ghost", at = sec(3))
        val table = script.table()

        assertNull(table.get("ghost"))
        assertEquals(1, table.lastEventSeq)
        assertEquals(sec(3), table.latestEventAppNanos)
    }

    @Test
    fun `a watermark advances the last seq and time but tracks no coroutine`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.watermark(at = 2)
        val table = script.table()

        assertEquals(2, table.lastEventSeq)
        assertEquals(2, table.latestEventAppNanos)
        assertEquals(1, table.all.size)
    }

    @Test
    fun `beyond capacity new coroutines are only counted`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.created("b", at = 2)
        script.created("c", at = 3)
        script.started("c", at = 4)
        val table = script.table(trackedCapacity = 2)

        assertEquals(2, table.all.size)
        assertNull(table.get("c"))
        assertEquals(1, table.untrackedCount)
    }

    @Test
    fun `a request root whose children finish first is not left a container`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        listOf("a", "b", "c", "d").forEach { script.created(it, at = sec(1), parent = "req") }
        listOf("a", "b").forEach { script.completed(it, at = sec(2)) }
        assertNotNull(script.table().get("req")?.promotedAtSeq, "half of four children finished: qualifies")

        listOf("c", "d").forEach { script.completed(it, at = sec(3)) }
        script.completed("req", at = sec(4))

        val req = assertNotNull(script.table().get("req"))
        assertNull(req.promotedAtSeq)
        assertEquals(script.lastSeq, req.lastModifiedSeq)
    }

    @Test
    fun `a container that got new children after promotion stays a container when it is cancelled`() {
        val script = containerScript()
        script.created("r4", at = sec(7), parent = "app")
        script.completed("r3", at = sec(8))
        script.completed("r4", at = sec(8))
        script.cancelled("app", at = sec(9))

        assertNotNull(script.table().get("app")?.promotedAtSeq)
    }

    @Test
    fun `over the target, finished non-failed summaries become stubs before failed ones`() {
        val script = EventScript()
        script.created("ok", at = 1)
        script.created("failed", at = 2)
        script.created("active", at = 3)
        script.failed("failed", at = 4)
        script.completed("ok", at = 5)
        script.created("new", at = 6)
        val table = script.table(fullSummaryTarget = 3)

        assertTrue(table.get("ok")!!.reduced)
        assertEquals(6, table.get("ok")!!.lastModifiedSeq, "reduction is a change, stamped with the triggering seq")
        assertFalse(table.get("failed")!!.reduced, "failed ones are reduced only after the non-failed")
        assertFalse(table.get("active")!!.reduced, "active coroutines keep their full summary")
        assertEquals(3, table.fullSummaryCount)

        script.created("newer", at = 7)
        assertTrue(script.table(fullSummaryTarget = 3).get("failed")!!.reduced)
    }

    @Test
    fun `reduction takes the oldest finished summary, not the first to finish`() {
        val script = EventScript()
        script.created("old", at = 1)
        script.created("new", at = 2)
        script.completed("new", at = 3)
        script.completed("old", at = 4)
        script.created("z", at = 5)
        val table = script.table(fullSummaryTarget = 2)

        assertTrue(table.get("old")!!.reduced)
        assertFalse(table.get("new")!!.reduced)
    }

    @Test
    fun `two creates sharing one seq are both reducible in first-observation order`() {
        val script = EventScript()
        script.created("first", at = 1)
        script.created("second", at = 2)
        script.completed("first", at = 3)
        script.completed("second", at = 4)
        script.created("z", at = 5)
        script.events[1].seq = script.events[0].seq
        val table = script.table(fullSummaryTarget = 2)

        assertTrue(table.get("first")!!.reduced)
        assertFalse(table.get("second")!!.reduced)
        assertEquals(2, table.fullSummaryCount)
    }

    @Test
    fun `a reduced failure keeps its failed flag and ancestry`() {
        val script = EventScript()
        script.created("root", at = 1)
        script.created("child", at = 2, parent = "root")
        script.failed("child", at = 3)
        script.created("x", at = 4)
        val table = script.table(fullSummaryTarget = 1)

        val child = assertNotNull(table.get("child"))
        assertTrue(child.reduced)
        assertTrue(child.failed)
        assertEquals("root", child.parentId)
        assertNull(child.failure)
    }

    /** A request that runs four children one after another: promoted at the 3rd child, grows with the 4th. */
    private fun sequentialRequest(): EventScript =
        EventScript().apply {
            created("req", at = sec(0))
            listOf("c1", "c2", "c3").forEachIndexed { i, id ->
                created(id, at = sec(1L + i), parent = "req")
                completed(id, at = sec(1L + i))
            }
            created("c4", at = sec(5), parent = "req")
        }

    @Test
    fun `a request that starts its children one after another is one round when it fails`() {
        val script = sequentialRequest()
        val before = assertNotNull(script.table().get("req"))
        assertNotNull(before.promotedAtSeq)
        assertTrue(before.grewAfterPromotion)

        script.failed("c4", at = sec(6))
        script.failed("req", at = sec(6))

        assertNull(script.table().get("req")?.promotedAtSeq)
    }

    @Test
    fun `a request that starts its children one after another is cleared when it completes`() {
        val script = sequentialRequest()
        script.completed("c4", at = sec(6))
        script.completed("req", at = sec(6))

        assertNull(script.table().get("req")?.promotedAtSeq)
    }

    @Test
    fun `a child beyond capacity still marks a promoted parent as grown`() {
        val script = containerScript()
        val capacity = script.table().all.size
        script.created("overflow", at = sec(7), parent = "app")
        val table = script.table(trackedCapacity = capacity)

        assertNull(table.get("overflow"))
        assertEquals(1, table.untrackedCount)
        assertTrue(table.get("app")!!.grewAfterPromotion)
    }

    @Test
    fun `a child created before its parent promotes the root when the parent arrives`() {
        val script = EventScript()
        listOf("a", "b", "c").forEach { script.created(it, at = sec(1), parent = "req") }
        script.completed("a", at = sec(2))
        script.completed("b", at = sec(2))
        script.created("req", at = sec(3))

        assertEquals(script.lastSeq, script.table().get("req")?.promotedAtSeq)
    }
}
