package com.jh.proj.coroutineviz.experiment

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookExperimentConfigTest {
    private val even = mapOf("classic" to 25, "a" to 25, "b" to 25, "c" to 25)

    @Test
    fun `the known looks are the four on the session page, in the registry's order`() {
        assertEquals(listOf("classic", "a", "b", "c"), LookIds.ALL)
        assertEquals("B · Calm", LookIds.label("b"))
    }

    @Test
    fun `off by default with even weights and no survey`() {
        val config = LookExperimentConfig.fromConfig(MapApplicationConfig())

        assertFalse(config.enabled)
        assertEquals(even, config.weights)
        assertNull(config.surveyUrl)
    }

    @Test
    fun `application yaml gives the same defaults, colons in the weights included`() {
        val config = LookExperimentConfig.fromConfig(YamlConfig("application.yaml")!!)

        assertFalse(config.enabled)
        assertEquals(even, config.weights)
        assertNull(config.surveyUrl)
    }

    @Test
    fun `reads the switch, the weights and the survey link`() {
        val config =
            LookExperimentConfig.fromConfig(
                MapApplicationConfig(
                    "lookExperiment.enabled" to "true",
                    "lookExperiment.weights" to "classic:10, b:30 ,c:60",
                    "lookExperiment.surveyUrl" to "https://example.org/survey?src=vizcore",
                ),
            )

        assertTrue(config.enabled)
        assertEquals(mapOf("classic" to 10, "a" to 0, "b" to 30, "c" to 60), config.weights)
        assertEquals("https://example.org/survey?src=vizcore", config.surveyUrl)
    }

    @Test
    fun `bad weights fall back to the even split`() {
        listOf("", "classic", "classic:x", "z:10", "a:-1", "a:0,b:0", "a:1001", "a:1,a:2").forEach { raw ->
            assertEquals(even, LookExperimentConfig.parseWeights(raw), "for '$raw'")
        }
    }

    @Test
    fun `only an absolute http or https survey link is kept`() {
        assertEquals("http://x.org/s", LookExperimentConfig.parseSurveyUrl("http://x.org/s"))
        listOf("", "  ", "javascript:alert(1)", "/relative", "ftp://x.org", "https://").forEach { raw ->
            assertNull(LookExperimentConfig.parseSurveyUrl(raw), "for '$raw'")
        }
    }

    @Test
    fun `a look is in the split when its weight is above zero`() {
        val config = LookExperimentConfig(enabled = true, weights = LookExperimentConfig.parseWeights("a:1,b:1"), surveyUrl = null)

        assertEquals(listOf("a", "b"), config.inSplit)
    }
}
