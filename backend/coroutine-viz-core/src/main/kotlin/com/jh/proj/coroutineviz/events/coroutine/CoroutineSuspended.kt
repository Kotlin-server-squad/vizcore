package com.jh.proj.coroutineviz.events.coroutine

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a coroutine suspends execution.
 *
 * Suspension occurs at suspension points like [delay], [await], [join],
 * or [withContext]. The coroutine releases its thread and will be
 * resumed later when the suspending operation completes.
 *
 * @property reason Why the coroutine suspended (e.g., "delay", "await", "join")
 * @property durationMillis Expected duration if known (e.g., for delay)
 * @property suspensionPoint Source code location where suspension occurred
 *
 * Lifecycle: ACTIVE → SUSPENDED → [CoroutineResumed]
 */
@Serializable
@SerialName("CoroutineSuspended")
data class CoroutineSuspended(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val coroutineId: String,
    override val jobId: String,
    override val parentCoroutineId: String?,
    override val scopeId: String,
    override val label: String?,
    val reason: String,
    val durationMillis: Long? = null,
    val suspensionPoint: SuspensionPoint? = null,
    /** Where [suspensionPoint] came from; `null` when the producer did not say. */
    val provenance: SuspensionProvenance? = null,
) : CoroutineEvent {
    /**
     * The 0.2.1 JVM constructor, kept for binary compatibility (prebuilt jars, Java callers). Hidden from
     * source resolution so it never competes with the primary one. `copy` is source-compatible only.
     */
    @Deprecated("binary compatibility with 0.2.x", level = DeprecationLevel.HIDDEN)
    constructor(
        sessionId: String,
        seq: Long,
        tsNanos: Long,
        coroutineId: String,
        jobId: String,
        parentCoroutineId: String?,
        scopeId: String,
        label: String?,
        reason: String,
        durationMillis: Long? = null,
        suspensionPoint: SuspensionPoint? = null,
    ) : this(
        sessionId, seq, tsNanos, coroutineId, jobId, parentCoroutineId, scopeId, label,
        reason, durationMillis, suspensionPoint, null,
    )

    override val kind: String get() = "CoroutineSuspended"
}
