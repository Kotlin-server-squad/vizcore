package com.jh.coroutinevisualizer.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

class VizcoreToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.add(JLabel("Coroutine Visualizer — native view (under construction)."))
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
