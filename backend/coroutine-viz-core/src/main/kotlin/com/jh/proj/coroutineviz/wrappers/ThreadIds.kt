package com.jh.proj.coroutineviz.wrappers

/**
 * The current thread's id, safe on the JVM-17 floor this library targets.
 *
 * `Thread#threadId()` only exists from JDK 19; calling it compiles fine on a JDK-21
 * toolchain yet throws `NoSuchMethodError` for SDK consumers on Java 17. `Thread#getId()`
 * is deprecated from 19 but present everywhere, and returns the same value.
 */
@Suppress("DEPRECATION")
internal fun currentThreadId(): Long = Thread.currentThread().id
