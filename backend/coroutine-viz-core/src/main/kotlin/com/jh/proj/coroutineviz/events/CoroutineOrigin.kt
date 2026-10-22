package com.jh.proj.coroutineviz.events

import kotlinx.serialization.Serializable

/**
 * Who launched a coroutine (spec 2026-10-21, "Origin"). The SDK sends it on
 * [com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated]; `null` on the wire
 * means an SDK that predates origin support.
 */
@Serializable
enum class CoroutineOrigin { APP, VIZCORE, UNKNOWN }
