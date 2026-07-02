package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jh.coroutinevisualizer.model.Problem
import com.jh.coroutinevisualizer.model.ProblemCategory
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * Persistent, fixed-height Problems strip (sketch 004-D, D-01/D-02/D-03). Sits full-width under the
 * metric tiles: a leading status label (green "✓ No problems" when healthy, else the problem count)
 * followed by three ALWAYS-present category chips (Exceptions / Leaks / Long-suspended). The strip
 * height never changes, so the layout never jumps when the first problem arrives (D-02).
 *
 * Chips are single-select toggles with MANUAL selection management (no [javax.swing.ButtonGroup], so
 * re-clicking the active chip clears the filter, D-03): clicking a chip fires [onFilterChange] with
 * its category; re-clicking the active chip fires `onFilterChange(null)`. Zero-count chips are
 * disabled. Selection survives [update] rebuilds via [activeCategory]. All decision logic lives in the
 * pure [chipCounts]/[isHealthy] companions (unit-tested); this panel only renders.
 */
class ProblemsStripPanel(
    private val onFilterChange: (ProblemCategory?) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, STRIP_GAP, STRIP_GAP)) {
    /** The currently-selected filter chip, preserved across [update] rebuilds. EDT-confined. */
    private var activeCategory: ProblemCategory? = null

    /** The chips rendered by the last [update], paired with their category, for selection syncing. */
    private var currentChips: List<Pair<ProblemCategory, JToggleButton>> = emptyList()

    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(STRIP_HEIGHT))
        minimumSize = Dimension(0, JBUI.scale(STRIP_HEIGHT))
    }

    /** Rebuilds the status label + three chips from the latest problems. Call on the EDT. */
    fun update(problems: List<Problem>) {
        removeAll()
        val counts = chipCounts(problems)
        val active = activeCategory
        if (active != null && counts.getValue(active) == 0) {
            // The active category emptied: its chip is about to render disabled, so no click can ever
            // clear the filter again — auto-clear it and notify so the trees leave the empty keep-set.
            activeCategory = null
            onFilterChange(null)
        }
        add(statusLabel(problems))
        currentChips =
            ProblemCategory.entries.map { category ->
                category to buildChip(category, counts.getValue(category))
            }
        currentChips.forEach { (_, chip) -> add(chip) }
        syncSelection()
        revalidate()
        repaint()
    }

    private fun statusLabel(problems: List<Problem>): JBLabel {
        val label = JBLabel()
        // T-15-01: never treat a wire-derived count/text as HTML markup.
        label.putClientProperty("html.disable", true)
        if (isHealthy(problems)) {
            label.text = "✓ No problems"
            label.foreground = HEALTHY_GREEN
        } else {
            label.text = "${problems.size} problems"
            label.foreground = JBColor.foreground()
        }
        return label
    }

    private fun buildChip(
        category: ProblemCategory,
        count: Int,
    ): JToggleButton {
        val chip = JToggleButton(chipText(category, count))
        chip.isEnabled = count > 0
        chip.foreground = chipColor(category)
        chip.addActionListener { onChipClicked(category) }
        return chip
    }

    private fun onChipClicked(category: ProblemCategory) {
        activeCategory = if (activeCategory == category) null else category
        syncSelection()
        onFilterChange(activeCategory)
    }

    /** Clears any active chip filter WITHOUT firing [onFilterChange]; used on session switch. */
    fun clearFilter() {
        activeCategory = null
        syncSelection()
    }

    /** Reflects [activeCategory] onto every chip's selected state (setSelected fires no ActionEvent). */
    private fun syncSelection() {
        currentChips.forEach { (category, chip) ->
            chip.isSelected = category == activeCategory && chip.isEnabled
        }
    }

    companion object {
        /** Fixed strip height so the first problem never shifts the surrounding layout (D-02). */
        const val STRIP_HEIGHT = 28

        /** FlowLayout hgap/vgap for the strip (matches the metric-tiles idiom). */
        const val STRIP_GAP = 6

        private val HEALTHY_GREEN = JBColor(0x2E7D32, 0x66BB6A)
        private val DANGER = JBColor(0xD32F2F, 0xFF6B68)
        private val AMBER = JBColor(0xF5A524, 0xF5A524)

        /** Per-category chip counts; EVERY category is present (missing → 0). Pure; the render gate. */
        fun chipCounts(problems: List<Problem>): Map<ProblemCategory, Int> =
            ProblemCategory.entries.associateWith { category -> problems.count { it.category == category } }

        /** Healthy iff there are no problems at all. Pure. */
        fun isHealthy(problems: List<Problem>): Boolean = problems.isEmpty()

        private fun chipText(
            category: ProblemCategory,
            count: Int,
        ): String =
            when (category) {
                ProblemCategory.EXCEPTION -> "✗ Exceptions $count"
                ProblemCategory.LEAK -> "⚠ Leaks $count"
                ProblemCategory.LONG_SUSPENDED -> "⚠ Long-suspended $count"
            }

        /** Exceptions are red; leaks + long-suspended are ALWAYS amber, never red (palette rule). */
        private fun chipColor(category: ProblemCategory): JBColor =
            when (category) {
                ProblemCategory.EXCEPTION -> DANGER
                ProblemCategory.LEAK, ProblemCategory.LONG_SUSPENDED -> AMBER
            }
    }
}
