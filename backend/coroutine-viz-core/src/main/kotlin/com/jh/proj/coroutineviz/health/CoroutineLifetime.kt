package com.jh.proj.coroutineviz.health

import kotlinx.serialization.Serializable

/** How a [CoroutineLifetime] was measured (spec 2026-10-21, "Lifetimes"). */
@Serializable
enum class LifetimeKind {
    /** First observation to its terminal event. */
    FINISHED,

    /** Still running: up to the later of the live watermark and its own last event. */
    SO_FAR,

    /** Still running, no watermark: up to its own last event, so the true figure may be larger. */
    AT_LEAST,
}

/** A coroutine's lifetime on the app clock (spec "Lifetimes"; dock Part 7, the cards' C-2 fields). */
@Serializable
data class CoroutineLifetime(
    val nanos: Long,
    val kind: LifetimeKind,
) {
    companion object {
        /**
         * [watermarkNanos] is the live attached app's latest poll on the app clock, or null (a demo, a replay, an app
         * that sends none).
         */
        fun of(
            summary: CoroutineSummary,
            watermarkNanos: Long?,
        ): CoroutineLifetime {
            val start = summary.firstObserved.appNanos
            val terminal = summary.terminal
            return when {
                terminal != null -> CoroutineLifetime(maxOf(0L, terminal.appNanos - start), LifetimeKind.FINISHED)
                watermarkNanos != null ->
                    CoroutineLifetime(maxOf(0L, maxOf(watermarkNanos, summary.lastObservedNanos) - start), LifetimeKind.SO_FAR)
                else -> CoroutineLifetime(maxOf(0L, summary.lastObservedNanos - start), LifetimeKind.AT_LEAST)
            }
        }
    }
}
