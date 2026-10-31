package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.HttpClient
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals

/**
 * Pins the JVM signatures of the SDK client's published API, which Java callers and apps compiled
 * against 0.2.x link against. #254 made `start(...)` survive an unreachable backend; it must not
 * have changed any of these.
 */
class ClientBinaryCompatibilityTest {
    private val string = String::class.java
    private val int = Int::class.javaPrimitiveType
    private val long = Long::class.javaPrimitiveType
    private val companion = VizcoreClient.Companion::class.java

    @Test
    fun `VizcoreClient keeps its 0_2_x members`() {
        val client = VizcoreClient::class.java
        assertEquals(VizSession::class.java, client.getMethod("getSession").returnType)
        assertEquals(string, client.getMethod("getCurrentSessionId").returnType)
        client.getMethod("start")
        client.getMethod("stop")
    }

    @Test
    fun `the start factories keep their JVM signatures`() {
        val client = VizcoreClient::class.java
        assertEquals(client, companion.getMethod("start", string, string, string, string).returnType)
        companion.getDeclaredMethod("start\$default", companion, string, string, string, string, int, Any::class.java)
        assertEquals(client, companion.getMethod("start", string, string, string, long, string).returnType)
        assertEquals(client, companion.getMethod("start", string, string, string, long).returnType)
    }

    @Test
    fun `createSession keeps its JVM signature`() {
        val bootstrap = Class.forName("com.jh.proj.coroutineviz.client.SessionBootstrapKt")
        val http = HttpClient::class.java
        val continuation = Continuation::class.java
        bootstrap.getMethod("createSession", http, string, string, string, string, continuation)
        bootstrap.getDeclaredMethod("createSession\$default", http, string, string, string, string, continuation, int, Any::class.java)
    }

    @Test
    fun `the not-yet-connected state is readable from Java`() {
        assertEquals(string, VizcoreClient::class.java.getMethod("getSessionIdOrNull").returnType)
    }
}
