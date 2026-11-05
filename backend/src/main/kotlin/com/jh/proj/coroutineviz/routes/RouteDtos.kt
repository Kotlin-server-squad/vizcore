package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.VizEvent
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
)

@Serializable
data class CoroutineNodeDto(
    val id: String,
    val jobId: String,
    val parentId: String?,
    val scopeId: String,
    val label: String?,
    val state: String,
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
)

/** A still-active coroutine flagged as a potential leak (alive past the threshold). */
@Serializable
data class LeakDto(
    val coroutineId: String,
    val label: String?,
    val aliveMs: Long,
)
