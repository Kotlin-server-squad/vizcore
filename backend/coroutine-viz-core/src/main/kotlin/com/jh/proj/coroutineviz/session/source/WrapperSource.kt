package com.jh.proj.coroutineviz.session.source

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Formalizes today's manual-wrapper instrumentation (`VizScope` and siblings)
 * as a single [InstrumentationSource] (Phase 6 RCO-01).
 *
 * CRITICAL — zero behavior change (RCO-01 "minimal behavior change",
 * 06-RESEARCH.md §Refactor seam): this class wraps LIFECYCLE only. It does NOT
 * move, edit, or re-route any emission code out of [VizScope] or the other
 * wrappers. Those wrappers continue to construct events and call
 * `session.send(...)` exactly as before. Every existing `VizScope*` regression
 * test passes unchanged because their emission path is untouched.
 *
 * Lifecycle:
 * - [start] is idempotent; it flips the running flag and enables the session's
 *   job monitoring (`VizSession.enableJobMonitoring()` is itself already
 *   idempotent), mirroring how wrapper-driven sessions are activated today.
 * - [stop] is idempotent; on the true->false transition it disables the job
 *   monitoring it enabled in [start] (`VizSession.disableJobMonitoring()` is
 *   itself idempotent), honoring the [InstrumentationSource] contract that
 *   `stop()` MUST release the resources the source acquired (WR-07). Without
 *   this, `isRunning` reported false while the monitor kept emitting into the
 *   session. `VizSession.close()` also disables monitoring on session close, so
 *   the two paths are mutually idempotent and never double-tear-down.
 */
class WrapperSource(
    private val session: VizSession,
) : InstrumentationSource {
    override val sourceId: String = "wrapper"

    private val running = AtomicBoolean(false)

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        // Idempotent: only enable monitoring on the false->true transition.
        if (running.compareAndSet(false, true)) {
            session.enableJobMonitoring()
        }
    }

    override fun stop() {
        // Idempotent: only release on the true->false transition. Disable the job
        // monitoring we enabled in start() so the source actually relinquishes the
        // resource it acquired and isRunning=false is truthful (WR-07). The call is
        // itself idempotent and mutually safe with VizSession.close().
        if (running.compareAndSet(true, false)) {
            session.disableJobMonitoring()
        }
    }

    /**
     * Convenience factory exposing the existing [VizScope] for callers that want
     * to route construction through the source. Optional (Research Open Q3): the
     * requirement is lifecycle wrapping, not re-routing — the returned scope uses
     * the unchanged wrapper emission path.
     */
    fun scope(context: CoroutineContext = EmptyCoroutineContext): VizScope = VizScope(session, context)
}
