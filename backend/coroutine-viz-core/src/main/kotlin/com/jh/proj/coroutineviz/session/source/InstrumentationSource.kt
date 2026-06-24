package com.jh.proj.coroutineviz.session.source

import com.jh.proj.coroutineviz.session.VizSession

/**
 * A pluggable producer of [com.jh.proj.coroutineviz.events.VizEvent]s for a
 * single [VizSession] (Phase 6 RCO-01).
 *
 * An `InstrumentationSource` sits IN FRONT OF `VizSession.send`: it produces
 * events by calling `session.send(event)` — exactly the contract today's
 * wrappers (`VizScope` et al.) already use. The downstream pipeline
 * (`EventStore`, `EventApplier`/snapshot, `EventBus`, SSE, frontend) is
 * UNCHANGED; the interface adds no method to `VizSession`.
 *
 * Each implementation is constructed WITH its target [VizSession] (constructor
 * arg), so a source always knows where to emit. Multiple sources may run
 * concurrently against the same session and be toggled independently — stopping
 * one source must not stop another bound to the same session.
 *
 * Lifecycle contract:
 * - [start] MUST be idempotent: calling it while already running is a no-op.
 * - [stop] MUST be idempotent: calling it while already stopped is a no-op, and
 *   it MUST release any resources the source acquired (poll loops, JVM-global
 *   installs such as `DebugProbes`). Because such resources can be process-wide,
 *   the backend/source installer is expected to wire `stop()` to session close
 *   via [com.jh.proj.coroutineviz.session.SessionManager.addOnSessionClosed], so
 *   a source is always torn down when its session is closed or evicted (no
 *   long-lived leak — relevant to Plan 06-02 `DebugProbesSource`).
 */
interface InstrumentationSource {
    /** Stable identifier for attribution and independent toggling (e.g. "wrapper", "debugprobes"). */
    val sourceId: String

    /** Health flag: true between a successful [start] and the next [stop]. */
    val isRunning: Boolean

    /** Begin producing events into the bound session. Idempotent. */
    fun start()

    /** Stop producing and release resources. Idempotent. */
    fun stop()
}
