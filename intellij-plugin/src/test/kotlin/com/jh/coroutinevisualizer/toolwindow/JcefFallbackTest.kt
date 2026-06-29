package com.jh.coroutinevisualizer.toolwindow

import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComponent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless proof (D-13 — no display, no pixels) that the JCEF-unavailable Swing fallback path
 * targets the SAME correlation-deep-linked URL the JCEF path would load (IDE-02, IDE-03; T-13-10).
 *
 * Does NOT instantiate [com.intellij.ui.jcef.JBCefBrowser] (needs JCEF) and does NOT call
 * [com.intellij.ide.BrowserUtil.browse] (needs a desktop). Instead it injects a capturing
 * `onOpen` seam into [VizcoreToolWindowFactory.buildFallbackPanel] and fires the button's action
 * to record the exact URL the fallback is wired with, then compares it to [VizcoreViewUrl.build].
 */
class JcefFallbackTest {
    @Test
    fun `jcef url and fallback browser url are the identical string for the same port and correlation`() {
        val port = 54321
        val correlation = "corr-abc"

        // The URL the JCEF JBCefBrowser(url) constructor would receive.
        val jcefUrl = VizcoreViewUrl.build(port, correlation)

        // The URL the Swing fallback button is wired to open — captured, not browsed.
        val captured = AtomicReference<String?>(null)
        val panel = VizcoreToolWindowFactory.buildFallbackPanel(jcefUrl) { url -> captured.set(url) }

        val button = findButton(panel)
        assertNotNull(button, "fallback panel must contain a button to open the live view")
        // Firing the action invokes the injected onOpen instead of BrowserUtil.browse (no desktop).
        button.doClick()

        assertEquals(
            jcefUrl,
            captured.get(),
            "fallback browser URL must be identical to the JCEF URL",
        )
        assertEquals("http://127.0.0.1:54321/?correlation=corr-abc", captured.get())
    }

    @Test
    fun `fallback button is labelled to open the live view in the browser`() {
        val panel = VizcoreToolWindowFactory.buildFallbackPanel("http://127.0.0.1:1/?correlation=x") {}
        val button = findButton(panel)
        assertNotNull(button)
        assertTrue(button.text.contains("browser", ignoreCase = true))
    }

    private fun findButton(root: JComponent): AbstractButton? =
        root.components.firstNotNullOfOrNull { child ->
            when (child) {
                is JButton -> child
                is JComponent -> findButton(child)
                else -> null
            }
        }
}
