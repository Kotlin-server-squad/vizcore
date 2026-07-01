package com.jh.coroutinevisualizer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent settings for the vizcore plugin.
 *
 * Holds the single user-configured backend URL the plugin points at (D-04/D-05).
 * Both [com.jh.coroutinevisualizer.health.BackendHealthCheck] and the loopback
 * `/api` reverse-proxy use ONLY this configured URL as their target — never a
 * request-supplied one (SSRF guard, V5).
 *
 * Persists to `coroutineVisualizer.xml`; the stale legacy state in that file
 * (port/retention/refresh from the deleted [VisualizerSettings]) is inert and
 * simply ignored on load.
 */
@Service(Service.Level.APP)
@State(
    name = "VizcoreSettings",
    storages = [Storage("coroutineVisualizer.xml")],
)
class VizcoreSettings : PersistentStateComponent<VizcoreSettings.State> {
    data class State(
        var backendUrl: String = DEFAULT_BACKEND_URL,
        var pollIntervalMs: Int = DEFAULT_POLL_INTERVAL_MS,
    )

    private var myState = State()

    var backendUrl: String
        get() = myState.backendUrl
        set(value) {
            myState.backendUrl = value
        }

    /**
     * Live-view poll interval in ms. Clamped on read to a sane minimum so a corrupt persisted
     * value can never spin the poll loop into a tight busy-loop.
     */
    var pollIntervalMs: Int
        get() = clampPollIntervalMs(myState.pollIntervalMs)
        set(value) {
            myState.pollIntervalMs = clampPollIntervalMs(value)
        }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        const val DEFAULT_BACKEND_URL: String = "http://localhost:8080"
        const val DEFAULT_POLL_INTERVAL_MS: Int = 200
        const val MIN_POLL_INTERVAL_MS: Int = 50
        const val MAX_POLL_INTERVAL_MS: Int = 5000

        /** Pure clamp so a corrupt/out-of-range persisted value is coerced into the supported range. */
        fun clampPollIntervalMs(value: Int): Int = value.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)

        fun getInstance(): VizcoreSettings = ApplicationManager.getApplication().getService(VizcoreSettings::class.java)
    }
}
