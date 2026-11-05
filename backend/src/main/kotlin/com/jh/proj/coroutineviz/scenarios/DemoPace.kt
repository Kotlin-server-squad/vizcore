package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong

/**
 * How fast a demo runs: a step paused with [pause] takes its scripted time times [scale]
 * (`scenarios.timeScale`, env `DEMO_TIME_SCALE`). 1 runs demos as scripted, at a pace a person
 * can follow; the tests and the smoke test use a small scale, which keeps every step in the same
 * order, only shorter. A demo scope carries the pace of the run that made it.
 *
 * The scale is at least [MIN_SCALE]: closer to zero the steps (0.5 s and up) shrink to a few
 * milliseconds or none, and then run in whatever order the threads get to them, so a demo could
 * close a screen before its requests started, or fail a call after its siblings finished.
 */
class DemoPace(
    val scale: Double,
) : AbstractCoroutineContextElement(DemoPace) {
    companion object Key : CoroutineContext.Key<DemoPace> {
        /** The smallest scale: a 0.5 s step still waits 5 ms. */
        const val MIN_SCALE = 0.01

        fun isValid(scale: Double) = scale.isFinite() && scale >= MIN_SCALE
    }

    init {
        require(isValid(scale)) { "The demo time scale must be a number of at least $MIN_SCALE, got $scale" }
    }

    fun scaled(millis: Long): Long = (millis * scale).roundToLong()
}

/**
 * [millis] of scripted time in real milliseconds at the scope's [DemoPace], for an operator that
 * waits in real time (such as `debounce`), so it keeps the demo's pace. Outside a demo scope it
 * is [millis].
 */
fun VizScope.scaled(millis: Long): Long = this.coroutineContext[DemoPace]?.scaled(millis) ?: millis

/**
 * One step of a demo: suspends for [millis] of scripted time, scaled by the scope's [DemoPace],
 * and shows as a suspension in the session. Outside a demo scope it runs as scripted.
 */
suspend fun VizScope.pause(millis: Long) = vizDelay(scaled(millis))
