package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jh.coroutinevisualizer.model.SessionTiles
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Header strip of small metric tiles (Coroutines / Active / Suspended / Leak risk / Dispatchers).
 * The label→value mapping lives in the pure [tileValues] helper (unit-tested); this panel only
 * renders those values as theme-aware tiles. The leak-risk tile turns amber when its value is > 0.
 */
class MetricTilesPanel : JPanel(FlowLayout(FlowLayout.LEFT, TILE_GAP, TILE_GAP)) {
    init {
        isOpaque = false
    }

    /** Re-renders all tiles from the latest session counts. Call on the EDT. */
    fun update(tiles: SessionTiles) {
        removeAll()
        val isLeak = tiles.leakRisk > 0
        for ((index, pair) in tileValues(tiles).withIndex()) {
            val (label, value) = pair
            val highlight = index == LEAK_RISK_INDEX && isLeak
            add(buildTile(label, value, highlight))
        }
        revalidate()
        repaint()
    }

    private fun buildTile(
        label: String,
        value: String,
        highlight: Boolean,
    ): JPanel {
        val tile = JPanel()
        tile.layout = BoxLayout(tile, BoxLayout.Y_AXIS)
        tile.isOpaque = false
        tile.border =
            JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(TILE_PADDING),
            )

        val valueLabel = JBLabel(value)
        valueLabel.font = valueLabel.font.deriveFont(Font.BOLD, VALUE_FONT_SIZE)
        if (highlight) {
            valueLabel.foreground = AMBER
        }
        valueLabel.alignmentX = Component.LEFT_ALIGNMENT

        val captionLabel = JBLabel(label)
        captionLabel.font = captionLabel.font.deriveFont(CAPTION_FONT_SIZE)
        captionLabel.foreground = JBColor.GRAY
        captionLabel.alignmentX = Component.LEFT_ALIGNMENT

        tile.add(valueLabel)
        tile.add(captionLabel)
        return tile
    }

    companion object {
        private const val TILE_GAP = 6
        private const val TILE_PADDING = 8
        private const val LEAK_RISK_INDEX = 3
        private const val VALUE_FONT_SIZE = 16f
        private const val CAPTION_FONT_SIZE = 11f
        private const val AMBER_RGB = 0xF5A524
        private val AMBER = JBColor(Color(AMBER_RGB), Color(AMBER_RGB))

        /** Ordered label→value pairs for the header tiles. Pure; the rendering gate. */
        fun tileValues(tiles: SessionTiles): List<Pair<String, String>> =
            listOf(
                "Coroutines" to tiles.total.toString(),
                "Active" to tiles.active.toString(),
                "Suspended" to tiles.suspended.toString(),
                "Leak risk" to tiles.leakRisk.toString(),
                "Dispatchers" to tiles.dispatchers.toString(),
            )
    }
}
