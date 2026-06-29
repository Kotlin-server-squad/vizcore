package com.jh.coroutinevisualizer.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action: "Run with Coroutine Visualizer".
 *
 * Compiling shell only. The real launch sequence (mint correlation,
 * health-check the backend, arm launch state, run the configuration, open the
 * JCEF tool window) is rebuilt in Plan 05 once the new infrastructure
 * (run-configuration extension, loopback server, tool window, settings) lands.
 */
class RunWithVisualizerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project ?: return
        // Intentionally a no-op until Plan 05 rebuilds the launch sequence.
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
