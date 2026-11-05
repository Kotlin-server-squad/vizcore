package com.jh.proj.coroutineviz.events

import kotlinx.serialization.Serializable

/**
 * What a coroutine waited for when a vizcore wrapper recorded the wait as a suspension (spec 2026-11-05, S-7):
 * a [com.jh.proj.coroutineviz.wrappers.VizMutex], a [com.jh.proj.coroutineviz.wrappers.VizSemaphore] permit, or
 * an [com.jh.proj.coroutineviz.wrappers.InstrumentedChannel] send or receive. [reason] is the
 * `CoroutineSuspended.reason` sent with it, so a reader that ignores the field still sees the kind.
 * Adding a constant is a breaking wire change: `appJson` does not coerce unknown enum values, so a reader that
 * knows an older set fails the whole event.
 */
@Serializable
enum class WaitKind(
    val reason: String,
) {
    MUTEX("mutex"),
    SEMAPHORE("semaphore"),
    CHANNEL_SEND("channel-send"),
    CHANNEL_RECEIVE("channel-receive"),
}
