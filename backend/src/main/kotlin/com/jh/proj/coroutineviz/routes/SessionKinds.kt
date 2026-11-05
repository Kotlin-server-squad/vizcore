package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SessionKind
import io.ktor.server.application.ApplicationCall

/**
 * Ids vizcore mints for its own runs (demo scenarios, the scenario runner and API), whose
 * coroutines run in this JVM rather than in a connected app. Mirrors
 * `frontend/src/lib/session-kind.ts`. An app that names itself `scenario-…` is taken for a demo
 * here too; then its timeline keeps an open phase after the app disconnects.
 */
private val VIZCORE_OWNED_SESSION_ID = Regex("^(scenario-|auto-\\d+(-\\d+)?$|api-session-\\d+$)")

/** A session's kind (spec C-3 `kind`), read from the id vizcore minted; demos are started under `scenario-…` ids. */
internal fun sessionKindOf(sessionId: String): SessionKind =
    if (VIZCORE_OWNED_SESSION_ID.containsMatchIn(sessionId)) SessionKind.DEMO else SessionKind.ATTACHED

/**
 * The `?leakThresholdMs=` of `/metrics` and `/health`, so the state bar filter and the health agree: absent or
 * unparseable falls back to the server default; other values are clamped to bounds (T-08-05/V5).
 */
internal fun ApplicationCall.leakThresholdMs(): Long =
    request.queryParameters["leakThresholdMs"]
        ?.toLongOrNull()
        ?.coerceIn(MIN_LEAK_MS, MAX_LEAK_MS)
        ?: DEFAULT_LEAK_MS
