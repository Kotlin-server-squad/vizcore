package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.Serializable

/**
 * Standard error envelope returned on 4xx/5xx responses, matching the OpenAPI
 * `ErrorResponse` schema (documentation.yaml). Serializes to `{"error":"..."}`;
 * the frontend 404 detection (`ComparisonView.isSessionNotFound`) parses this
 * exact shape, and the compiler now enforces the documented contract.
 */
@Serializable
data class ErrorResponse(
    val error: String,
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
