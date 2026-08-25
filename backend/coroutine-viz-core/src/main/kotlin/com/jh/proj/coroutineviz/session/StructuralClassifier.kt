package com.jh.proj.coroutineviz.session

/**
 * The single, explicit, [kind]-keyed allow-set of STRUCTURAL events.
 *
 * This is the **shared spine** of two PERF capabilities:
 * - **PERF-01 (never sample):** the egress [EventSampler] must NEVER thin a structural kind,
 *   regardless of configured per-type rate or observed load (D-01).
 * - **PERF-04 (never shed):** the structural-aware overflow buffer (Plan 02) must NEVER evict a
 *   structural kind; only non-structural noise is shed first (D-07).
 *
 * Structural events define existence and topology — node birth/death and the edges between
 * them. Dropping any of them corrupts the live tree/graph. In particular the coroutine
 * lifecycle create/complete/cancel kinds are protected ABSOLUTELY per D-01.
 *
 * ## Why an explicit allow-set, not a suffix heuristic
 * The previous protection logic (`EventSampler.isLifecycleEvent`) matched kinds by suffix
 * (`*Created`/`*Started`/`*Completed`/`*Failed`/`*Cancelled`). That rule both under- and
 * over-protected: it would wrongly protect high-frequency value events whose kind happens to
 * end in a lifecycle suffix, and it could miss kinds that carry no such suffix. Keying on the
 * exact `kind` discriminator (as registered in `VizEventSerializersModule`) makes the protected
 * set EXACT and AUDITABLE, and lets the sampler (this phase) and the shed buffer (Plan 02)
 * share ONE definition.
 *
 * ## Borderline kinds (Assumption A2 — safe-over-protect)
 * Low-frequency state-machine-completion kinds whose structural-vs-sheddable membership is not
 * clear-cut (`MutexUnlocked`, `SemaphorePermitReleased`, `Select*`, `Deferred*`) default to
 * STRUCTURAL per RESEARCH §1 Open-Q1. Over-protecting is safe (it only means slightly less
 * thinning); under-protecting risks state gaps. If any of these is later proven to flood, demote
 * it with a per-type rate rather than reclassifying here.
 *
 * Pure-Kotlin, JVM-17, framework-free (no Micrometer, no io.ktor) — lives in `coroutine-viz-core`.
 */
object StructuralClassifier {
    /**
     * The protected kinds, keyed on the exact `kind` discriminator registered in
     * `VizEventSerializersModule`. Anything NOT in this set is sheddable.
     */
    private val STRUCTURAL_KINDS: Set<String> = setOf(
        // -- coroutine lifecycle (D-01: create/start/complete/cancel/fail protected) --
        "CoroutineCreated",
        "CoroutineStarted",
        "CoroutineCompleted",
        "CoroutineCancelled",
        "CoroutineFailed",
        "CoroutineBodyCompleted",
        // -- job parentage / terminal / structured-concurrency topology --
        "JobStateChanged",
        "JobCancellationRequested",
        "JobJoinCompleted",
        "JobJoinRequested",
        "WaitingForChildren",
        // -- channel object birth/death --
        "ChannelCreated",
        "ChannelClosed",
        // -- flow stream existence boundaries --
        "FlowCreated",
        "FlowCollectionStarted",
        "FlowCollectionCompleted",
        "FlowCollectionCancelled",
        // -- synchronization primitive birth --
        "MutexCreated",
        "SemaphoreCreated",
        // -- actor birth/death --
        "ActorCreated",
        "ActorClosed",
        // -- rare, high-signal diagnostics — never thin --
        "DeadlockDetected",
        "PotentialDeadlockWarning",
        "AntiPatternDetected",
        // -- borderline low-frequency state-machine completion (A2: default structural) --
        "MutexUnlocked",
        "SemaphorePermitReleased",
        "SelectStarted",
        "SelectClauseRegistered",
        "SelectClauseWon",
        "SelectCompleted",
        "DeferredAwaitStarted",
        "DeferredAwaitCompleted",
        "DeferredValueAvailable",
    )

    /**
     * Returns true if [kind] is STRUCTURAL (protected — never sampled, never shed).
     *
     * Only the explicit [STRUCTURAL_KINDS] allow-set is protected; an unknown or unregistered
     * kind returns false (sheddable).
     */
    fun isStructural(kind: String): Boolean = kind in STRUCTURAL_KINDS
}
