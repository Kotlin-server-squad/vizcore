package com.jh.vizcore.demo

import com.jh.proj.coroutineviz.client.VizcoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component

@SpringBootApplication
class SpringVizcoreDemoApplication

fun main(args: Array<String>) {
	runApplication<SpringVizcoreDemoApplication>(*args)
}

/**
 * Starts the embeddable [VizcoreClient] against a running vizcore backend, then drives a
 * continuous mix of structured-concurrency workloads. kotlinx-coroutines DebugProbes (installed
 * by the client's DebugProbesSource) captures the live coroutines in THIS JVM and the client
 * streams the synthesized events to the backend over a WebSocket — where they flow through the
 * existing EventStore -> snapshot -> EventBus -> SSE -> frontend pipeline.
 *
 * Runs until Ctrl+C.
 */
@Component
class VizcoreDemoRunner(
	@param:Value("\${vizcore.app-name:spring-vizcore-demo}") private val appName: String,
	@param:Value("\${vizcore.backend-url:http://localhost:8080}") private val backendUrl: String,
	@param:Value("\${vizcore.token:demo-token}") private val token: String,
	@param:Value("\${vizcore.continuous:false}") private val continuous: Boolean,
	@param:Value("\${vizcore.embedded-client:true}") private val embeddedClient: Boolean,
	// > 0: one coroutine holds a Default thread this long without suspending (the session's "possibly blocked").
	@param:Value("\${vizcore.busy-ms:0}") private val busyMs: Long,
	// > 0: every request also launches this many `fetch-1 … fetch-N` children (a big app: hundreds of siblings per round).
	@param:Value("\${vizcore.fan-out:0}") private val fanOut: Int,
) : CommandLineRunner {
	private val logger = LoggerFactory.getLogger(VizcoreDemoRunner::class.java)

	override fun run(vararg args: String) {
		// When embeddedClient=false (agent-UAT mode) we run ONLY the workload and let an external
		// `-javaagent` (the IntelliJ plugin's agent) be the sole instrumenter — avoiding the
		// self-instrumenting double-client hazard. When true (default) the app self-instruments.
		if (embeddedClient) {
			logger.info("Starting embedded VizcoreClient: app='{}' -> {}", appName, backendUrl)
			val client = VizcoreClient.start(appName = appName, backendUrl = backendUrl, token = token)
			Runtime.getRuntime().addShutdownHook(Thread { client.stop() })
			if (busyMs > 0) {
				// Holds a Default thread without suspending: what the session calls "possibly blocked" (over 10 s running).
				CoroutineScope(Dispatchers.Default).launch(CoroutineName("busy-worker")) {
					logger.info("busy-worker blocks its thread for {} ms", busyMs)
					Thread.sleep(busyMs)
				}
			}
		} else {
			logger.info("Embedded VizcoreClient DISABLED (vizcore.embedded-client=false) — expecting an external -javaagent to capture this JVM.")
		}

		// Bounded, NAMED fixture (not a firehose). Every coroutine carries a CoroutineName so the
		// labels populate in the UI today, and the parent/child shape is well-defined so this
		// doubles as the validation fixture for the Phase-8 hierarchy reconstruction. A small
		// number of slow rounds keeps the session legible (tens of coroutines, not thousands).
		runBlocking {
			if (continuous) {
				// Investigation/UAT mode: never stop. One stable session whose coroutines are
				// continuously created/suspended/resumed so the live view always has motion.
				var round = 0
				while (isActive) {
					round += 1
					logger.info("workload round {} (continuous)", round)
					runWorkloadRound(round)
					delay(CONTINUOUS_PAUSE_MS)
				}
			} else {
				repeat(ROUNDS) { i ->
					val round = i + 1
					logger.info("workload round {}/{}", round, ROUNDS)
					runWorkloadRound(round)
					delay(ROUND_PAUSE_MS)
				}
				logger.info("workload complete ({} rounds). Holding the client open so the session stays viewable; Ctrl+C to exit.", ROUNDS)
				awaitCancellation()
			}
		}
	}

	/**
	 * One round = a named parent fanning out a small, well-defined set of named children:
	 * `request-N` -> { db-query-N, http-call-N, compute-N (async), pipeline-N -> stage-N }.
	 * Longer-lived than a tight loop so coroutines are observable as ACTIVE/SUSPENDED in the UI.
	 */
	private suspend fun runWorkloadRound(round: Int) =
		coroutineScope {
			launch(CoroutineName("request-$round")) {
				coroutineScope {
					launch(CoroutineName("db-query-$round")) { delay(400) }
					launch(CoroutineName("http-call-$round")) { delay(550) }

					val computed =
						async(Dispatchers.Default + CoroutineName("compute-$round")) {
							delay(300)
							(1..1000).sum()
						}

					// Siblings whose names differ only in a trailing number: the session page groups them by name.
					repeat(fanOut) { k ->
						launch(CoroutineName("fetch-${k + 1}")) { delay(FETCH_BASE_MS + (k % FETCH_SPREAD) * FETCH_STEP_MS) }
					}

					launch(CoroutineName("pipeline-$round")) {
						flow {
							repeat(4) { delay(120); emit(it) }
						}.map { it * it }.collect { /* drain */ }
					}

					withContext(Dispatchers.IO + CoroutineName("io-write-$round")) {
						delay(250)
					}
					logger.debug("round {} compute={}", round, computed.await())
				}
			}.join()
		}

	private companion object {
		const val ROUNDS = 6
		const val ROUND_PAUSE_MS = 4000L
		const val CONTINUOUS_PAUSE_MS = 800L
		const val FETCH_BASE_MS = 300L
		const val FETCH_SPREAD = 10
		const val FETCH_STEP_MS = 150L
	}
}
