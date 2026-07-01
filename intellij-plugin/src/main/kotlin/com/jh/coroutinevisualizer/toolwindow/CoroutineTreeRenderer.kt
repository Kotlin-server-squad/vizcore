package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.jh.coroutinevisualizer.model.CoroutineRow
import java.awt.Color
import javax.swing.JTree
import javax.swing.Timer

/**
 * Swing tree-cell renderer for [CoroutineRow] nodes. Draws a colored state tag, the coroutine name,
 * a state badge, dispatcher chip, approximate age, and child count. When a row's state changes
 * between polls it flashes briefly via a [Timer]-driven background highlight — the native
 * "what just happened" cue, no web animation.
 *
 * All non-visual decisions live in [CoroutineStateStyle] / [FlashTracker] which are unit-tested.
 */
class CoroutineTreeRenderer : ColoredTreeCellRenderer() {
    private val flashTracker = FlashTracker()

    /** ids currently flashing -> EDT timer that clears the highlight and repaints. */
    private val flashing = mutableMapOf<String, Timer>()

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val userObject = (value as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
        val coroutine = userObject as? CoroutineRow ?: return

        if (flashTracker.shouldFlash(coroutine.id, coroutine.state)) {
            startFlash(coroutine.id, tree)
        }
        if (!selected && flashing.containsKey(coroutine.id)) {
            background = FLASH_BACKGROUND
        }

        val style = CoroutineStateStyle.of(coroutine.state)
        val accent = if (coroutine.isLeak) CoroutineStateStyle.leakColor() else CoroutineStateStyle.color(style)

        append("● ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, accent))
        append(coroutine.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("  ${CoroutineStateStyle.badgeText(coroutine.state)}", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, accent))

        coroutine.dispatcherName?.let { dispatcher ->
            append("  @$dispatcher", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        coroutine.threadName?.takeIf { it.isNotBlank() }?.let { thread ->
            append("  @$thread", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        val ageLabel = CoroutineStateStyle.ageLabel(coroutine.ageMs)
        if (coroutine.isLeak) {
            append("  ⚠ $ageLabel", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, CoroutineStateStyle.leakColor()))
        } else {
            append("  $ageLabel", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        if (coroutine.childCount > 0) {
            append("  (${coroutine.childCount})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private fun startFlash(
        id: String,
        tree: JTree,
    ) {
        flashing.remove(id)?.stop()
        val timer =
            Timer(FLASH_DURATION_MS) {
                flashing.remove(id)
                tree.repaint()
            }
        timer.isRepeats = false
        flashing[id] = timer
        timer.start()
        tree.repaint()
    }

    private companion object {
        const val FLASH_DURATION_MS = 1200
        val FLASH_BACKGROUND: JBColor = JBColor(Color(0xFFF3D6), Color(0x4A3D1A))
    }
}
