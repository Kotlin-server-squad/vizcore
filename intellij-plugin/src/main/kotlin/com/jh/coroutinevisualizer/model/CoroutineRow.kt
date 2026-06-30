package com.jh.coroutinevisualizer.model

/** Immutable per-coroutine view-model rendered as one tree row. ageMs is approximate (poll-bounded). */
data class CoroutineRow(
    val id: String,
    val name: String,
    val state: String,
    val dispatcherName: String?,
    val ageMs: Long,
    val childCount: Int,
    val isLeak: Boolean,
)
