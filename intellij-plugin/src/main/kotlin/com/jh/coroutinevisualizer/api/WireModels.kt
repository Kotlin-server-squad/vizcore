package com.jh.coroutinevisualizer.api

import kotlinx.serialization.Serializable

@Serializable data class ResolveDto(
    val sessionId: String,
)

@Serializable data class HierarchyNodeDto(
    val id: String,
    val parentId: String? = null,
    val children: List<String> = emptyList(),
    val name: String,
    val scopeId: String = "",
    val state: String,
    val createdAtNanos: Long = 0,
    val completedAtNanos: Long? = null,
    val dispatcherId: String? = null,
    val dispatcherName: String? = null,
    val currentThreadId: Long? = null,
    val currentThreadName: String? = null,
    val jobId: String = "",
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val activeChildrenIds: List<String> = emptyList(),
    val activeChildrenCount: Int = 0,
    // Durable source refs that survive EventStore eviction (15-09).
    val creationPoint: SuspensionPointDto? = null,
    // Durable source refs that survive EventStore eviction (15-09).
    val lastSuspensionPoint: SuspensionPointDto? = null,
)

@Serializable data class LeakDto(
    val coroutineId: String,
    val label: String? = null,
    val aliveMs: Long = 0,
)

@Serializable data class MetricsDto(
    val active: Int = 0,
    val peak: Int = 0,
    val throughputPerSec: Double = 0.0,
    val dispatcherUtilization: Map<String, Int> = emptyMap(),
    val leaks: List<LeakDto> = emptyList(),
    val leakThresholdMs: Long = 0,
)

@Serializable data class SuspensionPointDto(
    val function: String = "",
    val fileName: String? = null,
    val lineNumber: Int? = null,
    val reason: String = "",
)

@Serializable data class TimelineEventDto(
    val seq: Long = 0,
    val tsNanos: Long = 0,
    val kind: String = "",
    val threadName: String? = null,
    val dispatcherName: String? = null,
    val reason: String? = null,
    val suspensionPoint: SuspensionPointDto? = null,
)

@Serializable data class TimelineDto(
    val coroutineId: String = "",
    val name: String = "",
    val state: String = "",
    val totalDuration: Long? = null,
    val activeDuration: Long? = null,
    val suspendedDuration: Long? = null,
    val parentId: String? = null,
    val childrenIds: List<String> = emptyList(),
    val events: List<TimelineEventDto> = emptyList(),
)
