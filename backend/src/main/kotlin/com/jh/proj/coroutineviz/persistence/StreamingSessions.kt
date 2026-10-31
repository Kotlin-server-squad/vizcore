package com.jh.proj.coroutineviz.persistence

/**
 * Which sessions an app is streaming into right now, as [DbRetentionPolicy] needs to know (#261).
 *
 * Implemented by [com.jh.proj.coroutineviz.routes.IngestConnections], the open ingest sockets of
 * this process; declared here so the persistence layer does not depend on the routes.
 */
interface StreamingSessions {
    /** Whether [sessionId] has an open ingest socket. */
    fun isStreaming(sessionId: String): Boolean

    /**
     * Runs [block] while no ingest socket can register, so a decision based on [isStreaming] and
     * acted on inside [block] cannot be overtaken by a socket opening in between.
     */
    fun <T> whileNoSocketRegisters(block: () -> T): T

    /** Nothing is ever streamed into: the policy's behaviour before #261. */
    object None : StreamingSessions {
        override fun isStreaming(sessionId: String): Boolean = false

        override fun <T> whileNoSocketRegisters(block: () -> T): T = block()
    }
}
