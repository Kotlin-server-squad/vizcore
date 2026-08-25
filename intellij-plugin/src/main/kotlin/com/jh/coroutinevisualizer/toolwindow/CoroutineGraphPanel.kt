package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Thin Swing painter for the parent->child coroutine graph. All geometry comes from [GraphLayout]
 * (pure, unit-tested); this class only renders the positioned model and hit-tests clicks. No
 * networking or threading — the caller feeds models on the EDT and wraps this in a JBScrollPane.
 */
class CoroutineGraphPanel(
    private val onSelect: (String) -> Unit,
) : JComponent() {
    private var graph: GraphModel = GraphModel(emptyList(), emptyList(), 0, 0)
    private var selectedId: String? = null

    init {
        isOpaque = true
        // Non-null registers this component with the ToolTipManager; getToolTipText resolves per node.
        toolTipText = ""
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    handleClick(e.x, e.y)
                }
            },
        )
    }

    fun setModel(graph: GraphModel) {
        this.graph = graph
        if (selectedId != null && graph.nodes.none { it.id == selectedId }) {
            selectedId = null
        }
        preferredSize = Dimension(graph.width.coerceAtLeast(1), graph.height.coerceAtLeast(1))
        revalidate()
        repaint()
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val node =
            graph.nodes.firstOrNull { node ->
                e.x >= node.x &&
                    e.x <= node.x + GraphLayout.NODE_W &&
                    e.y >= node.y &&
                    e.y <= node.y + GraphLayout.NODE_H
            } ?: return null
        val where = node.threadName ?: node.dispatcherName ?: "—"
        return "${node.name} — ${node.state} — $where"
    }

    private fun handleClick(
        x: Int,
        y: Int,
    ) {
        val hit =
            graph.nodes.firstOrNull { node ->
                x >= node.x &&
                    x <= node.x + GraphLayout.NODE_W &&
                    y >= node.y &&
                    y <= node.y + GraphLayout.NODE_H
            } ?: return
        selectedId = hit.id
        repaint()
        onSelect(hit.id)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = background
            g2.fillRect(0, 0, width, height)
            paintEdges(g2)
            paintNodes(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun paintEdges(g2: Graphics2D) {
        val byId = graph.nodes.associateBy { it.id }
        g2.color = EDGE_COLOR
        g2.stroke = BasicStroke(EDGE_WIDTH)
        for (edge in graph.edges) {
            val from = byId[edge.fromId]
            val to = byId[edge.toId]
            if (from != null && to != null) {
                val x1 = from.x + GraphLayout.NODE_W / 2
                val y1 = from.y + GraphLayout.NODE_H
                val x2 = to.x + GraphLayout.NODE_W / 2
                val y2 = to.y
                g2.drawLine(x1, y1, x2, y2)
            }
        }
    }

    private fun paintNodes(g2: Graphics2D) {
        for (node in graph.nodes) {
            paintNode(g2, node)
        }
    }

    private fun paintNode(
        g2: Graphics2D,
        node: GraphNode,
    ) {
        val fill = CoroutineStateStyle.color(CoroutineStateStyle.of(node.state))
        g2.color = fill
        g2.fillRoundRect(node.x, node.y, GraphLayout.NODE_W, GraphLayout.NODE_H, ARC, ARC)

        val borderColor: Color
        val borderWidth: Float
        when {
            node.id == selectedId -> {
                borderColor = SELECTED_BORDER
                borderWidth = SELECTED_BORDER_WIDTH
            }
            node.isLeak -> {
                borderColor = CoroutineStateStyle.leakColor()
                borderWidth = LEAK_BORDER_WIDTH
            }
            else -> {
                borderColor = NODE_BORDER
                borderWidth = EDGE_WIDTH
            }
        }
        g2.color = borderColor
        g2.stroke = BasicStroke(borderWidth)
        g2.drawRoundRect(node.x, node.y, GraphLayout.NODE_W, GraphLayout.NODE_H, ARC, ARC)

        paintLabel(g2, node)
    }

    private fun paintLabel(
        g2: Graphics2D,
        node: GraphNode,
    ) {
        g2.color = LABEL_COLOR
        val fm = g2.fontMetrics
        val maxTextWidth = GraphLayout.NODE_W - TEXT_PADDING * 2
        val text = truncate(node.name, fm, maxTextWidth)
        val textX = node.x + TEXT_PADDING
        val textY = node.y + (GraphLayout.NODE_H - fm.height) / 2 + fm.ascent
        g2.drawString(text, textX, textY)
    }

    private fun truncate(
        text: String,
        fm: java.awt.FontMetrics,
        maxWidth: Int,
    ): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private companion object {
        const val ARC = 10
        const val TEXT_PADDING = 8
        const val EDGE_WIDTH = 1f
        const val LEAK_BORDER_WIDTH = 2f
        const val SELECTED_BORDER_WIDTH = 2f

        val EDGE_COLOR: JBColor = JBColor(Color(0xB0B0B0), Color(0x5A5A5A))
        val NODE_BORDER: JBColor = JBColor(Color(0x909090), Color(0x707070))
        val LABEL_COLOR: JBColor = JBColor(Color(0xFFFFFF), Color(0xFFFFFF))
        val SELECTED_BORDER: JBColor =
            JBUI.CurrentTheme.Focus
                .focusColor()
                .let { JBColor(it, it) }
    }
}
