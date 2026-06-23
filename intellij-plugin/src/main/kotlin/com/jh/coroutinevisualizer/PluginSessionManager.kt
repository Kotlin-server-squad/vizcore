package com.jh.coroutinevisualizer

import com.intellij.openapi.project.Project
import com.jh.proj.coroutineviz.session.VizSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages VizSession instances per IntelliJ project.
 * Each open project gets its own visualization session.
 */
object PluginSessionManager {
    private val sessions = ConcurrentHashMap<String, VizSession>()

    fun getOrCreateSession(project: Project): VizSession {
        val key = project.locationHash
        return sessions.getOrPut(key) {
            VizSession("plugin-${project.name}-${System.currentTimeMillis()}")
        }
    }

    fun getSession(project: Project): VizSession? = sessions[project.locationHash]

    fun closeSession(project: Project) {
        val key = project.locationHash
        sessions.remove(key)?.close()
    }

    fun clearAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
