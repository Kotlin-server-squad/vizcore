package com.jh.proj.coroutineviz.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spring Boot pins kotlin-stdlib to the app's Kotlin version and, on 3.x, kotlinx-coroutines
 * to 1.8.1. With either too old, `VizcoreClient.start` died with a bare NoSuchMethodError or
 * NoClassDefFoundError. The SDK now checks first and says what to change.
 */
class RuntimeRequirementsTest {
    @Test
    fun `the test classpath meets the requirements`() {
        val found = RuntimeRequirements.coroutinesVersion()
        assertNotNull(found)
        assertTrue(RuntimeRequirements.isAtLeast(found, RuntimeRequirements.MIN_COROUTINES_VERSION))
        assertDoesNotThrow { RuntimeRequirements.check() }
    }

    @Test
    fun `Spring Boot 3 coroutines version is refused with the override to apply`() {
        val problem = RuntimeRequirements.coroutinesProblem("1.8.1")
        assertNotNull(problem)
        assertTrue("1.8.1" in problem, problem)
        assertTrue("extra[\"kotlin-coroutines.version\"] = \"1.10.2\"" in problem, problem)
    }

    @Test
    fun `an unknown coroutines version is not a problem`() {
        assertNull(RuntimeRequirements.coroutinesProblem(null))
    }

    @Test
    fun `a stdlib older than 2_2 is refused with the plugin to update`() {
        val problem = RuntimeRequirements.kotlinStdlibProblem(KotlinVersion(2, 0, 21))
        assertNotNull(problem)
        assertTrue("2.0.21" in problem, problem)
        assertTrue("Kotlin Gradle plugin" in problem, problem)
        assertNull(RuntimeRequirements.kotlinStdlibProblem(KotlinVersion(2, 2, 0)))
        assertNull(RuntimeRequirements.kotlinStdlibProblem(KotlinVersion(2, 4, 0)))
    }

    @Test
    fun `versions compare numerically and order a pre-release before its release`() {
        assertTrue(RuntimeRequirements.isAtLeast("1.10.2", "1.10.2"))
        assertTrue(RuntimeRequirements.isAtLeast("1.11.0", "1.10.2"))
        assertTrue(RuntimeRequirements.isAtLeast("2.0", "1.10.2"))
        assertTrue(RuntimeRequirements.isAtLeast("1.10.10", "1.10.2"))
        assertFalse(RuntimeRequirements.isAtLeast("1.9.0", "1.10.2"))
        assertFalse(RuntimeRequirements.isAtLeast("1.10.1", "1.10.2"))
        assertFalse(RuntimeRequirements.isAtLeast("1.10.2-RC", "1.10.3"))
        assertFalse(RuntimeRequirements.isAtLeast("1.10.2-RC", "1.10.2"))
        assertTrue(RuntimeRequirements.isAtLeast("1.10.3-RC", "1.10.2"))
        assertTrue(RuntimeRequirements.isAtLeast("1.10.2-RC2", "1.10.2-RC"))
    }

    @Test
    fun `a linkage error from an undetected old library still gets the requirements message`() {
        val cause = NoSuchMethodError("kotlinx.coroutines.BuildersKt.runBlockingK")
        val error = RuntimeRequirements.linkageFailure(cause)
        val message = error.message.orEmpty()
        assertTrue("runBlockingK" in message, message)
        assertTrue("kotlinx-coroutines is 1.10.2 or newer" in message, message)
        assertTrue("missing or older" in message, message)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `the coroutines version comes from its version resource`() {
        val dir = Files.createTempDirectory("coroutines-version")
        val resource = dir.resolve("META-INF/kotlinx_coroutines_core.version")
        Files.createDirectories(resource.parent)
        Files.writeString(resource, "1.8.1\n")
        URLClassLoader(arrayOf<URL>(dir.toUri().toURL()), null).use { loader ->
            assertEquals("1.8.1", RuntimeRequirements.coroutinesVersion(loader))
        }
        URLClassLoader(arrayOf<URL>(), null).use { empty ->
            assertNull(RuntimeRequirements.coroutinesVersion(empty))
        }
    }
}
