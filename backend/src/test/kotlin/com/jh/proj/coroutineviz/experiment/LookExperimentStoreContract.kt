package com.jh.proj.coroutineviz.experiment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The behaviour both stores share; each store's test class runs it. */
abstract class LookExperimentStoreContract {
    abstract fun newStore(maxVisitors: Int = 100): LookExperimentStore

    private val one = "00000000-0000-4000-8000-000000000001"
    private val two = "00000000-0000-4000-8000-000000000002"

    @Test
    fun `the first entry of a browser is kept`() {
        val store = newStore()

        store.record(one, listOf(LookEvent.Entry("b", EntryKind.ASSIGNED)), at = 10)
        store.record(one, listOf(LookEvent.Entry("c", EntryKind.CHOSE)), at = 20)

        assertEquals(listOf(VisitorRow(one, "b", EntryKind.ASSIGNED, 10)), store.snapshot().visitors)
    }

    @Test
    fun `an entry after other events fills in the visitor`() {
        val store = newStore()

        store.record(one, listOf(LookEvent.Seen("a")), at = 10)
        store.record(one, listOf(LookEvent.Entry("a", EntryKind.CHOSE)), at = 20)

        assertEquals(listOf(VisitorRow(one, "a", EntryKind.CHOSE, 10)), store.snapshot().visitors)
    }

    @Test
    fun `seen, time and switches add up per browser and look`() {
        val store = newStore()

        store.record(one, listOf(LookEvent.Seen("a"), LookEvent.Time("a", 1_000), LookEvent.Seen("a")), at = 1)
        store.record(one, listOf(LookEvent.Time("a", 500), LookEvent.Switch("a", "b"), LookEvent.Switch("a", "b")), at = 2)
        store.record(two, listOf(LookEvent.Time("c", 7)), at = 3)

        val data = store.snapshot()
        assertEquals(
            setOf(ExposureRow(one, "a", 2, 1_500), ExposureRow(two, "c", 0, 7)),
            data.exposures.toSet(),
        )
        assertEquals(listOf(SwitchRow(one, "a", "b", 2)), data.switches)
        assertEquals(setOf(one, two), data.visitors.map { it.anonId }.toSet())
    }

    @Test
    fun `a new vote replaces the old one for the same look`() {
        val store = newStore()

        store.putVote(one, "b", 2, "meh", at = 10)
        store.putVote(one, "b", 5, null, at = 20)
        store.putVote(one, "c", 4, "nice", at = 30)

        assertEquals(
            setOf(VoteRow(one, "b", 5, null, 20), VoteRow(one, "c", 4, "nice", 30)),
            store.snapshot().votes.toSet(),
        )
    }

    @Test
    fun `beyond the visitor cap a new browser is dropped, known ones still count`() {
        val store = newStore(maxVisitors = 1)

        assertTrue(store.record(one, listOf(LookEvent.Seen("a")), at = 1))
        assertFalse(store.record(two, listOf(LookEvent.Seen("a")), at = 2))
        assertFalse(store.putVote(two, "a", 3, null, at = 3))
        assertTrue(store.putVote(one, "a", 3, null, at = 4))

        val data = store.snapshot()
        assertEquals(listOf(one), data.visitors.map { it.anonId })
        assertEquals(listOf(one), data.votes.map { it.anonId })
        assertEquals(listOf(ExposureRow(one, "a", 1, 0)), data.exposures)
    }
}
