package com.jh.coroutinevisualizer.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts the bundled `coroutine-viz-agent` fat-jar (Plan 01) from the plugin's own classpath
 * to a real file on disk so it can be referenced by an absolute `-javaagent:<path>` VM argument
 * ([com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension], IDE-01).
 *
 * The jar lives on the classpath at `/agent/coroutine-viz-agent.jar` (wired into the plugin's
 * `processResources` by Plan 07's build step). It is copied ONCE, lazily, into a temp file whose
 * path contains NO spaces — `Files.createTempFile` produces a name under the system temp dir
 * (`java.io.tmpdir`, conventionally space-free) rather than the IDE config directory, which on
 * macOS is `.../Application Support/...` and would break the javaagent arg on the embedded space
 * (RESEARCH Pitfall 6 / T-13-14). The file is marked `deleteOnExit()` so it is cleaned up when
 * the IDE shuts down.
 *
 * Sourced from the plugin's own signed classpath resource — never a network or request-supplied
 * path (T-13-15 accept / T-13-SC).
 */
object AgentJarExtractor {
    /** Classpath location of the bundled agent fat-jar (placed there by the Plan 07 build wire). */
    const val RESOURCE_PATH: String = "/agent/coroutine-viz-agent.jar"

    private val cached: Path by lazy { extract() }

    private fun extract(): Path {
        // createTempFile uses java.io.tmpdir — a space-free path on the supported platforms,
        // avoiding the IDE-config-with-spaces javaagent bug (Pitfall 6).
        val tmp = Files.createTempFile("coroutine-viz-agent", ".jar")
        val stream =
            AgentJarExtractor::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: error("bundled agent jar not found on classpath at $RESOURCE_PATH")
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        tmp.toFile().deleteOnExit()
        return tmp
    }

    /** Absolute, space-free filesystem path to the extracted agent jar (extracted once, cached). */
    fun path(): String = cached.toString()
}
