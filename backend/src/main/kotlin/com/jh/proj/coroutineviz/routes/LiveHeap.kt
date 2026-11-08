package com.jh.proj.coroutineviz.routes

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/** One heap memory pool: what it holds now, and what its last collection left (null when the pool reports none). */
internal data class HeapPool(
    val usedBytes: Long,
    val usedAfterLastCollectionBytes: Long?,
)

/**
 * The heap the app actually keeps: for each pool, what its last collection left, or what it holds now when it reports
 * no collection usage. `total - free` also counts garbage not yet collected, so a healthy JVM can read past 95 % between
 * collections; a probe on it would take the instance out of service for garbage alone.
 */
internal fun liveHeapBytes(pools: List<HeapPool>): Long = pools.sumOf { it.usedAfterLastCollectionBytes ?: it.usedBytes }

private const val PERCENT = 100.0

internal fun heapPercent(
    usedBytes: Long,
    maxBytes: Long,
): Double = if (maxBytes > 0) usedBytes.toDouble() / maxBytes * PERCENT else 0.0

/** The running JVM's heap pools. */
internal fun currentHeapPools(): List<HeapPool> =
    ManagementFactory
        .getMemoryPoolMXBeans()
        .filter { it.type == MemoryType.HEAP && it.isValid }
        .map { pool ->
            HeapPool(
                usedBytes = pool.usage.used,
                // Null when the pool does not support collection usage.
                usedAfterLastCollectionBytes = pool.collectionUsage?.used,
            )
        }

/** Live heap as a percentage of the maximum heap, for the health and readiness verdicts. */
internal fun liveHeapPercent(): Double = heapPercent(liveHeapBytes(currentHeapPools()), Runtime.getRuntime().maxMemory())
