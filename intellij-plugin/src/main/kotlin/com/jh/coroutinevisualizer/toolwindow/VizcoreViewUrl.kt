package com.jh.coroutinevisualizer.toolwindow

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * SINGLE source of truth for the loopback live-view URL the tool window points at.
 *
 * Both the JCEF [com.intellij.ui.jcef.JBCefBrowser] path and the Swing system-browser
 * fallback ([com.intellij.ide.BrowserUtil.browse]) build their URL here so the two paths
 * cannot drift apart (IDE-03 identity; T-13-10). The URL targets the Plan 04
 * [com.jh.coroutinevisualizer.server.LoopbackFrontendServer] (loopback-only, T-13-11) and
 * carries the Phase 9 correlation UUID as a `?correlation=` deep-link consumed by the SPA
 * root (Plan 03 / IDE-03).
 */
object VizcoreViewUrl {
    /**
     * Build `http://127.0.0.1:<port>/?correlation=<correlation>`.
     *
     * @param port the ephemeral loopback port the [LoopbackFrontendServer] bound to.
     * @param correlation the Phase 9 correlation UUID; URL-encoded defensively so a value
     *   containing reserved characters cannot break the query string.
     */
    fun build(
        port: Int,
        correlation: String,
    ): String {
        val encoded = URLEncoder.encode(correlation, StandardCharsets.UTF_8)
        return "http://127.0.0.1:$port/?correlation=$encoded"
    }
}
