package com.jh.coroutinevisualizer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Persistent settings for the Coroutine Visualizer plugin.
 */
@Service(Service.Level.APP)
@State(
    name = "CoroutineVisualizerSettings",
    storages = [Storage("coroutineVisualizer.xml")],
)
class VisualizerSettings : PersistentStateComponent<VisualizerSettings.State> {
    data class State(
        var autoStartReceiver: Boolean = true,
        var receiverPort: Int = 8090,
        var maxEventRetention: Int = 10000,
        var refreshRateMs: Int = 500,
        var showTreePanel: Boolean = true,
        var showTimelinePanel: Boolean = true,
        var showEventLogPanel: Boolean = true,
    )

    private var myState = State()

    val autoStartReceiver: Boolean get() = myState.autoStartReceiver
    val receiverPort: Int get() = myState.receiverPort
    val maxEventRetention: Int get() = myState.maxEventRetention
    val refreshRateMs: Int get() = myState.refreshRateMs

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): VisualizerSettings = ApplicationManager.getApplication().getService(VisualizerSettings::class.java)
    }
}

/**
 * Settings page in IntelliJ Preferences → Tools → Coroutine Visualizer.
 */
class VisualizerSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var autoStartCheckbox: JCheckBox? = null
    private var portField: JTextField? = null
    private var retentionField: JTextField? = null
    private var refreshField: JTextField? = null

    override fun getDisplayName(): String = "Coroutine Visualizer"

    override fun createComponent(): JComponent {
        val settings = VisualizerSettings.getInstance()

        panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                autoStartCheckbox = JCheckBox("Auto-start event receiver on project open", settings.autoStartReceiver)
                add(autoStartCheckbox)
                add(Box.createVerticalStrut(8))

                add(
                    createLabeledField("Event receiver port:", settings.state.receiverPort.toString())
                        .also {
                            portField = it.second
                        }.first,
                )
                add(Box.createVerticalStrut(4))

                add(
                    createLabeledField("Max event retention:", settings.state.maxEventRetention.toString())
                        .also {
                            retentionField = it.second
                        }.first,
                )
                add(Box.createVerticalStrut(4))

                add(
                    createLabeledField("Refresh rate (ms):", settings.state.refreshRateMs.toString())
                        .also {
                            refreshField = it.second
                        }.first,
                )
            }

        return panel!!
    }

    private fun createLabeledField(
        label: String,
        defaultValue: String,
    ): Pair<JPanel, JTextField> {
        val field = JTextField(defaultValue, 10)
        val row =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel(label))
                add(Box.createHorizontalStrut(8))
                add(field)
                add(Box.createHorizontalGlue())
            }
        return row to field
    }

    override fun isModified(): Boolean {
        val settings = VisualizerSettings.getInstance()
        return autoStartCheckbox?.isSelected != settings.autoStartReceiver ||
            portField?.text != settings.state.receiverPort.toString() ||
            retentionField?.text != settings.state.maxEventRetention.toString() ||
            refreshField?.text != settings.state.refreshRateMs.toString()
    }

    override fun apply() {
        val settings = VisualizerSettings.getInstance()
        settings.loadState(
            VisualizerSettings.State(
                autoStartReceiver = autoStartCheckbox?.isSelected ?: true,
                receiverPort = portField?.text?.toIntOrNull() ?: 8090,
                maxEventRetention = retentionField?.text?.toIntOrNull() ?: 10000,
                refreshRateMs = refreshField?.text?.toIntOrNull() ?: 500,
            ),
        )
    }

    override fun reset() {
        val settings = VisualizerSettings.getInstance()
        autoStartCheckbox?.isSelected = settings.autoStartReceiver
        portField?.text = settings.state.receiverPort.toString()
        retentionField?.text = settings.state.maxEventRetention.toString()
        refreshField?.text = settings.state.refreshRateMs.toString()
    }
}
