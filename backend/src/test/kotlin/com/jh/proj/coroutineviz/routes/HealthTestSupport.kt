package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.fail

/** Events for health tests. Seqs are 0: the session stamps its own. */
internal object HealthEvents {
    fun sec(n: Long): Long = n * 1_000_000_000L

    fun created(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
        origin: CoroutineOrigin? = CoroutineOrigin.APP,
        creationPoint: SuspensionPoint? = null,
    ) = CoroutineCreated(
        sessionId = sessionId,
        seq = 0,
        tsNanos = at,
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = parent,
        scopeId = "scope",
        label = id,
        origin = origin,
        creationPoint = creationPoint,
    )

    fun started(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
    ) = CoroutineStarted(sessionId, 0, at, id, "job-$id", parent, "scope", id)

    fun suspended(
        sessionId: String,
        id: String,
        at: Long,
        provenance: SuspensionProvenance? = SuspensionProvenance.REAL_FRAME,
    ) = CoroutineSuspended(
        sessionId = sessionId,
        seq = 0,
        tsNanos = at,
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = null,
        scopeId = "scope",
        label = id,
        reason = "delay",
        suspensionPoint = SuspensionPoint(function = "work", fileName = "App.kt", lineNumber = 10, reason = "delay"),
        provenance = provenance,
    )

    fun completed(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
    ) = CoroutineCompleted(sessionId, 0, at, id, "job-$id", parent, "scope", id)

    fun cancelled(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
    ) = CoroutineCancelled(sessionId, 0, at, id, "job-$id", parent, "scope", id, cause = null)

    fun failed(
        sessionId: String,
        id: String,
        at: Long,
        parent: String? = null,
    ) = CoroutineFailed(
        sessionId = sessionId,
        seq = 0,
        tsNanos = at,
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = parent,
        scopeId = "scope",
        label = id,
        exceptionType = "java.lang.IllegalStateException",
        message = "boom",
        stackTrace = emptyList(),
    )

    fun watermark(
        sessionId: String,
        at: Long,
        lost: Long = 0,
        uncertain: Boolean = false,
        final: Boolean = false,
    ) = PollWatermark(sessionId, 0, at, pollEpochMs = 1_792_000_000_000, lostEvents = lost, deliveryUncertain = uncertain, final = final)
}

internal fun VizEvent.frameText(): String = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), this)

/** The frame an SDK from before triage sends: the same event without the fields it did not know. */
internal fun VizEvent.olderSdkFrameText(vararg newFields: String): String {
    val full = appJson.parseToJsonElement(frameText()).jsonObject
    return JsonObject(full.filterKeys { it !in newFields }).toString()
}

internal suspend fun HttpClient.createSessionNamed(name: String): String {
    val response = post("/api/sessions?name=$name")
    assertEquals(HttpStatusCode.Created, response.status)
    return appJson
        .parseToJsonElement(response.bodyAsText())
        .jsonObject["sessionId"]!!
        .jsonPrimitive.content
}

internal suspend fun HttpClient.getJson(path: String): JsonObject {
    val response: HttpResponse = get(path)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return appJson.parseToJsonElement(response.bodyAsText()).jsonObject
}

/** Polls [check] until it holds: the server handles frames and socket closes asynchronously to the client. */
internal suspend fun eventually(
    timeoutMs: Long = 5_000,
    check: suspend () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!check()) {
        if (System.currentTimeMillis() > deadline) fail("condition not met within $timeoutMs ms")
        delay(20)
    }
}
