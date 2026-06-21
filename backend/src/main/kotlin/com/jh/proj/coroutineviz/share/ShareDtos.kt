package com.jh.proj.coroutineviz.share

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.routes.SessionSnapshotResponse
import kotlinx.serialization.Serializable

/**
 * Allowed share-expiry durations (D-11). The wire value is the lowercase code
 * sent by the client (`1d`/`7d`/`30d`/`never`); `never` maps to a null
 * `expires_at` (the share never expires — and is therefore always "active" for
 * the retention guard, see [com.jh.proj.coroutineviz.persistence.DbRetentionPolicy]).
 */
enum class ShareExpiry(val code: String, val days: Long?) {
    ONE_DAY("1d", 1),
    SEVEN_DAYS("7d", 7),
    THIRTY_DAYS("30d", 30),
    NEVER("never", null),
    ;

    companion object {
        /** Parse a client-supplied code into a [ShareExpiry], or null when invalid (→ 400). */
        fun fromCode(code: String?): ShareExpiry? = entries.firstOrNull { it.code == code }
    }
}

/** `POST /api/sessions/{id}/share` request body. `expiresIn` ∈ {1d, 7d, 30d, never} (D-11). */
@Serializable
data class CreateShareRequest(
    val expiresIn: String,
)

/**
 * `POST /api/sessions/{id}/share` 201 response. `expiresAt` is an ISO-8601
 * instant string, or null for a never-expiring share (never returned as a
 * stringified null — the field is genuinely JSON null).
 */
@Serializable
data class CreateShareResponse(
    val token: String,
    val url: String,
    val expiresAt: String?,
)

/** One row in the `GET /api/sessions/{id}/shares` owner listing. */
@Serializable
data class ShareSummary(
    val token: String,
    val expiresAt: String?,
    val accessCount: Int,
    val lastAccessedAt: String?,
)

/**
 * `GET /api/shared/{token}` 200 body: the read-only session snapshot plus its
 * full event history (ADR-019). `events` reuses the SSE [VizEvent] wire shape.
 */
@Serializable
data class SharedSessionResponse(
    val session: SessionSnapshotResponse,
    val events: List<VizEvent>,
)
