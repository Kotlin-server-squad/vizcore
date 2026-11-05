package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transformWhile

/**
 * The Flow demos, each told as an everyday app: a stock ticker (a cold flow runs once for every
 * collector), search as you type (operators work on each value in order), a shopping cart (a
 * StateFlow always holds the latest value) and notifications (a SharedFlow sends each value to
 * every subscriber). Their steps are paced with [pause] like the Basics: 0.5-3 s a step, 10-30 s
 * a demo, at the configured `scenarios.timeScale`. Each returns the demo's root coroutine.
 *
 * A collector that joins later is started once the demo has reached the right point, never after
 * a fixed wait alone: at a small time scale a wait is a few milliseconds, and a collector that
 * missed the value it waits for would wait for ever.
 */
object FlowScenarios {
    /** The Acme share prices the ticker sends to each collector, 2 s apart. */
    private val ACME_PRICES = listOf(101.2, 101.8, 100.9, 102.4, 103.1, 102.7)

    /** The cart total after each item the user adds. */
    private val CART_TOTALS = listOf(25, 40, 52, 64, 70)

    private val NOTIFICATIONS =
        listOf("Ana sent you a message", "Your order has shipped", "Meeting in 10 minutes", "Battery at 15%", "Ana is calling")

    /** How long the search box waits after the last keystroke before it searches. */
    private const val DEBOUNCE_MILLIS = 1200L

    /** What the user ends up typing; the results screen stops once it shows its results. */
    private const val FINAL_QUERY = "kotlin flow"

    /** What the search service answers for [query]. */
    internal fun resultsFor(query: String) = "results for \"$query\""

    /**
     * A cold flow, as a stock ticker: the flow `acme-prices` waits 1.5 s before each of six
     * prices, and each screen draws a price for 0.5 s before the flow goes on, so a screen gets a
     * price every 2 s. The price chart collects from the start, the price
     * alert from 4 s; the flow runs again for the alert, from the first price, because a cold flow
     * runs once for every collector. Ends at about 16 s.
     */
    suspend fun simpleFlow(session: VizSession): Job =
        coroutineScope {
            val viz = session.demoScope()

            viz.vizLaunch("stock-ticker") {
                // Cold: this block runs again, from the first price, for every collector.
                val prices =
                    vizFlow(label = "acme-prices") {
                        for (price in ACME_PRICES) {
                            pause(1500)
                            emit(price)
                        }
                    }
                vizLaunch("price-chart") {
                    prices.collect { pause(500) }
                }
                pause(2000)
                pause(2000)
                vizLaunch("price-alert") {
                    prices.collect { pause(500) }
                }
            }
        }

    /**
     * Flow operators, as search as you type: the user types "ko", thinks, types "tlin", thinks,
     * then types " flow", a key every 0.5 s. The search box debounces the keystrokes (it waits
     * 1.2 s after the last one), filters out queries shorter than three letters, maps each query
     * to results (the search service takes 1.5 s) and stops once it shows the results for the
     * whole query. "ko" is debounced and filtered out; "kotlin" and "kotlin flow" are searched.
     * Ends at about 14 s.
     */
    suspend fun flowOperators(session: VizSession): Job =
        coroutineScope {
            val viz = session.demoScope()

            viz.vizLaunch("search-screen") {
                val keystrokes = vizSharedFlow<String>(label = "keystrokes")
                vizLaunch("show-results") {
                    vizWrap(keystrokes, label = "search-box")
                        .vizDebounce(scaled(DEBOUNCE_MILLIS))
                        .vizFilter { it.length >= 3 }
                        .vizMap { query ->
                            pause(1500)
                            resultsFor(query)
                        }.untilResultsFor(FINAL_QUERY)
                        .collect { pause(500) }
                }
                vizLaunch("type-query") {
                    // Keystrokes typed before the search box listens would be lost.
                    keystrokes.subscriptionCount.first { it > 0 }
                    type(keystrokes, "", "ko")
                    pause(2500)
                    type(keystrokes, "ko", "kotlin")
                    pause(2500)
                    type(keystrokes, "kotlin", FINAL_QUERY)
                }
            }
        }

