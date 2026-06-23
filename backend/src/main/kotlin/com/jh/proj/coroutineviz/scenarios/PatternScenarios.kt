package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-world coroutine pattern scenarios for visualization.
 *
 * These scenarios demonstrate common concurrency patterns used in
 * production Kotlin applications, including retry logic, producer-consumer,
 * fan-out/fan-in, supervisor patterns, and circuit breakers.
 */
object PatternScenarios {
    private val logger = LoggerFactory.getLogger(PatternScenarios::class.java)

    // ========================================================================
    // RETRY WITH EXPONENTIAL BACKOFF
    // ========================================================================

    /**
     * Retry a failing operation 3 times with exponential backoff (100ms, 200ms, 400ms).
     *
     * Demonstrates the structured retry pattern commonly used for transient failures
     * such as network timeouts, temporary service unavailability, or rate limiting.
     */
    suspend fun retryWithExponentialBackoff(scope: VizScope) {
        val attemptCounter = AtomicInteger(0)

        scope
            .vizLaunch("retry-controller") {
                var lastException: Exception? = null
                val maxRetries = 3
                var delayMs = 100L

                for (attempt in 1..maxRetries) {
                    val currentAttempt = attempt
                    val currentDelay = delayMs

                    val job =
                        vizLaunch("attempt-$currentAttempt") {
                            logger.debug("Attempt $currentAttempt: calling unreliable service...")
                            vizDelay(80)

                            val count = attemptCounter.incrementAndGet()
                            if (count < maxRetries) {
                                error("Service unavailable (attempt $currentAttempt)")
                            }
                            logger.debug("Attempt $currentAttempt: service responded successfully")
                        }

                    try {
                        job.join()
                        if (job.isCancelled) {
                            throw lastException ?: error("Attempt $currentAttempt failed")
                        }
                        logger.debug("Operation succeeded on attempt $currentAttempt")
                        return@vizLaunch
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            lastException = IllegalStateException("Attempt $currentAttempt failed")
                        } else {
                            lastException = e
                        }
                        logger.debug("Attempt $currentAttempt failed: ${lastException?.message}")

                        if (currentAttempt < maxRetries) {
                            vizLaunch("backoff-${currentDelay}ms") {
                                logger.debug("Waiting ${currentDelay}ms before retry...")
                                vizDelay(currentDelay)
                            }.join()
                            delayMs *= 2
                        }
                    }
                }

                if (lastException != null) {
                    logger.debug("All $maxRetries attempts exhausted")
                }
            }.join()
    }

    // ========================================================================
    // PRODUCER-CONSUMER
    // ========================================================================

    /**
     * Producer sends items to a buffered channel (capacity 5), consumer processes
     * them with delay.
     *
     * Demonstrates channel-based communication between coroutines, which is the
     * recommended way to share data between coroutines instead of shared mutable state.
     */
    suspend fun producerConsumer(scope: VizScope) {
        val channel = scope.vizChannel<String>(5, "task-queue")

        scope
            .vizLaunch("producer-consumer") {
                val producer =
                    vizLaunch("producer") {
                        val items = listOf("order-1", "order-2", "order-3", "order-4", "order-5", "order-6", "order-7")
                        for (item in items) {
                            logger.debug("Producer sending $item")
                            channel.send(item)
                            vizDelay(50)
                        }
                        channel.close()
                        logger.debug("Producer finished, channel closed")
                    }

                val consumer =
                    vizLaunch("consumer") {
                        vizDelay(100) // Let buffer fill a bit
                        try {
                            while (true) {
                                val item = channel.receive()
                                logger.debug("Consumer processing $item")
                                vizDelay(150) // Slower consumer to show backpressure
                                logger.debug("Consumer finished $item")
                            }
                        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                            logger.debug("Consumer: channel closed, done")
                        }
                    }

                producer.join()
                consumer.join()
                logger.debug("Producer-consumer pattern completed")
            }.join()
    }

    // ========================================================================
    // FAN-OUT / FAN-IN
    // ========================================================================

    /**
     * One producer, 3 worker coroutines that process in parallel, results collected
     * via channel.
     *
     * Demonstrates the fan-out/fan-in pattern for distributing work across multiple
     * workers and collecting results. Common in data processing pipelines.
     */
    suspend fun fanOutFanIn(scope: VizScope) {
        val taskChannel = scope.vizChannel<Int>(Channel.BUFFERED, "tasks")
        val resultChannel = scope.vizChannel<String>(Channel.BUFFERED, "results")

        scope
            .vizLaunch("fan-out-fan-in") {
                // Producer: generates tasks
                val producer =
                    vizLaunch("task-producer") {
                        for (taskId in 1..9) {
                            logger.debug("Dispatching task $taskId")
                            taskChannel.send(taskId)
                            vizDelay(30)
                        }
                        taskChannel.close()
                        logger.debug("All tasks dispatched")
                    }

                // Fan-out: 3 workers process tasks in parallel
                val workers =
                    (1..3).map { workerId ->
                        vizLaunch("worker-$workerId") {
                            try {
                                while (true) {
                                    val taskId = taskChannel.receive()
                                    logger.debug("Worker $workerId processing task $taskId")
                                    vizDelay((80..200).random().toLong())
                                    resultChannel.send("task-$taskId-by-worker-$workerId")
                                    logger.debug("Worker $workerId completed task $taskId")
                                }
                            } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                                logger.debug("Worker $workerId: no more tasks")
                            }
                        }
                    }

                // Fan-in: collector gathers results
                val collector =
                    vizLaunch("result-collector") {
                        var count = 0
                        try {
                            while (count < 9) {
                                val result = resultChannel.receive()
                                count++
                                logger.debug("Collected result $count: $result")
                            }
                        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                            logger.debug("Result collector: channel closed")
                        }
                        logger.debug("Collected all $count results")
                    }

                producer.join()
                workers.forEach { it.join() }
                resultChannel.close()
                collector.join()
                logger.debug("Fan-out/fan-in pattern completed")
            }.join()
    }

    // ========================================================================
    // SUPERVISOR PATTERN
    // ========================================================================

    /**
     * Launch with SupervisorJob where one child fails but siblings continue.
     *
     * Demonstrates error isolation in structured concurrency. With a SupervisorJob,
     * a failing child does not cancel its siblings, which is essential for services
     * that must remain partially available.
     */
    suspend fun supervisorPattern(scope: VizScope) {
        scope
            .vizLaunch("supervisor") {
                // Launch children — one will fail
                val stableChild1 =
                    vizLaunch("stable-service-A") {
                        logger.debug("Service A: starting")
                        vizDelay(200)
                        logger.debug("Service A: processing...")
                        vizDelay(400)
                        logger.debug("Service A: completed successfully")
                    }

                val failingChild =
                    vizLaunch("failing-service-B") {
                        logger.debug("Service B: starting")
                        vizDelay(150)
                        logger.debug("Service B: about to fail!")
                        error("Service B encountered a fatal error")
                    }

                val stableChild2 =
                    vizLaunch("stable-service-C") {
                        logger.debug("Service C: starting")
                        vizDelay(300)
                        logger.debug("Service C: processing...")
                        vizDelay(300)
                        logger.debug("Service C: completed successfully")
                    }

                // Wait for stable children; handle failing child gracefully
                try {
                    failingChild.join()
                } catch (e: Exception) {
                    logger.debug("Supervisor caught failure from Service B: ${e.message}")
                }

                stableChild1.join()
                stableChild2.join()

                vizLaunch("health-reporter") {
                    logger.debug("Health report: Service A OK, Service B FAILED, Service C OK")
                    vizDelay(50)
                }.join()

                logger.debug("Supervisor pattern completed — partial failure handled")
            }.join()
    }

    // ========================================================================
    // CIRCUIT BREAKER
    // ========================================================================

    /**
     * Simple circuit breaker: after N failures, stop trying for a cooldown period.
     *
     * Demonstrates the circuit breaker resilience pattern. After consecutive failures
     * exceed a threshold, the circuit opens and subsequent calls fail fast without
     * hitting the downstream service, giving it time to recover.
     */
    suspend fun circuitBreaker(scope: VizScope) {
        val failureThreshold = 3
        val cooldownMs = 300L
        val failureCount = AtomicInteger(0)
        val circuitOpen = AtomicBoolean(false)
        val lastFailureTime = AtomicLong(0L)

        scope
            .vizLaunch("circuit-breaker") {
                // Simulate a series of calls through the circuit breaker
                for (requestId in 1..8) {
                    vizLaunch("request-$requestId") {
                        // Check circuit state
                        if (circuitOpen.get()) {
                            val elapsed = System.currentTimeMillis() - lastFailureTime.get()
                            if (elapsed < cooldownMs) {
                                logger.debug("Request $requestId: REJECTED (circuit open, cooldown ${cooldownMs - elapsed}ms remaining)")
                                vizDelay(30) // Small delay to visualize the fast-fail
                                return@vizLaunch
                            } else {
                                logger.debug("Request $requestId: circuit half-open, attempting probe...")
                                circuitOpen.set(false)
                                failureCount.set(0)
                            }
                        }

                        // Attempt the call — first 4 requests fail, rest succeed
                        vizLaunch("downstream-call-$requestId") {
                            vizDelay(80)
                            if (requestId <= 4) {
                                error("Downstream service error")
                            }
                            logger.debug("Request $requestId: downstream call succeeded")
                        }.also { job ->
                            try {
                                job.join()
                                if (!job.isCancelled) {
                                    failureCount.set(0)
                                    logger.debug("Request $requestId: SUCCESS (circuit closed)")
                                } else {
                                    handleFailure(requestId, failureCount, failureThreshold, cooldownMs) {
                                        circuitOpen.set(true)
                                        lastFailureTime.set(System.currentTimeMillis())
                                    }
                                }
                            } catch (e: Exception) {
                                handleFailure(requestId, failureCount, failureThreshold, cooldownMs) {
                                    circuitOpen.set(true)
                                    lastFailureTime.set(System.currentTimeMillis())
                                }
                            }
                        }
                    }.join()

                    // Small gap between requests
                    vizDelay(50)
                }

                logger.debug("Circuit breaker demo completed")
            }.join()
    }

    private fun handleFailure(
        requestId: Int,
        failureCount: AtomicInteger,
        failureThreshold: Int,
        cooldownMs: Long,
        openCircuit: () -> Unit,
    ) {
        val count = failureCount.incrementAndGet()
        if (count >= failureThreshold) {
            logger.debug("Request $requestId: FAILED — circuit OPENED (threshold $failureThreshold reached, cooldown ${cooldownMs}ms)")
            openCircuit()
        } else {
            logger.debug("Request $requestId: FAILED ($count/$failureThreshold)")
        }
    }
}
