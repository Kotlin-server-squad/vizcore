package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals

/** Which coroutines of a dump [excludeSdkOwned] treats as vizcore's own (#225). */
class SdkOwnedCoroutinesTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sdkDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun raw(
        name: String,
        context: CoroutineContext,
        job: Job? = Job(),
        creationStack: List<StackTraceElement> = emptyList(),
    ) = CoroutineInfoAdapter.RawInfo(
        state = CoroState.SUSPENDED,
        job = job,
        context = context + CoroutineName(name),
        creationStackTrace = creationStack,
        lastObservedStackTrace = emptyList(),
    )

    private fun names(raws: List<CoroutineInfoAdapter.RawInfo>) =
        excludeSdkOwned(raws, setOf(sdkDispatcher)).map { it.context[CoroutineName]!!.name }

    @Test
    fun `app coroutines are kept, including ones on a dispatcher the app shares with nobody`() {
        val dump =
            listOf(
                raw("app-default", Dispatchers.Default),
                raw("app-io", Dispatchers.IO),
                raw("app-no-job", Dispatchers.Default, job = null),
            )
        assertEquals(listOf("app-default", "app-io", "app-no-job"), names(dump))
    }

    @Test
    fun `a coroutine carrying the marker is left out`() {
        val dump = listOf(raw("poll-loop", Dispatchers.Default + VizcoreInternal), raw("app", Dispatchers.Default))
        assertEquals(listOf("app"), names(dump))
    }

    @Test
    fun `a coroutine on the SDK's transport dispatcher is left out`() {
        val dump = listOf(raw("ws-reader", sdkDispatcher), raw("app", Dispatchers.IO))
        assertEquals(listOf("app"), names(dump))
    }

    @Test
    fun `an app coroutine created while SDK code was on the stack is kept`() {
        val sdkFrame = StackTraceElement("com.jh.proj.coroutineviz.client.IngestTransport", "connect", "IngestTransport.kt", 1)
        val dump = listOf(raw("app", Dispatchers.Default, creationStack = listOf(sdkFrame)))
        assertEquals(listOf("app"), names(dump))
    }

    @Test
    fun `app work sharing a root Job with a marked coroutine is kept`() {
        // An app may launch on a scope whose Job also parents vizcore's projections (the
        // session scope); only the marked coroutines are vizcore's.
        val sessionRoot = SupervisorJob()
        val dump =
            listOf(
                raw("projection", Dispatchers.Default + VizcoreInternal, job = Job(sessionRoot)),
                raw("app-on-session-scope", Dispatchers.Default, job = Job(sessionRoot)),
            )
        assertEquals(listOf("app-on-session-scope"), names(dump))
    }

    @Test
    fun `siblings under the root Job of an SDK coroutine are left out, other trees are kept`() {
        // Ktor runs a WebSocket session's coroutines as siblings under one request Job; some of
        // them run on Unconfined rather than on the engine dispatcher.
        val requestRoot = SupervisorJob()
        val sessionJob = Job(requestRoot)
        val appRoot = SupervisorJob()
        val dump =
            listOf(
                raw("ws-reader", sdkDispatcher, job = Job(sessionJob)),
                raw("ws-incoming-processor", Dispatchers.Unconfined, job = Job(Job(sessionJob))),
                raw("app-child", Dispatchers.Unconfined, job = Job(appRoot)),
            )
        assertEquals(listOf("app-child"), names(dump))
    }

    @Test
    fun `nothing is dropped when no coroutine is the SDK's`() {
        val dump = listOf(raw("a", Dispatchers.Default), raw("b", Dispatchers.IO))
        assertEquals(dump, excludeSdkOwned(dump, emptySet()))
    }
}
