package com.jh.coroutinevisualizer.toolwindow

import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JScrollPane
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Headless structural gate for the inspector scroll fix (plan 15-10, Task 1). Verifies the card
 * column is wrapped in a PERSISTENT viewport so a tall card stack (exception → timing → source →
 * events) is reachable regardless of pane height, and that the placeholder renders inside that
 * viewport.
 *
 * Plain JUnit, no IDE Application (repo convention — see ProblemsPanelsTest): a bare unit test
 * cannot construct a [com.intellij.ui.components.JBScrollPane] on macOS because its native Mac
 * scrollbar UI asserts a JNA lib in `Foundation.<clinit>`. The test injects a plain [JScrollPane]
 * through InspectorPanel's scroll-pane factory seam; production keeps the JBScrollPane default.
 */
class InspectorPanelScrollTest {
    private fun panel() =
        InspectorPanel(
            onJump = { _, _ -> },
            scrollPaneFactory = { view -> JScrollPane(view) },
        )

    private fun firstScrollPane(root: Container): JScrollPane? =
        root.components.firstNotNullOfOrNull { child ->
            when (child) {
                is JScrollPane -> child
                is Container -> firstScrollPane(child)
                else -> null
            }
        }

    private fun containsLabelText(
        root: Container,
        text: String,
    ): Boolean =
        root.components.any { child ->
            (child is javax.swing.JLabel && child.text == text) ||
                (child is Container && containsLabelText(child, text))
        }

    private fun sampleVm(): InspectorViewModel = InspectorViewModel.from(null, null)

    @Test fun `show renders the card column inside a scroll pane viewport`() {
        val panel = panel()
        panel.show(sampleVm())

        val scroll = firstScrollPane(panel)
        assertNotNull(scroll, "inspector must wrap its content in a scroll pane viewport")
        val view = scroll.viewport.view
        assertTrue(view is Container, "viewport view must be a container holding the card column")
        assertTrue((view as Container).componentCount > 0, "viewport wrapper must hold the card column")
    }

    @Test fun `placeholder renders inside the viewport when nothing is selected`() {
        val panel = panel()
        panel.show(null)

        val scroll = firstScrollPane(panel)
        assertNotNull(scroll, "inspector must always host a scroll pane, even for the placeholder")
        val view = scroll.viewport.view as Container
        assertTrue(containsLabelText(view, "Select a coroutine"), "placeholder must live inside the viewport")
    }

    @Test fun `the scroll pane is the same instance across two show calls`() {
        val panel = panel()
        panel.show(sampleVm())
        val first = firstScrollPane(panel)
        panel.show(null)
        val second = firstScrollPane(panel)

        assertNotNull(first)
        assertNotNull(second)
        assertSame(first, second, "the scroll pane must be persistent, not rebuilt per show()")
    }
}
