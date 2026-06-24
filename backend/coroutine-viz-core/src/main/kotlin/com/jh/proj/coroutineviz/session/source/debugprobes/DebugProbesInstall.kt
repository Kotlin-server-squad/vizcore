package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.debug.DebugProbes
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide reference-counted DebugProbes install/uninstall (Research Pitfall
 * 4 / threat T-06-04). `DebugProbes` is a JVM-global singleton: multiple
 * [DebugProbesSource]s installing/uninstalling independently could disable
 * probes out from under each other. This object guarantees:
 * - the FIRST source to acquire installs `DebugProbes` (with
 *   `enableCreationStackTraces = true`), guarded by `DebugProbes.isInstalled`;
 * - the LAST source to release uninstalls it.
 *
 * [acquire]/[release] are idempotent-safe at the count level (a source must call
 * release exactly once per acquire — [DebugProbesSource] enforces this via its
 * own running flag).
 */
object DebugProbesInstall {
    private val logger = LoggerFactory.getLogger(DebugProbesInstall::class.java)
    private val refCount = AtomicInteger(0)

    /**
     * True only when THIS object actually called [DebugProbes.install]. The
     * acquire/release guards must be symmetric on ownership: acquire installs
     * only when DebugProbes was not already installed (so we don't double-install
     * over an external party), and release must therefore uninstall ONLY when we
     * were the installer. Keying release off the global `DebugProbes.isInstalled`
     * instead would tear down probes installed by some other party (another
     * agent, a test harness, an app using DebugProbes directly) — CR-01.
     */
    private var installedByUs = false

    /** Increment the ref-count; install DebugProbes on the 0→1 transition. */
    @Synchronized
    fun acquire() {
        val count = refCount.incrementAndGet()
        if (count == 1 && !DebugProbes.isInstalled) {
            DebugProbes.enableCreationStackTraces = true
            DebugProbes.install()
            installedByUs = true
            logger.info("DebugProbes installed (enableCreationStackTraces=true)")
        }
    }

    /** Decrement the ref-count; uninstall DebugProbes on the 1→0 transition. */
    @Synchronized
    fun release() {
        val count = refCount.updateAndGet { if (it > 0) it - 1 else 0 }
        if (count == 0 && installedByUs) {
            uninstallQuietly()
            installedByUs = false
        }
    }

    /**
     * `DebugProbes.uninstall()` triggers a ByteBuddy class re-transformation that
     * can fail with `UnsupportedOperationException: class redefinition failed`
     * under some JDK/agent combinations (notably JDK 21 with a dynamically-loaded
     * byte-buddy agent). The probes are JVM-global and remain functionally
     * harmless if a teardown re-transform fails, so we log and swallow rather than
     * propagating an environmental teardown error into source/session lifecycle.
     */
    private fun uninstallQuietly() {
        try {
            DebugProbes.uninstall()
            logger.info("DebugProbes uninstalled (last source released)")
        } catch (t: UnsupportedOperationException) {
            logger.warn("DebugProbes.uninstall() failed during teardown (environmental); probes left installed", t)
        }
    }

    /** Current ref-count (test/diagnostic). */
    fun count(): Int = refCount.get()

    /**
     * Test-only reset: force the ref-count to zero and uninstall if installed, so
     * a shared-JVM test suite does not leak install state across tests.
     */
    @Synchronized
    fun resetForTest() {
        refCount.set(0)
        if (DebugProbes.isInstalled) {
            uninstallQuietly()
        }
        installedByUs = false
    }

    /** Test/diagnostic: did THIS object install DebugProbes (CR-01 ownership). */
    fun installedByUs(): Boolean = installedByUs
}
