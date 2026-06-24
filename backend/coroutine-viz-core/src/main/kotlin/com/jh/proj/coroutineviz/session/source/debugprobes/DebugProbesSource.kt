package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.EventSampler
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * An [InstrumentationSource] (Plan 06-01) that observes a running app's
 * coroutines via kotlinx `DebugProbes` and a snapshot-diffing poll loop, then
 * synthesizes the existing `VizEvent` subtypes from the deltas (RCO-02). Source
 * attribution (RCO-03) — function/file:line from the creation stack, dispatcher
 * + `CoroutineName` from context — rides existing event fields.
 *
 * Design (Research §"Code Examples"): each tick `dump()` →
 * `Map<CoroKey, CoroutineSnapshot>` → [diff] → [DebugProbesEventSynthesizer] →
 * (optional [EventSampler]) → `session.send`. The loop owns its OWN
 * [CoroutineScope] (a `Dispatchers.Default + SupervisorJob`) — NOT
 * `session.sessionScope` — so a slow dump cannot starve event delivery.
 *
 * Per-tick error isolation (06-REVIEWS.md / threat T-06-07): the tick body is
 * wrapped in try/catch. A throwing tick is logged at WARN and the loop CONTINUES.
 * On failure `prev` is NOT advanced, so the next successful tick re-diffs against
 * the last good snapshot and recovers the missed delta. `CancellationException`
 * is rethrown so [stop]/scope-cancellation still works.
 *
 * Lifecycle (06-REVIEWS.md / threat T-06-04): the JVM-global DebugProbes install
 * is reference-counted ([DebugProbesInstall]). [start] registers [stop] against
 * [SessionManager.addOnSessionClosed], so closing/evicting the bound session
 * always releases the install — no long-lived JVM-global leak. [start]/[stop]
 * are idempotent and the source is restartable.
 *
 * Accepted v1 tradeoffs (Research): poll-bounded timing (±~pollInterval) — a
 * coroutine that starts+finishes between polls is invisible (Pitfall 2);
 * `dumpCoroutinesInfo()` returns ALL JVM coroutines (Open Q1, single-app dev
 * session); vanished → `CoroutineCompleted` only (A3); creation stack traces are
 * dev-only and may expose source paths (T-06-03 — do not enable in prod).
 *
 * @param scope injectable so tests drive the loop with `runTest` virtual time.
 * @param installProbes set false in deterministic tests (no real DebugProbes).
 * @param dump injectable `() -> List<CoroutineSnapshot>`; defaults to the real
 *   [DebugProbes.dumpCoroutinesInfo] mapped through [CoroutineInfoAdapter].
 */
class DebugProbesSource(
    private val session: VizSession,
    private val pollInterval: Duration = 150.milliseconds,
    private val sampler: EventSampler? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val installProbes: Boolean = true,
    private val adapter: CoroutineInfoAdapter = CoroutineInfoAdapter(),
    private val dump: () -> List<CoroutineSnapshot> = {
        DebugProbes.dumpCoroutinesInfo().map { adapter.toSnapshot(it) }
    },
) : InstrumentationSource {
    private val logger = LoggerFactory.getLogger(DebugProbesSource::class.java)
    private val synthesizer = DebugProbesEventSynthesizer()

    override val sourceId: String = DebugProbesEventSynthesizer.SOURCE_ID

    private var loop: Job? = null
    private val installed = AtomicBoolean(false)

    // Registered once on first start so the close hook fires stop(); guarded by sessionId.
    private val closeListenerRegistered = AtomicBoolean(false)

    private var prev: Map<CoroKey, CoroutineSnapshot> = emptyMap()

    override val isRunning: Boolean get() = loop?.isActive == true

    override fun start() {
        if (isRunning) return

        if (installProbes && installed.compareAndSet(false, true)) {
            DebugProbesInstall.acquire()
        }

        registerCloseHook()
        prev = emptyMap()

        loop =
            scope.launch {
                while (isActive) {
                    pollTick()
                    delay(pollInterval)
                }
            }
    }

    private fun pollTick() {
        try {
            val next = dump().associateBy { it.key }
            diff(prev, next).forEach { delta ->
                synthesizer.synthesize(delta, session).forEach { event -> emit(event) }
            }
            // Advance prev ONLY on a fully successful tick.
            prev = next
        } catch (ce: CancellationException) {
            // Cooperative cancellation (stop()/scope cancel) must propagate.
            throw ce
        } catch (t: Throwable) {
            // Per-tick isolation: log + continue; prev intentionally NOT advanced so
            // the missed delta recovers on the next successful tick (T-06-07).
            logger.warn("DebugProbesSource poll tick failed; loop continues, prev preserved", t)
        }
    }

    private fun emit(event: VizEvent) {
        if (sampler == null || sampler.shouldKeep(event)) {
            session.send(event)
        }
    }

    private fun registerCloseHook() {
        if (closeListenerRegistered.compareAndSet(false, true)) {
            SessionManager.addOnSessionClosed { closedId ->
                if (closedId == session.sessionId) stop()
            }
        }
    }

    override fun stop() {
        // Idempotent: a close-hook firing after an explicit stop() is a no-op.
        loop?.cancel()
        loop = null
        if (installed.compareAndSet(true, false)) {
            DebugProbesInstall.release()
        }
    }
}
