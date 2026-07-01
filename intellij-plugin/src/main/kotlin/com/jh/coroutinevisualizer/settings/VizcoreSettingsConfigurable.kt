package com.jh.coroutinevisualizer.settings

import com.intellij.openapi.options.Configurable
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

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
    private var pollIntervalSpinner: JSpinner? = null

    override fun getDisplayName(): String = "Coroutine Visualizer"

    override fun createComponent(): JComponent {
        val settings = VizcoreSettings.getInstance()
        val field = JTextField(settings.backendUrl, FIELD_COLUMNS)
        backendUrlField = field

        val spinner =
            JSpinner(
                SpinnerNumberModel(
                    settings.pollIntervalMs,
                    VizcoreSettings.MIN_POLL_INTERVAL_MS,
                    VizcoreSettings.MAX_POLL_INTERVAL_MS,
                    POLL_INTERVAL_STEP,
                ),
            )
        pollIntervalSpinner = spinner

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
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        add(JLabel("Poll interval (ms):"))
                        add(Box.createHorizontalStrut(HORIZONTAL_STRUT))
                        add(spinner)
                        add(Box.createHorizontalGlue())
                    },
                )
            }.also { panel = it }
    }

    override fun isModified(): Boolean {
        val settings = VizcoreSettings.getInstance()
        return backendUrlField?.text != settings.backendUrl ||
            pollIntervalValue() != settings.pollIntervalMs
    }

    override fun apply() {
        val settings = VizcoreSettings.getInstance()
        val text = backendUrlField?.text?.trim().orEmpty()
        settings.backendUrl = text.ifEmpty { VizcoreSettings.DEFAULT_BACKEND_URL }
        settings.pollIntervalMs = pollIntervalValue()
    }

    override fun reset() {
        val settings = VizcoreSettings.getInstance()
        backendUrlField?.text = settings.backendUrl
        pollIntervalSpinner?.value = settings.pollIntervalMs
    }

    override fun disposeUIResources() {
        panel = null
        backendUrlField = null
        pollIntervalSpinner = null
    }

    private fun pollIntervalValue(): Int = (pollIntervalSpinner?.value as? Int) ?: VizcoreSettings.DEFAULT_POLL_INTERVAL_MS

    private companion object {
        const val FIELD_COLUMNS = 30
        const val HORIZONTAL_STRUT = 8
        const val POLL_INTERVAL_STEP = 50
    }
}
