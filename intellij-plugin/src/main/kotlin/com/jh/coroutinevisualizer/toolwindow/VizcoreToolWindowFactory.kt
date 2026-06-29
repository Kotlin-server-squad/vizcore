package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Tool window that embeds the bundled React frontend served by the Plan 04
 * [com.jh.coroutinevisualizer.server.LoopbackFrontendServer] (replaces the deleted legacy
 * tabbed `CoroutineVisualizerToolWindowFactory`, D-11; same `ToolWindowFactory` extension point).
 *
 * When JCEF is available ([JBCefApp.isSupported]) a [JBCefBrowser] loads the deep-linked
 * loopback URL. Otherwise (D-10) a Swing fallback panel offers a button that opens the SAME
 * URL in the system browser via [BrowserUtil.browse]. Both paths build their URL through the
 * single [VizcoreViewUrl] builder so they cannot drift (IDE-03; T-13-10). Only public platform
 * APIs are used (Pitfall 7 / T-13-12; verifyPlugin-safe).
 */
class VizcoreToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val url = VizcoreLaunchState.getInstance(project).viewUrl()
        val component: JComponent =
            when {
                url == null -> buildNotLaunchedPanel()
                JBCefApp.isSupported() -> buildJcefPanel(url, toolWindow)
                else -> buildFallbackPanel(url)
            }
        val content = ContentFactory.getInstance().createContent(component, null, false)
        toolWindow.contentManager.addContent(content)
    }

    /** Embed the bundled frontend in a platform-managed JCEF browser at [url]. */
    private fun buildJcefPanel(
        url: String,
        toolWindow: ToolWindow,
    ): JComponent {
        val browser = JBCefBrowser(url)
        Disposer.register(toolWindow.disposable, browser)
        return JPanel(BorderLayout()).apply { add(browser.component, BorderLayout.CENTER) }
    }

    companion object {
        /**
         * Swing fallback panel (D-10): a status label + a button that opens [url] — the SAME
         * URL the JCEF path would load — in the system browser.
         *
         * Factored out and `internal` so the headless [JcefFallbackTest] can build it without a
         * real tool window or a display, and capture the URL the button is wired with without
         * actually invoking [BrowserUtil.browse] (which needs a desktop).
         */
        internal fun buildFallbackPanel(
            url: String,
            onOpen: (String) -> Unit = BrowserUtil::browse,
        ): JPanel {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT))
            panel.add(JLabel("The embedded browser is unavailable on this IDE."))
            panel.add(
                JButton("Open live view in browser").apply {
                    addActionListener { onOpen(url) }
                },
            )
            return panel
        }

        /** Shown until the "Run with Coroutine Visualizer" action arms a launch (Plan 06). */
        internal fun buildNotLaunchedPanel(): JPanel {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT))
            panel.add(JLabel("Run a configuration with \"Run with Coroutine Visualizer\" to open the live view."))
            return panel
        }
    }
}
