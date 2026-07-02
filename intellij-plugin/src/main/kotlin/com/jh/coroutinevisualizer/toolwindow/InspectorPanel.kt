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
@Suppress("TooManyFunctions") // presentational panel built from many small card/label helpers
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
            val label = JBLabel("Select a coroutine")
            label.horizontalAlignment = SwingConstants.CENTER
            label.foreground = JBColor.GRAY
            add(label, BorderLayout.CENTER)
        } else {
            add(content(vm), BorderLayout.NORTH)
        }
        revalidate()
        repaint()
    }

    private fun content(vm: InspectorViewModel): Component {
        val column = JPanel()
        column.layout = BoxLayout(column, BoxLayout.Y_AXIS)
        column.isOpaque = false

        // D-24 / sketch 005-A most-diagnostic-first order: exception (when thrown) → timing →
        // suspended-at (+ its suspension history + the future multi-frame stack placeholder) →
        // launched-at → runs-on → identity → events.
        val status = if (vm.running) "running" else "completed"
        column.add(header(vm))
        if (vm.exceptionType != null) {
            column.add(exceptionCard(vm))
        }
        column.add(
            captionedRows(
                "Timing",
                listOf(
                    "$status  ·  lifetime ${vm.lifetimeLabel}",
                    "active ${vm.activeLabel}  ·  suspended ${vm.suspendedLabel}  ·  total ${vm.totalLabel}",
                ),
            ),
        )
        column.add(sourceRow("Suspended at", vm.suspendedAt))
        if (vm.suspensionHistory.isNotEmpty()) {
            val history = leftColumn()
            history.border = JBUI.Borders.emptyTop(ROW_GAP)
            history.add(caption("Suspension history"))
            vm.suspensionHistory.forEach { ref -> appendRef(history, ref) }
            column.add(history)
        }
        column.add(placeholderCard())
        column.add(sourceRow("Launched at", vm.launchedAt))
        column.add(captionedRows("Runs on", listOf("${vm.threadName ?: EMPTY_VALUE} · ${vm.dispatcherName ?: EMPTY_VALUE}")))
        column.add(
            captionedRows(
                "Identity",
                listOf(
                    "job ${vm.jobId ?: EMPTY_VALUE}",
                    "scope ${vm.scopeId ?: EMPTY_VALUE}",
                    "children ${vm.activeChildrenCount} active / ${vm.childrenCount} total",
                ),
            ),
        )
        if (vm.events.isNotEmpty()) {
            val lines =
                vm.events.map { event ->
                    val reason =
                        event.reason
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " · $it" }
                            .orEmpty()
                    "${event.relativeLabel}  ${event.kind}$reason"
                }
            column.add(captionedRows("Events", lines))
        }
        return column
    }

    /**
     * Placeholder for the future multi-frame suspension stack (D-24, sketch 005). It sits directly
     * under the suspension block because that is where the real stack will land once the backend
     * DebugProbes-stack change ships; for now it is a static, wire-free label (no HTML path).
     */
    private fun placeholderCard(): Component {
        val panel = leftColumn()
        panel.border = JBUI.Borders.emptyTop(ROW_GAP)
        panel.add(caption("Stack trace"))
        val note = JBLabel("Multi-frame suspension stacks coming soon — single frame shown above.")
        note.foreground = JBColor.GRAY
        note.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(note)
        return panel
    }

    private fun exceptionCard(vm: InspectorViewModel): Component {
        val panel = leftColumn()
        panel.border =
            JBUI.Borders.compound(
                JBUI.Borders.emptyTop(ROW_GAP),
                JBUI.Borders.customLine(DANGER_COLOR, 0, LEFT_BORDER, 0, 0),
            )

        val captionLabel = caption("Exception")
        captionLabel.border = JBUI.Borders.emptyLeft(CARD_INSET)
        panel.add(captionLabel)

        val type = noHtml(JBLabel(vm.exceptionType.orEmpty()))
        type.foreground = DANGER_COLOR
        type.font = type.font.deriveFont(Font.BOLD)
        type.alignmentX = Component.LEFT_ALIGNMENT
        type.border = JBUI.Borders.emptyLeft(CARD_INSET)
        panel.add(type)

        vm.exceptionMessage?.takeIf { it.isNotBlank() }?.let { message ->
            val label = noHtml(JBLabel(message))
            label.alignmentX = Component.LEFT_ALIGNMENT
            label.border = JBUI.Borders.emptyLeft(CARD_INSET)
            panel.add(label)
        }
        return panel
    }

    private fun header(vm: InspectorViewModel): Component {
        val panel = leftColumn()

        val title = noHtml(JBLabel("${vm.name}  ·  ${vm.state}"))
        title.font = title.font.deriveFont(Font.BOLD, TITLE_FONT_SIZE)
        title.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(title)

        if (vm.identity.isNotBlank()) {
            val identity = noHtml(JBLabel(vm.identity))
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
        panel.add(caption(caption))
        if (ref == null) {
            panel.add(value(EMPTY_VALUE))
        } else {
            appendRef(panel, ref)
        }
        return panel
    }

    /** Appends a source reference (reason + file:line + Jump button) to an existing column. */
    private fun appendRef(
        panel: JPanel,
        ref: SourceRef,
    ) {
        ref.reason?.takeIf { it.isNotBlank() }?.let { panel.add(value(it)) }
        val file = ref.fileName
        val line = ref.lineNumber
        if (file != null && line != null) {
            panel.add(value("$file:$line"))
            val jump = JButton("Jump")
            jump.alignmentX = Component.LEFT_ALIGNMENT
            jump.addActionListener { onJump(file, line) }
            panel.add(jump)
        } else if (file != null) {
            panel.add(value(file))
        }
    }

    private fun captionedRows(
        caption: String,
        lines: List<String>,
    ): Component {
        val panel = leftColumn()
        panel.border = JBUI.Borders.emptyTop(ROW_GAP)
        panel.add(caption(caption))
        lines.forEach { panel.add(value(it)) }
        return panel
    }

    private fun leftColumn(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.alignmentX = Component.LEFT_ALIGNMENT
        return panel
    }

    private fun caption(text: String): JBLabel {
        val label = noHtml(JBLabel(text))
        label.foreground = JBColor.GRAY
        label.font = label.font.deriveFont(Font.BOLD, SMALL_FONT_SIZE)
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    private fun value(text: String): Component {
        val label = noHtml(JBLabel(text))
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    /**
     * Disables Swing HTML rendering on a wire-fed label (T-15-01): a "&lt;html&gt;…" name, state,
     * identity, exception message, or event reason stays literal — never markup, never a remote img.
     */
    private fun noHtml(label: JBLabel): JBLabel {
        label.putClientProperty("html.disable", true)
        return label
    }

    private companion object {
        const val ROOT_PADDING = 10
        const val ROW_GAP = 10
        const val TITLE_FONT_SIZE = 15f
        const val SMALL_FONT_SIZE = 11f
        const val EMPTY_VALUE = "—"
        const val LEFT_BORDER = 2
        const val CARD_INSET = 8
        val DANGER_COLOR = JBColor(0xD32F2F, 0xFF6B68)
    }
}
