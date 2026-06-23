package com.jh.coroutinevisualizer.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.jh.coroutinevisualizer.PluginSessionManager
import com.jh.coroutinevisualizer.receiver.PluginEventReceiver

/**
 * Action: "Run with Coroutine Visualizer"
 *
 * Ensures the event receiver is running and opens the tool window
 * before executing the current run configuration.
 */
class RunWithVisualizerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Ensure event receiver is running
        val receiver = PluginEventReceiver.getInstance(project)
        if (!receiver.isRunning) {
            receiver.start()
        }

        // Reset session for a fresh run
        PluginSessionManager.closeSession(project)
        PluginSessionManager.getOrCreateSession(project)

        // Open tool window
        val toolWindowManager =
            com.intellij.openapi.wm.ToolWindowManager
                .getInstance(project)
        toolWindowManager.getToolWindow("Coroutine Visualizer")?.show()

        // TODO: Execute the current run configuration with -javaagent or classpath modifications
        // This would inject the coroutine-viz-core library into the target application
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
