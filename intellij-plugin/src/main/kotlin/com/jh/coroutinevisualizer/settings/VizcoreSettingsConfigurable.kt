package com.jh.coroutinevisualizer.settings

import com.intellij.openapi.options.Configurable
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Settings page in IntelliJ Preferences → Tools → Coroutine Visualizer.
 *
 * Exposes the single backend-URL field (default `http://localhost:8080`, D-04)
 * backing [VizcoreSettings.State.backendUrl]. Replaces the deleted legacy
 * `VisualizerSettingsConfigurable` (port/retention/refresh fields).
 */
class VizcoreSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var backendUrlField: JTextField? = null

    override fun getDisplayName(): String = "Coroutine Visualizer"

    override fun createComponent(): JComponent {
        val settings = VizcoreSettings.getInstance()
        val field = JTextField(settings.backendUrl, FIELD_COLUMNS)
        backendUrlField = field

        return JPanel()
            .apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        add(JLabel("Backend URL:"))
                        add(Box.createHorizontalStrut(HORIZONTAL_STRUT))
                        add(field)
                        add(Box.createHorizontalGlue())
                    },
                )
            }.also { panel = it }
    }

    override fun isModified(): Boolean = backendUrlField?.text != VizcoreSettings.getInstance().backendUrl

    override fun apply() {
        val text = backendUrlField?.text?.trim().orEmpty()
        VizcoreSettings.getInstance().backendUrl =
            text.ifEmpty { VizcoreSettings.DEFAULT_BACKEND_URL }
    }

    override fun reset() {
        backendUrlField?.text = VizcoreSettings.getInstance().backendUrl
    }

    override fun disposeUIResources() {
        panel = null
        backendUrlField = null
    }

    private companion object {
        const val FIELD_COLUMNS = 30
        const val HORIZONTAL_STRUT = 8
    }
}
