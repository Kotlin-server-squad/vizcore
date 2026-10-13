package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job

/**
 * Suspends until [session] is deleted, in either storage mode.
 *
 * In memory mode a delete closes the session, which cancels its scope. In database mode every
 * read builds a fresh [VizSession], and a delete only removes the row, so this instance's scope
 * never ends; the close notification from [SessionManager] is the only signal there. Waiting on
 * both covers each mode, and the listener is always removed again. A delete that happened before
 * the listener was registered is caught by checking the row once more right after registering.
 */
internal suspend fun awaitSessionRemoved(session: VizSession) {
    val removed = CompletableDeferred<Unit>()
    val onClosed: (String) -> Unit = { id -> if (id == session.sessionId) removed.complete(Unit) }
    SessionManager.addOnSessionClosed(onClosed)
    val dbStore = SessionManager.backingStore() as? ExposedSessionStore
    if (dbStore != null && !dbStore.sessionExists(session.sessionId)) removed.complete(Unit)
    val onScopeEnd =
        session.sessionScope.coroutineContext.job
            .invokeOnCompletion { removed.complete(Unit) }
    try {
        removed.await()
    } finally {
        SessionManager.removeOnSessionClosed(onClosed)
        onScopeEnd.dispose()
    }
}
