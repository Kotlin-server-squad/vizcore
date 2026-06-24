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
 * always releases the install — no long-lived JVM-global leak. [stop]
 * deregisters that close hook (via [SessionManager.removeOnSessionClosed]) so the
 * listener — and, transitively, this source and its session — is not pinned in
 * the registry after teardown (CR-02), and resets the registration flag so a
 * stop→start restart re-registers the hook. [start]/[stop] are idempotent and the
 * source is restartable.
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

    // @Volatile: written in start()/stop() (possibly off the loop thread — the
    // close hook fires on the caller's thread) and read in isRunning/emit (IN-03).
    @Volatile
    private var loop: Job? = null
    private val installed = AtomicBoolean(false)

    // Registered while running so the close hook fires stop(); reset on stop() so a
    // stop→start restart re-registers (CR-02). Guarded by sessionId at fire time.
    private val closeListenerRegistered = AtomicBoolean(false)

    // The exact close-listener lambda we registered, held so stop() can deregister
    // it from SessionManager (CR-02 leak: otherwise the lambda — and, transitively,
    // this source and its VizSession/EventStore — is pinned for the JVM lifetime).
    @Volatile
    private var closeListener: ((String) -> Unit)? = null

    @Volatile
    private var prev: Map<CoroKey, CoroutineSnapshot> = emptyMap()

    override val isRunning: Boolean get() = loop?.isActive == true

    override fun start() {
        if (isRunning) return

        if (installProbes && installed.compareAndSet(false, true)) {
            DebugProbesInstall.acquire()
        }

        registerCloseHook()
        // Run-scoped identity: reset both the diff baseline AND the adapter so a
        // restart does not re-Appeared coroutines whose synthetic key was cached in
        // the prior run (WR-03).
        prev = emptyMap()
        adapter.reset()

        loop =
            scope.launch {
                while (isActive) {
                    pollTick()
                    delay(pollInterval)
                }
            }
    }

    private fun pollTick() {
        // Working copy of the last-good snapshot. We advance it per-delta as each
        // delta's events are fully emitted, so a mid-batch failure neither replays
        // the already-delivered prefix (WR-02) nor loses the un-emitted suffix:
        // the suffix stays pending against the old state and recovers next tick.
        val committed = prev.toMutableMap()
        try {
            val next = dump().associateBy { it.key }
            diff(prev, next).forEach { delta ->
                synthesizer.synthesize(delta, session).forEach { event -> emit(event) }
                // This delta's events are all out — fold it into the committed state.
                applyDelta(committed, delta)
            }
            // Fully successful tick: committed == next.
            prev = next
        } catch (ce: CancellationException) {
            // Cooperative cancellation (stop()/scope cancel) must propagate. Do NOT
            // commit partial progress: the loop is stopping.
            throw ce
        } catch (t: Throwable) {
            // Per-tick isolation: log + continue. Advance prev to the state reflecting
            // ONLY the deltas already fully emitted this tick (WR-02) — so the next
            // tick re-diffs without replaying delivered events, and still recovers the
            // deltas that never made it out (T-06-07).
            prev = committed
            logger.warn("DebugProbesSource poll tick failed; loop continues, prev advanced past emitted deltas", t)
        }
    }

    /** Fold a fully-emitted delta into the running committed snapshot. */
    private fun applyDelta(
        committed: MutableMap<CoroKey, CoroutineSnapshot>,
        delta: CoroutineDelta,
    ) {
        when (delta) {
            is CoroutineDelta.Appeared -> committed[delta.now.key] = delta.now
            is CoroutineDelta.StateChanged -> committed[delta.now.key] = delta.now
            is CoroutineDelta.Vanished -> committed.remove(delta.last.key)
        }
    }

    private fun emit(event: VizEvent) {
        // WR-06: the close hook cancels the loop, but cancellation is cooperative —
        // a tick already inside the emit loop would otherwise keep send()-ing into a
        // session the manager has already closed/removed (orphan events, racing
        // close). Stop emitting as soon as the loop is no longer active.
        if (loop?.isActive == false) return
        if (sampler == null || sampler.shouldKeep(event)) {
            session.send(event)
        }
    }

    private fun registerCloseHook() {
        if (closeListenerRegistered.compareAndSet(false, true)) {
            val listener: (String) -> Unit = { closedId ->
                if (closedId == session.sessionId) stop()
            }
            closeListener = listener
            SessionManager.addOnSessionClosed(listener)
        }
    }

    override fun stop() {
        // Idempotent: a close-hook firing after an explicit stop() is a no-op.
        loop?.cancel()
        loop = null
        // Deregister the close hook so neither the lambda nor (transitively) this
        // source + its VizSession/EventStore leak into SessionManager's registry
        // forever (CR-02). Resetting the flag also lets a stop→start restart
        // re-register the hook (without this, a restarted loop was never torn down
        // on session close).
        closeListener?.let { SessionManager.removeOnSessionClosed(it) }
        closeListener = null
        closeListenerRegistered.set(false)
        if (installed.compareAndSet(true, false)) {
            DebugProbesInstall.release()
        }
    }
}
