package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The demo each session was created for (spec S-13, plan P-1): set once, at creation, from
 * `POST /api/sessions?demoId=`, and returned by the session API. Run again and story mode read
 * it, so a demo session no longer depends on `?scenarioId=` in the page address. Kept like
 * [ChecksResults]: in the session row's `scenario` column in database mode, in a map in memory.
 */
internal object SessionDemos {
    private val memory = ConcurrentHashMap<String, String>()

    private fun database(): ExposedSessionStore? = SessionManager.backingStore() as? ExposedSessionStore

    /** Whether [demoId] names a demo the server can run (one of [demoCatalog]). */
    fun isKnown(demoId: String): Boolean = demoCatalog.any { it.id == demoId }

    fun record(
        sessionId: String,
        demoId: String,
    ) {
        val store = database()
        if (store == null) memory[sessionId] = demoId else store.saveDemoId(sessionId, demoId)
    }

    fun of(sessionId: String): String? {
        val store = database() ?: return memory[sessionId]
        return store.loadDemoId(sessionId)
    }

    fun forget(sessionId: String) {
        memory.remove(sessionId)
    }

    internal fun clear() = memory.clear()
}
