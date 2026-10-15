package com.jh.proj.coroutineviz.client

import kotlinx.coroutines.Job

/**
 * Checks the app's runtime classpath before [VizcoreClient.start] touches anything that
 * needs newer Kotlin libraries than the app provides.
 *
 * The SDK and its Ktor client need kotlin-stdlib [MIN_KOTLIN_STDLIB] and kotlinx-coroutines
 * [MIN_COROUTINES_VERSION]. Spring Boot pins both through its dependency management: the
 * stdlib to the app's own Kotlin version, and kotlinx-coroutines to 1.8.1 on Spring Boot
 * 3.x. Those versions win over the SDK's, and the first SDK call then fails with a bare
 * `NoSuchMethodError` or `NoClassDefFoundError` from deep inside a library. This check
 * replaces that with a message that says what to change.
 */
internal object RuntimeRequirements {
    const val MIN_COROUTINES_VERSION = "1.10.2"
    val MIN_KOTLIN_STDLIB = KotlinVersion(2, 2)

    private const val COROUTINES_VERSION_RESOURCE = "META-INF/kotlinx_coroutines_core.version"

    /** Throws [IllegalStateException] naming every requirement the runtime classpath misses. */
    fun check() {
        val problems = listOfNotNull(kotlinStdlibProblem(), coroutinesProblem())
        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "vizcore: " + problems.joinToString(" ") + " See the Requirements section of the vizcore README.",
            )
        }
    }

    /** A description of what to change when [current] is older than [MIN_KOTLIN_STDLIB], else null. */
    fun kotlinStdlibProblem(current: KotlinVersion = KotlinVersion.CURRENT): String? =
        if (current >= MIN_KOTLIN_STDLIB) {
            null
        } else {
            "The app runs kotlin-stdlib $current, but the vizcore SDK needs $MIN_KOTLIN_STDLIB or newer. " +
                "Spring Boot pins the stdlib to the app's Kotlin version: update the Kotlin Gradle plugin " +
                "to $MIN_KOTLIN_STDLIB or newer."
        }

    /**
     * A description of what to change when [found] is older than [MIN_COROUTINES_VERSION], else
     * null. An unknown version (for example a shaded or repackaged jar) is not treated as a problem.
     */
    fun coroutinesProblem(found: String? = coroutinesVersion()): String? =
        if (found == null || isAtLeast(found, MIN_COROUTINES_VERSION)) {
            null
        } else {
            "The app runs kotlinx-coroutines $found, but the vizcore SDK needs $MIN_COROUTINES_VERSION or newer. " +
                "Spring Boot 3.x pins an older version; override it in build.gradle.kts with " +
                "extra[\"kotlin-coroutines.version\"] = \"$MIN_COROUTINES_VERSION\"."
        }

    /**
     * Turns a [LinkageError] from the start path into a message that points at the requirements.
     * [check] can miss an old library (a repackaged jar without its version resource, or an
     * unusual class loader), and the start path would then fail with the bare error again. The
     * error can also come from a missing library, so the message names both possibilities.
     */
    fun linkageFailure(error: LinkageError): IllegalStateException =
        IllegalStateException(
            "vizcore: the SDK could not start because a library it uses is missing or older than it " +
                "needs ($error). Check that kotlin-stdlib is $MIN_KOTLIN_STDLIB or newer, kotlinx-coroutines " +
                "is $MIN_COROUTINES_VERSION or newer, and the SDK's own dependencies (Ktor client, " +
                "kotlinx-coroutines-debug, kotlinx-serialization) are on the runtime classpath. " +
                "See the Requirements section of the vizcore README.",
            error,
        )

    /** The kotlinx-coroutines version on the classpath, or null when the jar does not say. */
    fun coroutinesVersion(loader: ClassLoader? = Job::class.java.classLoader): String? =
        loader
            ?.getResource(COROUTINES_VERSION_RESOURCE)
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Compares dotted versions numerically. A pre-release (`1.10.2-RC`) comes before its release,
     * so it does not meet a minimum of `1.10.2`.
     */
    internal fun isAtLeast(
        version: String,
        minimum: String,
    ): Boolean {
        val actual = numericParts(version)
        val required = numericParts(minimum)
        for (i in 0 until maxOf(actual.size, required.size)) {
            val a = actual.getOrElse(i) { 0 }
            val r = required.getOrElse(i) { 0 }
            if (a != r) return a > r
        }
        return !isPreRelease(version) || isPreRelease(minimum)
    }

    private fun isPreRelease(version: String): Boolean = '-' in version

    private fun numericParts(version: String): List<Int> =
        version
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
