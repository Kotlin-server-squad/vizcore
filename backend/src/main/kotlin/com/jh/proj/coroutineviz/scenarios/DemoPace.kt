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
 */
class DemoPace(
    val scale: Double,
) : AbstractCoroutineContextElement(DemoPace) {
    companion object Key : CoroutineContext.Key<DemoPace>

    init {
        require(scale.isFinite() && scale >= 0) { "The demo time scale must be zero or more, got $scale" }
    }

    fun scaled(millis: Long): Long = (millis * scale).roundToLong()
}

/**
 * One step of a demo: suspends for [millis] of scripted time, scaled by the scope's [DemoPace],
 * and shows as a suspension in the session. Outside a demo scope it runs as scripted.
 */
suspend fun VizScope.pause(millis: Long) = vizDelay(this.coroutineContext[DemoPace]?.scaled(millis) ?: millis)
