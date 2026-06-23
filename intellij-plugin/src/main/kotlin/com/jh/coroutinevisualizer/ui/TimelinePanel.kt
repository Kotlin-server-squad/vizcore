package com.jh.coroutinevisualizer.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.jh.proj.coroutineviz.models.CoroutineState
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Custom panel that draws a horizontal timeline showing coroutine state segments.
 * Each coroutine gets a lane, and colored segments show active/suspended/completed states.
 */
class TimelinePanel(
    private val project: Project,
    private val session: VizSession,
) : JPanel() {
    private val timelineCanvas = TimelineCanvas(session)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        layout = BorderLayout()
        add(JBScrollPane(timelineCanvas), BorderLayout.CENTER)

        // Refresh on new events
        scope.launch {
            session.coroutineLifecycleFlow().collect {
                SwingUtilities.invokeLater {
                    timelineCanvas.repaint()
                }
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}

/**
 * Canvas that renders the timeline visualization.
 */
private class TimelineCanvas(
    private val session: VizSession,
) : JPanel() {
    companion object {
        const val LANE_HEIGHT = 30
        const val LANE_GAP = 4
        const val LABEL_WIDTH = 150
        const val PADDING = 10
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val coroutines =
            session.snapshot.coroutines.values
                .toList()
        if (coroutines.isEmpty()) {
            g2.color = Color.GRAY
            g2.drawString("No coroutines yet — run a scenario to see the timeline", PADDING, 30)
            return
        }

        val events = session.store.all()
        if (events.isEmpty()) return

        val minTime = events.minOf { it.tsNanos }
        val maxTime = events.maxOf { it.tsNanos }
        val timeRange = maxTime - minTime
        if (timeRange == 0L) return

        val availableWidth = (width - LABEL_WIDTH - PADDING * 2).coerceAtLeast(100)

        coroutines.forEachIndexed { index, coroutine ->
            val y = PADDING + index * (LANE_HEIGHT + LANE_GAP)

            // Draw label
            g2.color = Color.DARK_GRAY
            g2.font = Font("Monospaced", Font.PLAIN, 11)
            val label = coroutine.label ?: coroutine.id.takeLast(10)
            g2.drawString(label, PADDING, y + LANE_HEIGHT / 2 + 4)

            // Draw state bar
            val x = LABEL_WIDTH + PADDING
            val barWidth = availableWidth

            // Background
            g2.color = Color(240, 240, 240)
            g2.fillRoundRect(x, y, barWidth, LANE_HEIGHT, 4, 4)

            // State color fill
            g2.color = getStateColor(coroutine.state)
            g2.fillRoundRect(x, y, barWidth, LANE_HEIGHT, 4, 4)

            // Border
            g2.color = getStateColor(coroutine.state).darker()
            g2.drawRoundRect(x, y, barWidth, LANE_HEIGHT, 4, 4)
        }

        // Set preferred size for scrolling
        val totalHeight = PADDING * 2 + coroutines.size * (LANE_HEIGHT + LANE_GAP)
        preferredSize = Dimension(width, totalHeight.coerceAtLeast(100))
    }

    private fun getStateColor(state: CoroutineState): Color =
        when (state) {
            CoroutineState.CREATED -> Color(200, 200, 200)
            CoroutineState.ACTIVE -> Color(99, 102, 241) // Indigo
            CoroutineState.SUSPENDED -> Color(245, 158, 11) // Amber
            CoroutineState.WAITING_FOR_CHILDREN -> Color(168, 85, 247) // Purple
            CoroutineState.COMPLETED -> Color(34, 197, 94) // Green
            CoroutineState.CANCELLED -> Color(156, 163, 175) // Gray
            CoroutineState.FAILED -> Color(239, 68, 68) // Red
        }
}
