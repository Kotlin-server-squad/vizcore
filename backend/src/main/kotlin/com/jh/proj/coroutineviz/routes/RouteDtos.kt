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
