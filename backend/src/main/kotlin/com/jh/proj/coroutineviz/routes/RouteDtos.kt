package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.health.LifetimeKind
import com.jh.proj.coroutineviz.health.SessionKind
import com.jh.proj.coroutineviz.health.SessionOrigin
import com.jh.proj.coroutineviz.health.WatermarkObservation
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Standard error envelope returned on 4xx/5xx responses, matching the OpenAPI
 * `ErrorResponse` schema (documentation.yaml). Serializes to `{"error":"..."}`;
 * the frontend 404 detection (`ComparisonView.isSessionNotFound`) parses this
 * exact shape, and the compiler now enforces the documented contract.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ErrorResponse(
    val error: String,
    /**
     * A machine-readable reason, only where a client acts on it: `demo-limit` (every demo slot
     * is taken) and `demo-time-limit` (the demo was stopped at MAX_DEMO_RUN_SECONDS). Omitted
     * otherwise, so every other error keeps the `{"error":"..."}` shape.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val code: String? = null,
)

@Serializable
data class ScenarioResponse(
    val success: Boolean,
    val message: String,
)

@Serializable
data class ScenarioCompletionResponse(
    val success: Boolean,
    val sessionId: String,
    val message: String,
    val coroutineCount: Int,
    val eventCount: Int,
)

@Serializable
data class ScenarioResultData(
    val success: Boolean,
    val sessionId: String,
    val events: List<VizEvent>,
    val coroutines: List<CoroutineNodeDto>,
    val eventCount: Int,
)

@Serializable
data class SessionSnapshotResponse(
    val sessionId: String,
    val coroutineCount: Int,
    val eventCount: Int,
    val coroutines: List<CoroutineNodeDto>,
    /** ATTACHED for a connected app's session, DEMO for vizcore's own runs (spec C-3). */
    val kind: SessionKind,
    /** When the session was created (backend wall clock). */
    val startedAtEpochMs: Long,
    /** App-clock time of the earliest event captured, the origin of "time into the capture"; null when unknown. */
    val captureStartAppNanos: Long?,
    /** REPORTED when the producer reports coroutine origins (or for demos), else UNKNOWN. */
    val origin: SessionOrigin,
    /** Whether an app's ingest socket is open on this backend right now (spec T-2, dock S-3). */
    val appConnected: Boolean,
    /** Backend receipt time of the last event frame from the app, null before any. */
    val lastIngestEpochMs: Long?,
    /** The app's latest poll watermark, null before any (spec C-7). */
    val lastWatermark: WatermarkObservation?,
    /** Seq of the oldest event still retained, null when none is. */
    val oldestRetainedSeq: Long?,
    /** Whether older events were dropped (bounded store or retention). */
    val trimmed: Boolean,
    /** The demo the session was created for (`POST /api/sessions?demoId=`, spec S-13), null when none. */
    val demoId: String? = null,
    /**
     * Coroutines the summaries know that [coroutines] does not hold, vizcore's own left out: a database session reloaded
     * after its events were trimmed rebuilds its nodes from the retained events (dock S-12, "the page says so").
     */
    val summaryOnlyCount: Int = 0,
)

@Serializable
data class CoroutineNodeDto(
    val id: String,
    val jobId: String,
    val parentId: String?,
    val scopeId: String,
    val label: String?,
    val state: String,
    /**
     * The round this coroutine belongs to (spec "Rounds", dock S-12): null when it has no round (its ancestry reaches
     * an untracked parent) or no summary yet, or the session keeps no usable summaries.
     */
    val roundId: String? = null,
    /** Its effective origin (spec "Origin"); null without a summary. */
    val origin: CoroutineOrigin? = null,
    /** Its lifetime on the app clock (spec "Lifetimes", dock Part 7's cards); null without a summary. */
    val lifetimeNanos: Long? = null,
    /** How [lifetimeNanos] was measured: FINISHED, SO_FAR (to the live watermark) or AT_LEAST (no watermark). */
    val lifetimeKind: LifetimeKind? = null,
    /** Where it was created, from its summary (spec C-2); null when unknown. */
    val creationLocation: SuspensionPoint? = null,
)

/**
 * Wire shape for `GET /api/sessions/{id}/metrics` (RCO-07). Plain `@Serializable`
 * data class — NOT a `VizEvent` subtype — so `appJson` serializes it with no
 * polymorphic registration. The route maps the core [com.jh.proj.coroutineviz.session.MetricsSnapshot]
 * into this. [leakThresholdMs] is the SERVER-resolved (clamped) threshold so the
 * FE tooltip can show the effective value (UI-SPEC `{threshold}`).
 */
@Serializable
data class MetricsResponse(
    val active: Int,
    val peak: Int,
    val throughputPerSec: Double,
    val dispatcherUtilization: Map<String, Int>,
    val leaks: List<LeakDto>,
    val leakThresholdMs: Long,
    /** False when the session's summaries are unavailable: then `leaks` is empty rather than aged on the wall clock (H-3). */
    val leaksAvailable: Boolean = true,
    /** Per effective origin (spec C-3); null when the summaries are unavailable. */
    val byOrigin: OriginMetrics? = null,
    /** The projection figures come from retained events only (a database session rebuilt after a trim). */
    val retainedOnly: Boolean = false,
    /** With [retainedOnly]: the app time of the oldest retained event. */
    val oldestRetainedAppNanos: Long? = null,
)

/** One origin's figures: counts from the summaries, peak and throughput from the projection (plan E-15). */
@Serializable
data class OriginSlice(
    val captured: Int,
    val active: Int,
    val peak: Int,
    /** Events per second of this origin's coroutines (backend receipt time); null for a rebuilt database session. */
    val throughputPerSec: Double?,
    val dispatcherUtilization: Map<String, Int>,
    val longLived: Int,
)

@Serializable
data class OriginMetrics(
    val app: OriginSlice,
    val vizcore: OriginSlice,
    val unknown: OriginSlice,
    val untracked: Long,
)

/** A still-active coroutine flagged as a potential leak (alive past the threshold). */
@Serializable
data class LeakDto(
    val coroutineId: String,
    val label: String?,
    val aliveMs: Long,
    /** The coroutine's effective origin (spec C-3 `/metrics` by origin); null when the summaries are unavailable. */
    val origin: CoroutineOrigin? = null,
)
