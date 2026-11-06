package com.jh.proj.coroutineviz.routes

import io.ktor.http.parametersOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewBindingTest {
    private fun parse(vararg pairs: Pair<String, String>) =
        parseViewBinding(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    @Test
    fun `no binding is the current state, atSeq a replay, asOfSeq a bound view`() {
        assertEquals(ViewBinding.Current, parse())
        assertEquals(ViewBinding.Replay(5), parse("atSeq" to "5"))
        assertEquals(ViewBinding.Bound(7, 12_000, 2), parse("asOfSeq" to "7", "asOfWatermark" to "12000", "asOfTransport" to "2"))
        assertEquals(ViewBinding.Bound(7, null, null), parse("asOfSeq" to "7"))
    }

    @Test
    fun `bad bindings are rejected`() {
        assertThrows<BadReadParameter> { parse("atSeq" to "5", "asOfSeq" to "5") }
        assertThrows<BadReadParameter> { parse("atSeq" to "-1") }
        assertThrows<BadReadParameter> { parse("asOfSeq" to "x") }
        assertThrows<BadReadParameter> { parse("asOfWatermark" to "1") }
        assertThrows<BadReadParameter> { parse("asOfTransport" to "1") }
        assertThrows<BadReadParameter> { parse("asOfSeq" to "1", "asOfWatermark" to "w") }
    }

    @Test
    fun `the page limit defaults to 50 and stays within 1 to 200`() {
        assertEquals(DEFAULT_PAGE_LIMIT, pageLimit(parametersOf()))
        assertEquals(200, pageLimit(parametersOf("limit" to listOf("200"))))
        for (bad in listOf("0", "201", "x")) assertThrows<BadReadParameter> { pageLimit(parametersOf("limit" to listOf(bad))) }
    }

    @Test
    fun `a keyset key round-trips through its cursor, ids included`() {
        val key = Keyset.Key(0, 10, 3, "co:1.2/x")
        assertEquals(key, Keyset.decode(key.encode()))
        for (bad in listOf("nope", "1.2.3", "a.2.3.Zm9v", "1.2.3.!!")) assertThrows<BadReadParameter> { Keyset.decode(bad) }
    }

    @Test
    fun `keys sort by group ascending, then a and b descending, then id`() {
        val sorted =
            listOf(
                Keyset.Key(1, 5, 0, "a"),
                Keyset.Key(0, 1, 0, "a"),
                Keyset.Key(0, 9, 0, "b"),
                Keyset.Key(0, 9, 0, "a"),
                Keyset.Key(0, 9, 4, "z"),
            ).sorted()
        assertEquals(
            listOf(
                Keyset.Key(0, 9, 4, "z"),
                Keyset.Key(0, 9, 0, "a"),
                Keyset.Key(0, 9, 0, "b"),
                Keyset.Key(0, 1, 0, "a"),
                Keyset.Key(1, 5, 0, "a"),
            ),
            sorted,
        )
    }

    @Test
    fun `pages follow each other through the cursor until there is none`() {
        val items = (1L..5L).map { Keyset.Key(0, 10 - it, 0, "i$it") }
        val first = Keyset.page(items, { it }, cursor = null, limit = 2)
        assertEquals(items.take(2), first.items)
        val second = Keyset.page(items, { it }, cursor = first.nextCursor, limit = 2)
        assertEquals(items.subList(2, 4), second.items)
        val third = Keyset.page(items, { it }, cursor = second.nextCursor, limit = 2)
        assertEquals(items.subList(4, 5), third.items)
        assertNull(third.nextCursor)
        // A cursor whose item is gone still continues after its position.
        val afterGone = Keyset.page(items.filter { it.id != "i2" }, { it }, cursor = first.nextCursor, limit = 10)
        assertEquals(items.drop(2), afterGone.items)
    }
}
