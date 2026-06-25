package com.jh.proj.coroutineviz.events.coroutine

import com.jh.proj.coroutineviz.events.CoroutineEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a new coroutine is created via [vizLaunch] or [vizAsync].
 *
 * This is the first event in a coroutine's lifecycle. At this point the
 * coroutine exists but has not yet started executing.
 *
 * Lifecycle: CREATED → [CoroutineStarted]
 *
 * [createdAtEpochMs] is a WALL-CLOCK ([System.currentTimeMillis]) creation stamp used
 * as the persistence-safe basis for leak age. Unlike [tsNanos] (a per-process
 * [System.nanoTime] value with no fixed origin across JVM lifetimes), epoch millis has a
 * fixed origin, so `now - createdAtEpochMs` stays well-defined after a backend restart on
 * the DB-rehydrated path (CR-01). It is the LAST, defaulted constructor param so existing
 * positional/named call sites stay source-compatible and pre-existing persisted rows that
 * lack the field decode to the `0L` default with NO schema migration.
 */
@Serializable
@SerialName("CoroutineCreated")
data class CoroutineCreated(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val coroutineId: String,
    override val jobId: String,
    override val parentCoroutineId: String?,
    override val scopeId: String,
    override val label: String?,
    val createdAtEpochMs: Long = 0L,
) : CoroutineEvent {
    override val kind: String get() = "CoroutineCreated"
}