    /** Types the letters that turn [typed] into [until], a key every 0.5 s; the box sends its text after each. */
    private suspend fun VizScope.type(
        keystrokes: MutableSharedFlow<String>,
        typed: String,
        until: String,
    ) {
        for (end in typed.length + 1..until.length) {
            pause(500)
            keystrokes.emit(until.take(end))
        }
    }

    /**
     * A StateFlow, as a shopping cart: the total starts at 0 and the user adds an item every
     * 2-3 s. The cart badge shows every total. The checkout summary opens at 7 s and gets the
     * current total, 52, at once, then each change after it. Ends at about 11.5 s, when the
     * total reaches 70.
     */
    suspend fun stateFlowDemo(session: VizSession): Job =
        coroutineScope {
            val viz = session.demoScope()

            viz.vizLaunch("shopping-cart") {
                val total = vizStateFlow(0, label = "cart-total")
                val final = CART_TOTALS.last()
                val thirdAdded = CompletableDeferred<Unit>()
                vizLaunch("cart-badge") {
                    total.untilTotal(final).collect { pause(500) }
                }
                vizLaunch("add-items") {
                    CART_TOTALS.take(3).forEach {
                        pause(2000)
                        total.value = it
                    }
                    thirdAdded.complete(Unit)
                    // The checkout opens while the user decides; the next item comes once it shows the total.
                    total.subscriptionCount.first { it >= 2 }
                    pause(2000)
                    total.value = CART_TOTALS[3]
                    pause(2000)
                    total.value = final
                }
                thirdAdded.await()
                pause(1000)
                vizLaunch("checkout-summary") {
                    total.untilTotal(final).collect { pause(500) }
                }
            }
        }

    /**
     * The totals up to and including [final]. A StateFlow may skip a value a slow collector
     * missed, but it always ends on the latest, so this stops where a count of values might not.
     */
    private fun Flow<Int>.untilTotal(final: Int): Flow<Int> =
        transformWhile {
            emit(it)
            it != final
        }

    /**
     * A SharedFlow, as notifications: three open screens subscribe, and the push server sends
     * five notifications, 2.5 s apart. Every screen gets each one at the same moment and draws it
     * (0.5, 1 or 1.5 s). The profile screen opens after the second notification and gets only
     * the three that follow: a SharedFlow without replay keeps nothing for a late subscriber.
     * Ends at about 14 s.
     */
    suspend fun sharedFlowDemo(session: VizSession): Job =
        coroutineScope {
            val viz = session.demoScope()

            viz.vizLaunch("notification-center") {
                val notifications = vizSharedFlow<String>(label = "notifications")
                val openScreens = listOf("home-screen" to 500L, "chat-screen" to 1000L, "settings-screen" to 1500L)
                val secondSent = CompletableDeferred<Unit>()
                openScreens.forEach { (screen, draw) ->
                    vizLaunch(screen) {
                        notifications.take(NOTIFICATIONS.size).collect { pause(draw) }
                    }
                }
                vizLaunch("push-server") {
                    notifications.subscriptionCount.first { it >= openScreens.size }
                    NOTIFICATIONS.forEachIndexed { i, notification ->
                        pause(2500)
                        // The third goes out once the profile screen, opened after the second, listens.
                        if (i == 2) notifications.subscriptionCount.first { it > openScreens.size }
                        notifications.emit(notification)
                        if (i == 1) secondSent.complete(Unit)
                    }
                }
                secondSent.await()
                pause(1000)
                vizLaunch("profile-screen") {
                    notifications.take(NOTIFICATIONS.size - 2).collect { pause(500) }
                }
            }
        }
}

/**
 * The search results up to and including those for [finalQuery]. The keystrokes are a SharedFlow,
 * which never completes, so the screen must stop on a value. A count of searches would wait for
 * ever if two typing pauses blurred into one search, which happens at a small time scale.
 */
internal fun Flow<String>.untilResultsFor(finalQuery: String): Flow<String> =
    transformWhile {
        emit(it)
        it != FlowScenarios.resultsFor(finalQuery)
    }
