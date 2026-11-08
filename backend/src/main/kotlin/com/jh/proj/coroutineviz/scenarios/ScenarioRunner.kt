package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * Runs a custom scenario built in the frontend. The Real life demos are in [RealLifeScenarios].
 */
object ScenarioRunner {
    private val logger = LoggerFactory.getLogger(ScenarioRunner::class.java)

    /**
     * Run a custom scenario from configuration.
     * This allows dynamic scenario creation from the frontend.
     */
    suspend fun runCustomScenario(
        session: VizSession,
        config: ScenarioConfig,
    ): Job =
        coroutineScope {
            logger.info("Starting custom scenario '${config.name}' in session: ${session.sessionId}")
            if (config.description != null) {
                logger.info("Description: ${config.description}")
            }

            val viz = session.demoScope()

            // Execute the root coroutine configuration recursively
            val job = viz.executeCoroutineConfig(config.root)

            logger.info("Custom scenario '${config.name}' launched, waiting for completion...")
            job
        }
}
