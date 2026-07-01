package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Selected-coroutine inspector. Renders the [InspectorViewModel] (built by the pure
 * [InspectorViewModel.from]) as a header plus "Suspended at" / "Launched at" / timing rows. A row
 * with a resolved file:line gets a Jump button that calls [onJump]; otherwise it renders plain text.
 *
 * No networking or threading lives here — the tool window drives [show] from the EDT.
 */
class InspectorPanel(
    private val onJump: (fileName: String, line: Int) -> Unit,
) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(ROOT_PADDING)
        show(null)
    }

    /** Replaces the content with the given view model, or a placeholder when null. Call on the EDT. */
    fun show(vm: InspectorViewModel?) {
        removeAll()
        if (vm == null) {
            add(placeholder(), BorderLayout.CENTER)
        } else {
            add(content(vm), BorderLayout.NORTH)
        }
        revalidate()
        repaint()
    }

    private fun placeholder(): Component {
        val label = JBLabel("Select a coroutine")
        label.horizontalAlignment = SwingConstants.CENTER
        label.foreground = JBColor.GRAY
        return label
    }

    private fun content(vm: InspectorViewModel): Component {
        val column = JPanel()
        column.layout = BoxLayout(column, BoxLayout.Y_AXIS)
        column.isOpaque = false

        column.add(header(vm))
        column.add(sourceRow("Suspended at", vm.suspendedAt))
        column.add(sourceRow("Launched at", vm.launchedAt))
        column.add(timingRow(vm))
        return column
    }

    private fun header(vm: InspectorViewModel): Component {
        val panel = leftColumn()

        val title = JBLabel("${vm.name}  ·  ${vm.state}")
        title.font = title.font.deriveFont(Font.BOLD, TITLE_FONT_SIZE)
        title.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(title)

        if (vm.identity.isNotBlank()) {
            val identity = JBLabel(vm.identity)
            identity.foreground = JBColor.GRAY
            identity.font = identity.font.deriveFont(SMALL_FONT_SIZE)
            identity.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(identity)
        }
        return panel
    }

    private fun sourceRow(
        caption: String,
        ref: SourceRef?,
    ): Component {
        val panel = leftColumn()
        panel.border = JBUI.Borders.emptyTop(ROW_GAP)

        val captionLabel = caption(caption)
        panel.add(captionLabel)

        if (ref == null) {
            panel.add(value(EMPTY_VALUE))
            return panel
        }

        ref.reason?.takeIf { it.isNotBlank() }?.let { panel.add(value(it)) }

        val file = ref.fileName
        val line = ref.lineNumber
        if (file != null && line != null) {
            val location = JBLabel("$file:$line")
            location.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(location)
            val jump = JButton("Jump")
            jump.alignmentX = Component.LEFT_ALIGNMENT
            jump.addActionListener { onJump(file, line) }
            panel.add(jump)
        } else if (file != null) {
            panel.add(value(file))
        }
        return panel
    }

    private fun timingRow(vm: InspectorViewModel): Component {
        val panel = leftColumn()
        panel.border = JBUI.Borders.emptyTop(ROW_GAP)
        panel.add(caption("Timing"))
        panel.add(value("active ${vm.activeLabel}  ·  suspended ${vm.suspendedLabel}  ·  total ${vm.totalLabel}"))
        return panel
    }

    private fun leftColumn(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.alignmentX = Component.LEFT_ALIGNMENT
        return panel
    }

    private fun caption(text: String): Component {
        val label = JBLabel(text)
        label.foreground = JBColor.GRAY
        label.font = label.font.deriveFont(Font.BOLD, SMALL_FONT_SIZE)
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    private fun value(text: String): Component {
        val label = JBLabel(text)
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    private companion object {
        const val ROOT_PADDING = 10
        const val ROW_GAP = 10
        const val TITLE_FONT_SIZE = 15f
        const val SMALL_FONT_SIZE = 11f
        const val EMPTY_VALUE = "—"
    }
}
