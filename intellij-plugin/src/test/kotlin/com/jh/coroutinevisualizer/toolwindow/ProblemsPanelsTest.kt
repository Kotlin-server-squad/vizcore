package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.model.Problem
import com.jh.coroutinevisualizer.model.ProblemCategory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-companion gate for the Problems strip + detail panels (plan 15-04, Task 1). No Swing
 * instantiation — only the [ProblemsStripPanel.chipCounts]/[ProblemsStripPanel.isHealthy] and
 * [ProblemsDetailPanel.rowText] decision functions are exercised.
 */
class ProblemsPanelsTest {
    private fun problem(
        category: ProblemCategory,
        id: String,
        name: String,
        ageLabel: String,
        why: String,
    ) = Problem(
        category = category,
        coroutineId = id,
        name = name,
        ageLabel = ageLabel,
        why = why,
        sortKeyNanos = 0L,
    )

    @Test fun `chipCounts tallies per category and zeroes the missing ones`() {
        val problems =
            listOf(
                problem(ProblemCategory.EXCEPTION, "c1", "a", "~1.0s", "boom"),
                problem(ProblemCategory.EXCEPTION, "c2", "b", "~2.0s", "bang"),
                problem(ProblemCategory.LEAK, "c3", "c", "~3.0s", "alive ~3.0s"),
            )
        val counts = ProblemsStripPanel.chipCounts(problems)
        assertEquals(2, counts[ProblemCategory.EXCEPTION])
        assertEquals(1, counts[ProblemCategory.LEAK])
        assertEquals(0, counts[ProblemCategory.LONG_SUSPENDED])
    }

    @Test fun `chipCounts over an empty list zeroes every category`() {
        val counts = ProblemsStripPanel.chipCounts(emptyList())
        assertEquals(0, counts[ProblemCategory.EXCEPTION])
        assertEquals(0, counts[ProblemCategory.LEAK])
        assertEquals(0, counts[ProblemCategory.LONG_SUSPENDED])
    }

    @Test fun `isHealthy is true only for an empty problem list`() {
        assertTrue(ProblemsStripPanel.isHealthy(emptyList()))
        assertFalse(
            ProblemsStripPanel.isHealthy(
                listOf(problem(ProblemCategory.LEAK, "c1", "a", "~1.0s", "alive ~1.0s")),
            ),
        )
    }

    @Test fun `rowText renders badge name age and why for an exception`() {
        val row =
            ProblemsDetailPanel.rowText(
                problem(ProblemCategory.EXCEPTION, "c1", "orders-worker", "~4.0s", "IllegalStateException: boom"),
            )
        assertEquals("✗ orders-worker · ~4.0s · IllegalStateException: boom", row)
    }

    @Test fun `rowText prefixes leak and long-suspended rows with the amber warning badge`() {
        val leak = ProblemsDetailPanel.rowText(problem(ProblemCategory.LEAK, "c2", "cache", "~9.0s", "alive ~9.0s"))
        val suspended =
            ProblemsDetailPanel.rowText(problem(ProblemCategory.LONG_SUSPENDED, "c3", "db-wait", "~35.0s", "suspended ~35.0s"))
        assertEquals("⚠ cache · ~9.0s · alive ~9.0s", leak)
        assertEquals("⚠ db-wait · ~35.0s · suspended ~35.0s", suspended)
    }
}
