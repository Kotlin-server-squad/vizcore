package com.jh.proj.coroutineviz.events

import kotlinx.serialization.Serializable

/**
 * Where a suspension point came from (spec 2026-10-21, "Suspension-point provenance").
 * [REAL_FRAME]: a suspension frame was read. [CREATION_FALLBACK]: no usable frame, the creation
 * frame was sent instead. [STALE]: set by the summary when a newer suspension arrived without a
 * frame and the older location was kept. Only [REAL_FRAME] is shown as "last suspension point".
 */
@Serializable
enum class SuspensionProvenance { REAL_FRAME, CREATION_FALLBACK, STALE }
