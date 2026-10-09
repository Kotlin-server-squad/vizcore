package com.jh.coroutinevisualizer.toolwindow

import com.intellij.util.messages.Topic

/**
 * Project message-bus topic fired by [com.jh.coroutinevisualizer.actions.RunWithVisualizerAction]
 * AFTER it arms a correlation and opens the tool window.
 *
 * Why this exists: IntelliJ creates a tool window's content ONCE and caches it — hide/show does NOT
 * re-run the factory. So a [VizcoreToolWindowPanel] that was built before a run (in the NOT_LAUNCHED
 * state) would never react to a later launch. The panel therefore subscribes to this topic and
 * (re)starts polling when a launch is announced, in addition to reading the already-armed correlation
 * at construction time (for the run-then-open ordering). This makes the view robust to open-order.
 */
fun interface VizcoreLaunchListener {
    fun launched(correlation: String)
}

val VIZCORE_LAUNCH_TOPIC: Topic<VizcoreLaunchListener> =
    Topic.create("Vizcore launch armed", VizcoreLaunchListener::class.java)
