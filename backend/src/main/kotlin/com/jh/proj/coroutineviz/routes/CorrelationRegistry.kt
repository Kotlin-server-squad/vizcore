package com.jh.proj.coroutineviz.routes

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory token → sessionId binding store for session correlation (CORR-01/CORR-02).
 *
 * A connecting `VizcoreClient` (or a poller such as the ConnectWizard / future IntelliJ
 * plugin) supplies an opaque, client-minted `correlation` token to `POST /api/sessions`.
 * The create handler records that token against the session the backend ACTUALLY creates
 * (no separately-minted id, D-11), and `GET /api/sessions/resolve?correlation=…` looks it up
 * so the caller converges on the SAME live session id.
 *
 * Design (matches [com.jh.proj.coroutineviz.session.SessionManager]'s shape, D-12):
 * - Object singleton + [ConcurrentHashMap], process-lifetime state. NO TTL, NO DB, NO schema
 *   (D-10) — the map dies with the process and is bounded by live-session cardinality via the
 *   `addOnSessionClosed` eviction hook wired in `configureRouting()`.
 * - Stores ONLY the sessionId (value), keyed on the correlation token. Tenant gating is NOT
 *   done here — it is deferred to the route's existing `resolveScopedSession` so this registry
 *   stays free of auth/tenancy types and keeps `coroutine-viz-core`/`-client` JVM-17 pure (D-12).
 * - Last-write-wins (D-09): re-binding a token to a new session simply re-points it, matching the
 *   dev edit-rerun loop.
 *
 * This is create-path metadata only; it does NOT touch `VizSession.send()` / the store-write path
 * (governing invariant #1 / D-13).
 */
object CorrelationRegistry {
    private val bindings = ConcurrentHashMap<String, String>()

    /**
     * Bind [token] to [sessionId]. Last-write-wins (D-09): an existing binding for the same
     * token is overwritten, re-pointing `resolve` at the newest session.
     */
    fun bind(
        token: String,
        sessionId: String,
    ) {
        bindings[token] = sessionId
    }

    /** Resolve [token] to its bound sessionId, or null if the token was never bound. */
    fun resolve(token: String): String? = bindings[token]

    /**
     * Remove every binding whose value is [sessionId] (scan-and-remove by value — low cardinality,
     * bounded by live sessions). A no-op when no binding references [sessionId]. Wired to the
     * composable `addOnSessionClosed` hook so a closed session's token resolves to 404 again (D-10).
     */
    fun evict(sessionId: String) {
        bindings.entries.removeIf { it.value == sessionId }
    }

    /** Empty the registry. Test helper only (object singleton shared across tests). */
    fun clear() {
        bindings.clear()
    }
}
