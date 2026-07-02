package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.toolwindow.CoroutineStateStyle
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundGroupingTest {
    private val nowNanos = 100_000_000_000L

    private fun node(
        id: String,
        parentId: String? = null,
        state: String = "COMPLETED",
        createdAtNanos: Long = 0,
        completedAtNanos: Long? = 1,
        name: String = id,
    ) = HierarchyNodeDto(
        id = id,
        parentId = parentId,
        name = name,
        scopeId = "sc",
        state = state,
        createdAtNanos = createdAtNanos,
        completedAtNanos = completedAtNanos,
        jobId = "j-$id",
    )

    private fun problem(
        category: ProblemCategory,
        id: String,
    ) = Problem(
        category = category,
        coroutineId = id,
        name = id,
        ageLabel = "~1.0s",
        why = "",
        sortKeyNanos = 0,
    )

    @Test fun `roots are parentless nodes and the subtree gathers descendants via parent chains`() {
        val hierarchy =
            listOf(
                node("r", createdAtNanos = 1),
                node("c1", parentId = "r", createdAtNanos = 2),
                node("c2", parentId = "c1", createdAtNanos = 3),
            )
        val plan = RoundGrouping.plan(hierarchy, emptyList(), nowNanos)
        val group = plan.listed.single()
        assertEquals("r", group.rootId)
        assertEquals(3, group.counts.ok)
    }

    @Test fun `counts split exceptions amber and completed clean nodes`() {
        val hierarchy =
            listOf(
                node("r", createdAtNanos = 1),
                node("e", parentId = "r", createdAtNanos = 2),
                node("l", parentId = "r", createdAtNanos = 3),
                node("s", parentId = "r", createdAtNanos = 4),
            )
        val problems =
            listOf(
                problem(ProblemCategory.EXCEPTION, "e"),
                problem(ProblemCategory.LEAK, "l"),
                problem(ProblemCategory.LONG_SUSPENDED, "s"),
            )
        val group = RoundGrouping.plan(hierarchy, problems, nowNanos).listed.single()
        assertEquals(1, group.counts.exceptions)
        assertEquals(2, group.counts.amber)
        assertEquals(1, group.counts.ok)
    }

    @Test fun `an in progress round is listed and expanded`() {
        val hierarchy =
            listOf(
                node("r", state = "RUNNING", completedAtNanos = null, createdAtNanos = 1),
                node("c", parentId = "r", state = "RUNNING", completedAtNanos = null, createdAtNanos = 2),
            )
        val group = RoundGrouping.plan(hierarchy, emptyList(), nowNanos).listed.single()
        assertTrue(group.inProgress)
        assertTrue(group.expanded)
    }

    @Test fun `multiple concurrent in progress rounds are all expanded`() {
        val hierarchy =
            listOf(
                node("r1", state = "RUNNING", completedAtNanos = null, createdAtNanos = 1),
                node("r2", state = "RUNNING", completedAtNanos = null, createdAtNanos = 2),
            )
        val plan = RoundGrouping.plan(hierarchy, emptyList(), nowNanos)
        assertEquals(2, plan.listed.size)
        assertTrue(plan.listed.all { it.inProgress && it.expanded })
    }

    @Test fun `nine clean finished rounds list the five recent and fold four into the summary`() {
        val hierarchy = (1..9).map { node("r$it", createdAtNanos = it.toLong()) }
        val plan = RoundGrouping.plan(hierarchy, emptyList(), nowNanos)
        assertEquals(5, plan.listed.size)
        assertTrue(plan.listed.none { it.expanded })
        assertEquals(setOf("r5", "r6", "r7", "r8", "r9"), plan.listed.map { it.rootId }.toSet())
        val summary = assertNotNull(plan.summary)
        assertEquals(listOf("r1", "r2", "r3", "r4"), summary.rootIds)
        assertEquals(4, summary.counts.ok)
        assertEquals("Rounds 1–4", summary.label)
    }

    @Test fun `an old finished problem round stays listed and never folds into the summary`() {
        val hierarchy = (1..8).map { node("r$it", createdAtNanos = it.toLong()) }
        val problems = listOf(problem(ProblemCategory.EXCEPTION, "r1"))
        val plan = RoundGrouping.plan(hierarchy, problems, nowNanos)
        val listedIds = plan.listed.map { it.rootId }.toSet()
        assertContains(listedIds, "r1")
        val summary = assertNotNull(plan.summary)
        assertFalse(summary.rootIds.contains("r1"))
        assertEquals(listOf("r2", "r3"), summary.rootIds)
    }

    @Test fun `the label contains the root name and a tilde start age`() {
        val hierarchy = listOf(node("root-x", name = "HttpHandler", createdAtNanos = nowNanos - 1_200_000_000L))
        val label =
            RoundGrouping
                .plan(hierarchy, emptyList(), nowNanos)
                .listed
                .single()
                .label
        assertContains(label, "HttpHandler")
        assertContains(label, CoroutineStateStyle.ageLabel(1_200L))
    }

    @Test fun `searchMatchIds matches by name or id case insensitive plain substring`() {
        val hierarchy =
            listOf(
                node("http-call-54", name = "fetch"),
                node("db-1", name = "HTTP-call query"),
                node("other", name = "misc"),
            )
        assertEquals(setOf("http-call-54", "db-1"), RoundGrouping.searchMatchIds(hierarchy, "HTTP-call"))
    }

    @Test fun `searchMatchIds returns empty for a blank query`() {
        assertTrue(RoundGrouping.searchMatchIds(listOf(node("a")), "   ").isEmpty())
    }

    @Test fun `match ids surface a folded round expanded with ancestor visibility`() {
        val hierarchy =
            buildList {
                add(node("r1", createdAtNanos = 1))
                add(node("child-1", parentId = "r1", createdAtNanos = 1, name = "target"))
                for (i in 2..9) add(node("r$i", createdAtNanos = i.toLong()))
            }
        val match = RoundGrouping.searchMatchIds(hierarchy, "target")
        val plan = RoundGrouping.plan(hierarchy, emptyList(), nowNanos, matchIds = match)
        assertNull(plan.summary)
        val group = plan.listed.single()
        assertEquals("r1", group.rootId)
        assertTrue(group.expanded)
        assertEquals(setOf("r1", "child-1"), group.visibleNodeIds)
    }

    @Test fun `null match ids restore the normal collapse plan`() {
        val hierarchy = (1..9).map { node("r$it", createdAtNanos = it.toLong()) }
        val plan = RoundGrouping.plan(hierarchy, emptyList(), nowNanos, matchIds = null)
        assertEquals(5, plan.listed.size)
        assertNotNull(plan.summary)
        assertTrue(plan.listed.all { it.visibleNodeIds == null })
    }

    @Test fun `empty match ids hide every round and the summary`() {
        val hierarchy = (1..3).map { node("r$it", createdAtNanos = it.toLong()) }
        val plan = RoundGrouping.plan(hierarchy, emptyList(), nowNanos, matchIds = emptySet())
        assertTrue(plan.listed.isEmpty())
        assertNull(plan.summary)
    }
}
