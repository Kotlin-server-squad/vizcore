package com.jh.proj.coroutineviz.experiment

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * The looks the session page can wear, as the frontend's `looks/registry.ts` lists them, in its order.
 * Deleting a look after the experiment means deleting it here and there (each side has a test pinning
 * its list).
 */
object LookIds {
    val ALL: List<String> = listOf("classic", "a", "b", "c")

    private val LABELS = mapOf("classic" to "Classic", "a" to "A · Compact", "b" to "B · Calm", "c" to "C · Bold")

    fun isKnown(id: String?): Boolean = id in ALL

    fun label(id: String): String = LABELS[id] ?: id
}

/**
 * The look experiment's settings (`lookExperiment.*`, env `LOOK_EXPERIMENT_ENABLED`, `LOOK_EXPERIMENT`,
 * `LOOK_SURVEY_URL`). Read at startup; a restart applies a change, no release needed.
 *
 * @property weights every known look's share of new visitors (0 = not in the split), in [LookIds.ALL] order.
 * @property surveyUrl an absolute http(s) link to an outside survey, or null.
 */
data class LookExperimentConfig(
    val enabled: Boolean,
    val weights: Map<String, Int>,
    val surveyUrl: String?,
) {
    /** The looks new visitors can be given. */
    val inSplit: List<String> get() = LookIds.ALL.filter { (weights[it] ?: 0) > 0 }

    companion object {
        private val logger = LoggerFactory.getLogger(LookExperimentConfig::class.java)

        const val MAX_WEIGHT = 1000
        private const val DEFAULT_WEIGHT = 25

        val DEFAULT_WEIGHTS: Map<String, Int> = LookIds.ALL.associateWith { DEFAULT_WEIGHT }

        fun fromConfig(config: ApplicationConfig): LookExperimentConfig =
            LookExperimentConfig(
                enabled =
                    config
                        .propertyOrNull("lookExperiment.enabled")
                        ?.getString()
                        ?.trim()
                        .toBoolean(),
                weights = parseWeights(config.propertyOrNull("lookExperiment.weights")?.getString()),
                surveyUrl = parseSurveyUrl(config.propertyOrNull("lookExperiment.surveyUrl")?.getString()),
            )

        /**
         * `classic:25,a:25,b:25,c:25`: known looks, whole weights 0–[MAX_WEIGHT], each once, total above 0.
         * A known look left out gets 0. Anything else is a mistake: warn and use the even split.
         */
        fun parseWeights(raw: String?): Map<String, Int> {
            val parsed = raw?.takeIf { it.isNotBlank() }?.let(::weightsOrNull)
            if (parsed == null && !raw.isNullOrBlank()) {
                logger.warn("LOOK_EXPERIMENT '{}' is not a valid list of look:weight pairs; using the even split", raw)
            }
            return parsed ?: DEFAULT_WEIGHTS
        }

        private fun weightsOrNull(raw: String): Map<String, Int>? {
            val pairs =
                raw.split(',').map { part ->
                    val id = part.substringBefore(':', missingDelimiterValue = "").trim()
                    val weight = part.substringAfter(':', missingDelimiterValue = "").trim().toIntOrNull()
                    id to weight
                }
            val valid =
                pairs.all { (id, weight) -> LookIds.isKnown(id) && weight != null && weight in 0..MAX_WEIGHT } &&
                    pairs.map { it.first }.toSet().size == pairs.size &&
                    pairs.sumOf { it.second ?: 0 } > 0
            val given = pairs.associate { (id, weight) -> id to (weight ?: 0) }
            return if (valid) LookIds.ALL.associateWith { given[it] ?: 0 } else null
        }

        /** The survey link when it is an absolute http(s) URL with a host; anything else is ignored. */
        fun parseSurveyUrl(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val uri = runCatching { URI(value) }.getOrNull()
            val ok = uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
            if (!ok) logger.warn("LOOK_SURVEY_URL is not an absolute http(s) link; no survey link is shown")
            return value.takeIf { ok }
        }
    }
}
