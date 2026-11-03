package com.jh.proj.coroutineviz.deploy

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The demo settings (application.yaml's `scenarios` section) are documented as environment
 * variables in docs/DEPLOYMENT.md, so both Compose files must pass them on to the backend
 * container: a variable Compose does not forward is silently ignored there.
 */
class ComposeDemoSettingsTest {
    /** The environment variables application.yaml's `scenarios` section reads. */
    private fun demoSettings(): List<String> {
        val yaml = File("src/main/resources/application.yaml").readLines()
        val section = yaml.dropWhile { it != "scenarios:" }.drop(1).takeWhile { it.isBlank() || it.startsWith(" ") }
        return section.mapNotNull { Regex("""\$\{([A-Z_]+):""").find(it)?.groupValues?.get(1) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["../docker-compose.yml", "../docker-compose.prod.yml"])
    fun `the backend container gets every demo setting from the environment`(composeFile: String) {
        val settings = demoSettings()
        assertTrue("DEMO_TIME_SCALE" in settings && "MAX_CONCURRENT_DEMOS" in settings, "found $settings")
        val compose = File(composeFile).readText()

        assertEquals(
            emptyList(),
            settings.filterNot { Regex("""- $it=\$\{$it:-[^}]+}""").containsMatchIn(compose) },
            "$composeFile does not pass these on to the backend",
        )
    }
}
