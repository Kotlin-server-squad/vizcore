package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ref-count + ownership tests for the JVM-global [DebugProbesInstall].
 *
 * Touches REAL `DebugProbes.install()/uninstall()` (a process-wide singleton
 * driven by a byte-buddy re-transform), so it is `@Tag("integration")` and
 * excluded from the deterministic gate (`:coroutine-viz-core:test` excludes the
 * `integration` tag). Run explicitly with `-PincludeIntegration`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class DebugProbesInstallTest {
    @BeforeEach
    fun reset() {
        DebugProbesInstall.resetForTest()
    }

    @AfterEach
    fun cleanup() {
        DebugProbesInstall.resetForTest()
    }

    @Test
    fun `acquire installs on 0 to 1 and release uninstalls on 1 to 0`() {
        assertFalse(DebugProbes.isInstalled)

        DebugProbesInstall.acquire()
        assertEquals(1, DebugProbesInstall.count())
        assertTrue(DebugProbes.isInstalled)
        assertTrue(DebugProbesInstall.installedByUs())

        DebugProbesInstall.release()
        assertEquals(0, DebugProbesInstall.count())
        assertFalse(DebugProbes.isInstalled)
        assertFalse(DebugProbesInstall.installedByUs())
    }

    @Test
    fun `nested acquire only the last release uninstalls`() {
        DebugProbesInstall.acquire()
        DebugProbesInstall.acquire()
        assertEquals(2, DebugProbesInstall.count())
        assertTrue(DebugProbes.isInstalled)

        DebugProbesInstall.release()
        assertEquals(1, DebugProbesInstall.count())
        assertTrue(DebugProbes.isInstalled, "still installed while a source holds the ref")

        DebugProbesInstall.release()
        assertEquals(0, DebugProbesInstall.count())
        assertFalse(DebugProbes.isInstalled)
    }

    /**
     * CR-01 regression: if DebugProbes was already installed by an EXTERNAL party
     * before our first acquire, release MUST NOT uninstall it — we never owned it.
     * The asymmetric guard (acquire keys off `!isInstalled`, release keyed off the
     * global `isInstalled`) would tear down the external party's probes.
     */
    @Test
    fun `release does not uninstall probes installed by an external party`() {
        // External party installs DebugProbes directly (not via our ref-count).
        DebugProbes.install()
        try {
            assertTrue(DebugProbes.isInstalled)
            assertFalse(DebugProbesInstall.installedByUs(), "we did not install it")

            // Our source acquires: count 0->1, but we must NOT (re)install or claim ownership.
            DebugProbesInstall.acquire()
            assertEquals(1, DebugProbesInstall.count())
            assertFalse(DebugProbesInstall.installedByUs(), "acquire over an external install must not claim ownership")

            // Our source releases: count 1->0. Because we never installed it, the
            // external party's probes MUST remain installed.
            DebugProbesInstall.release()
            assertEquals(0, DebugProbesInstall.count())
            assertTrue(
                DebugProbes.isInstalled,
                "external party's DebugProbes must survive our release (we never owned the install)",
            )
        } finally {
            // Clean up the external install ourselves.
            DebugProbes.uninstall()
        }
    }
}
