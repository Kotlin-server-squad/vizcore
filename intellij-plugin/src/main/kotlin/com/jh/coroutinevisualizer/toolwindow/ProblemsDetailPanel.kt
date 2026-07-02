package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.jh.coroutinevisualizer.model.Problem
import com.jh.coroutinevisualizer.model.ProblemCategory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Ordered problem list (sketch 004-D, D-05/D-06/D-07). Renders the pre-sorted [Problem] list as
 * one-line rows — badge · name · ~age · inline why — so the "why" is visible with no second click
 * (D-06). A single click soft-highlights the coroutine in the tree via [onHighlight] (the right pane
 * stays on the list); a double-click or the Inspect button selects it via [onInspect] (D-08). When
 * the filter chip is active the list is narrowed to that category in sync with the tree (D-03). The
 * healthy/empty state is a centered muted line (D-05). All non-visual text lives in the pure
 * [rowText] companion (unit-tested).
 */
class ProblemsDetailPanel(
    private val onHighlight: (String) -> Unit,
    private val onInspect: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val column =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(ROOT_PADDING)
        }

    /** Suspension-site suffix labels keyed by coroutine id (LONG_SUSPENDED rows only, D-06). */
    private val siteLabelsById = mutableMapOf<String, JBLabel>()

    /**
     * The (problems, filter) pair rendered by the last [show]. Skipping no-op rebuilds matters: a
     * rebuild between an Inspect button's mouse-press and -release replaces the component instance
     * and silently swallows the click (Swing only fires when press and release hit the SAME instance).
     */
    private var lastShown: Pair<List<Problem>, ProblemCategory?>? = null

    init {
        add(JBScrollPane(column), BorderLayout.CENTER)
    }

    /**
     * Rebuilds the list. When [activeFilter] is non-null the rows are narrowed to that category
     * (D-03). Problems arrive PRE-SORTED (severity then recency, D-07); render order is preserved.
     * Call on the EDT.
     */
    fun show(
        problems: List<Problem>,
        activeFilter: ProblemCategory?,
    ) {
        val key = problems to activeFilter
        if (key == lastShown) return // unchanged content — keep the live row/button instances
        lastShown = key
        column.removeAll()
        siteLabelsById.clear()
        val visible = if (activeFilter == null) problems else problems.filter { it.category == activeFilter }
        if (visible.isEmpty()) {
            column.add(healthyState())
        } else {
            visible.forEach { column.add(row(it)) }
        }
        column.revalidate()
        column.repaint()
    }

    /**
     * Extends a LONG_SUSPENDED row's why line with the resolved source site ("… at file:line").
     * No-op when [coroutineId] has no visible long-suspended row. Call on the EDT.
     */
    fun setSuspensionSite(
        coroutineId: String,
        fileLine: String,
    ) {
        val label = siteLabelsById[coroutineId] ?: return
        label.text = "… at $fileLine"
        label.isVisible = true
        column.revalidate()
        column.repaint()
    }

    private fun healthyState(): Component {
        val label = JBLabel("✓ No problems — all coroutines healthy")
        label.horizontalAlignment = SwingConstants.CENTER
        label.foreground = JBColor.GRAY
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    private fun row(problem: Problem): Component {
        val panel = JPanel(BorderLayout(ROW_HGAP, 0))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(ROW_PADDING, 0)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        // Single instance shared across the row + its children so a click anywhere on the row
        // registers (Swing does not bubble mouse events to parents automatically).
        val clicks = clickListener(problem.coroutineId)
        panel.addMouseListener(clicks)
        panel.add(rowContent(problem, clicks), BorderLayout.CENTER)

        val inspect = JButton("Inspect")
        inspect.addActionListener { onInspect(problem.coroutineId) }
        panel.add(inspect, BorderLayout.EAST)
        return panel
    }

    private fun clickListener(coroutineId: String): MouseListener =
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when (e.clickCount) {
                    SINGLE_CLICK -> onHighlight(coroutineId)
                    DOUBLE_CLICK -> onInspect(coroutineId)
                }
            }
        }

    /** The badge (colored) + rest-of-line (plain) + optional suspension-site suffix, in one column. */
    private fun rowContent(
        problem: Problem,
        clicks: MouseListener,
    ): Component {
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.alignmentX = Component.LEFT_ALIGNMENT
        text.addMouseListener(clicks)

        val line = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        line.isOpaque = false
        line.alignmentX = Component.LEFT_ALIGNMENT
        line.addMouseListener(clicks)

        val badge = wireLabel("${badgeText(problem.category)} ")
        badge.foreground = badgeColor(problem.category)
        badge.addMouseListener(clicks)
        line.add(badge)

        // name · age · why — all wire-derived, so HTML is disabled (T-15-01).
        val rest = wireLabel("${problem.name} · ${problem.ageLabel} · ${problem.why}")
        rest.addMouseListener(clicks)
        line.add(rest)
        text.add(line)

        if (problem.category == ProblemCategory.LONG_SUSPENDED) {
            text.add(suspensionSiteLabel(problem.coroutineId, clicks))
        }
        return text
    }

    private fun suspensionSiteLabel(
        coroutineId: String,
        clicks: MouseListener,
    ): JBLabel {
        val site = wireLabel("")
        site.font = Font(Font.MONOSPACED, Font.PLAIN, site.font.size)
        site.foreground = JBColor.GRAY
        site.alignmentX = Component.LEFT_ALIGNMENT
        site.isVisible = false
        site.addMouseListener(clicks)
        siteLabelsById[coroutineId] = site
        return site
    }

    /** A wire-fed label with HTML rendering disabled (T-15-01): a "&lt;html&gt;…" name stays literal. */
    private fun wireLabel(text: String): JBLabel {
        val label = JBLabel(text)
        label.putClientProperty("html.disable", true)
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    companion object {
        private const val ROOT_PADDING = 6
        private const val ROW_PADDING = 4
        private const val ROW_HGAP = 8
        private const val SINGLE_CLICK = 1
        private const val DOUBLE_CLICK = 2

        private val DANGER = JBColor(0xD32F2F, 0xFF6B68)
        private val AMBER = JBColor(0xF5A524, 0xF5A524)

        /** Category badge glyph: exception is ✗; leaks + long-suspended share the amber ⚠. */
        private fun badgeText(category: ProblemCategory): String =
            when (category) {
                ProblemCategory.EXCEPTION -> "✗"
                ProblemCategory.LEAK, ProblemCategory.LONG_SUSPENDED -> "⚠"
            }

        private fun badgeColor(category: ProblemCategory): JBColor =
            when (category) {
                ProblemCategory.EXCEPTION -> DANGER
                ProblemCategory.LEAK, ProblemCategory.LONG_SUSPENDED -> AMBER
            }

        /** One-line row text: "{badge} {name} · {age} · {why}". Pure; the render gate. */
        fun rowText(problem: Problem): String = "${badgeText(problem.category)} ${problem.name} · ${problem.ageLabel} · ${problem.why}"
    }
}
