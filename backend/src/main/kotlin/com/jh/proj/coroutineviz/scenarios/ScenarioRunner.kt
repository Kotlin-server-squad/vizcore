package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

/**
 * Collection of coroutine scenarios for visualization and teaching.
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

    // ============================================================================
    // CHANNEL SCENARIOS - Demonstrating Channel communication patterns
    // ============================================================================

    /**
     * Rendezvous Channel Scenario
     *
     * Demonstrates a zero-buffer channel where sender and receiver must
     * meet (rendezvous) for each element transfer. The sender suspends
     * until a receiver is ready, and vice versa.
     */
    suspend fun runChannelRendezvous(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting channel rendezvous scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val channel = viz.vizChannel<Int>(Channel.RENDEZVOUS, "rendezvous-channel")

            val job =
                viz.vizLaunch("rendezvous-demo") {
                    val producer =
                        vizLaunch("producer") {
                            for (i in 1..5) {
                                logger.debug("Producer sending $i...")
                                channel.send(i)
                                logger.debug("Producer sent $i")
                                vizDelay(100)
                            }
                            channel.close()
                            logger.debug("Producer closed channel")
                        }

                    val consumer =
                        vizLaunch("consumer") {
                            // Small delay so producer arrives first to demonstrate rendezvous suspension
                            vizDelay(200)
                            try {
                                while (true) {
                                    val value = channel.receive()
                                    logger.debug("Consumer received $value")
                                    // Slower consumer to show suspension
                                    vizDelay(300)
                                }
                            } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                                logger.debug("Consumer: channel closed")
                            }
                        }

                    producer.join()
                    consumer.join()
                    logger.debug("Rendezvous channel demo completed")
                }

            job.join()
            job
        }

    /**
     * Buffered Channel Scenario
     *
     * Demonstrates a buffered channel where the sender can send multiple
     * values without suspending until the buffer is full. Shows buffer
     * state changes as values are sent and received.
     */
    suspend fun runChannelBuffered(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting channel buffered scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val channel = viz.vizChannel<String>(3, "buffered-channel")

            val job =
                viz.vizLaunch("buffered-demo") {
                    val producer =
                        vizLaunch("producer") {
                            val items = listOf("alpha", "beta", "gamma", "delta", "epsilon")
                            for (item in items) {
                                logger.debug("Producer sending $item...")
                                channel.send(item)
                                logger.debug("Producer sent $item")
                            }
                            channel.close()
                            logger.debug("Producer closed channel")
                        }

                    val consumer =
                        vizLaunch("consumer") {
                            // Let buffer fill up first
                            vizDelay(500)
                            try {
                                while (true) {
                                    val value = channel.receive()
                                    logger.debug("Consumer received $value")
                                    vizDelay(200)
                                }
                            } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                                logger.debug("Consumer: channel closed")
                            }
                        }

                    producer.join()
                    consumer.join()
                    logger.debug("Buffered channel demo completed")
                }

            job.join()
            job
        }

    /**
     * Fan-Out Channel Scenario
     *
     * Demonstrates the fan-out pattern where a single producer sends
     * values to a channel and multiple consumers compete to receive them.
     * Each value is received by exactly one consumer.
     */
    suspend fun runChannelFanOut(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting channel fan-out scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val channel = viz.vizChannel<Int>(Channel.BUFFERED, "task-channel")

            val job =
                viz.vizLaunch("fan-out-demo") {
                    // Producer: sends tasks
                    val producer =
                        vizLaunch("task-producer") {
                            for (taskId in 1..10) {
                                logger.debug("Dispatching task $taskId")
                                channel.send(taskId)
                                vizDelay(50)
                            }
                            channel.close()
                            logger.debug("All tasks dispatched")
                        }

                    // Multiple consumers competing for tasks
                    val consumers =
                        (1..3).map { workerId ->
                            vizLaunch("worker-$workerId") {
                                try {
                                    while (true) {
                                        val taskId = channel.receive()
                                        logger.debug("Worker $workerId processing task $taskId")
                                        // Simulate variable work
                                        vizDelay((100..300).random().toLong())
                                        logger.debug("Worker $workerId completed task $taskId")
                                    }
                                } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                                    logger.debug("Worker $workerId: no more tasks")
                                }
                            }
                        }

                    producer.join()
                    consumers.forEach { it.join() }
                    logger.debug("Fan-out demo completed")
                }

            job.join()
            job
        }

    // ============================================================================
    // REALISTIC SCENARIOS - Simulating real-world service interactions
    // ============================================================================

    /**
     * E-commerce Order Processing Scenario
     *
     * Simulates a realistic order processing flow with:
     * - Sequential validation and stock checking
     * - Payment processing (can fail with timeout)
     * - Parallel notification sending
     * - Database operations
     *
     * Delays are intentionally longer (2-5 seconds) for learning purposes.
     */
    suspend fun runOrderProcessingScenario(
        session: VizSession,
        shouldFail: Boolean = false,
    ): Job =
        coroutineScope {
            logger.info("Starting Order Processing scenario in session: ${session.sessionId}")

            val viz = session.demoScope()

            val job =
                viz.vizLaunch("OrderService.processOrder") {
                    logger.debug("📦 Starting order processing...")

                    // Step 1: Validate Order (sequential - must complete first)
                    vizLaunch("OrderValidator.validate") {
                        logger.debug("Validating order data...")
                        vizDelay((2000..2500).random().toLong())
                        logger.debug("✅ Order validation passed")
                    }.join()

                    // Step 2: Check Inventory (API call to inventory service)
                    vizLaunch("InventoryService.checkStock") {
                        logger.debug("🏭 Checking inventory availability...")
                        vizDelay((2500..3500).random().toLong())
                        logger.debug("✅ Stock available")
                    }.join()

                    // Step 3: Process Payment (critical - can fail or timeout)
                    val paymentJob =
                        vizLaunch("PaymentService.processPayment") {
                            logger.debug("💳 Processing payment with payment gateway...")
                            vizDelay((3000..4000).random().toLong())

                            if (shouldFail) {
                                throw demoFailure("Payment declined: Insufficient funds")
                            }
                            logger.debug("✅ Payment processed successfully")
                        }

                    try {
                        paymentJob.join()
                    } catch (e: CancellationException) {
                        // A failed payment cancels this coroutine's join: the demo's failure, not
                        // an error. Rethrow to keep cooperative cancellation intact.
                        throw e
                    } catch (e: Exception) {
                        logger.error("❌ Payment failed: ${e.message}")
                        // Cancel remaining operations
                        throw e
                    }

                    // Step 4: Save to Database
                    vizLaunch("Database.saveOrder") {
                        logger.debug("💾 Persisting order to database...")
                        vizDelay((1500..2500).random().toLong())
                        logger.debug("✅ Order saved to database")
                    }.join()

                    // Step 5: Send Notifications (parallel - all can run simultaneously)
                    logger.debug("📨 Sending notifications in parallel...")
                    val notificationJobs =
                        listOf(
                            vizLaunch("EmailService.sendConfirmation") {
                                logger.debug("📧 Sending confirmation email...")
                                vizDelay((2000..3000).random().toLong())
                                logger.debug("✅ Email sent")
                            },
                            vizLaunch("SmsService.sendSms") {
                                logger.debug("📱 Sending SMS notification...")
                                vizDelay((1500..2500).random().toLong())
                                logger.debug("✅ SMS sent")
                            },
                            vizLaunch("AnalyticsService.trackPurchase") {
                                logger.debug("📊 Tracking purchase event...")
                                vizDelay((1000..1500).random().toLong())
                                logger.debug("✅ Analytics tracked")
                            },
                        )

                    // Wait for all notifications
                    notificationJobs.forEach { it.join() }

                    logger.debug("🎉 Order processing completed successfully!")
                }

            logger.info("Order processing scenario launched...")
            job.join()
            job
        }

    /**
     * User Registration Flow Scenario
     *
     * Simulates a complete user registration with:
     * - Input validation
     * - Database checks and writes
     * - Parallel profile and settings creation
     * - Welcome notifications
     * - Retry logic for email service
     */
    suspend fun runUserRegistrationScenario(
        session: VizSession,
        shouldFailEmail: Boolean = false,
    ): Job =
        coroutineScope {
            logger.info("Starting User Registration scenario in session: ${session.sessionId}")

            val viz = session.demoScope()

            val job =
                viz.vizLaunch("UserService.register") {
                    logger.debug("👤 Starting user registration...")

                    // Step 1: Validate Input
                    vizLaunch("InputValidator.validateUserData") {
                        logger.debug("Validating user input data...")
                        vizDelay((1500..2000).random().toLong())
                        logger.debug("✅ Input validation passed")
                    }.join()

                    // Step 2: Check if user already exists
                    vizLaunch("UserRepository.checkExists") {
                        logger.debug("🔍 Checking if user already exists...")
                        vizDelay((2000..2500).random().toLong())
                        logger.debug("✅ User does not exist, can proceed")
                    }.join()

                    // Step 3: Create user in database
                    vizLaunch("UserRepository.createUser") {
                        logger.debug("💾 Creating user in database...")
                        vizDelay((2500..3500).random().toLong())
                        logger.debug("✅ User created successfully")
                    }.join()

                    // Step 4: Parallel setup (profile + settings + avatar)
                    logger.debug("⚙️ Setting up user profile and settings in parallel...")
                    val setupJobs =
                        listOf(
                            vizLaunch("ProfileService.createProfile") {
                                logger.debug("Creating user profile...")
                                vizDelay((2000..2500).random().toLong())
                                logger.debug("✅ Profile created")
                            },
                            vizLaunch("SettingsService.createDefaults") {
                                logger.debug("Creating default settings...")
                                vizDelay((1500..2000).random().toLong())
                                logger.debug("✅ Default settings created")
                            },
                            vizLaunch("AvatarService.generateDefault") {
                                logger.debug("🎨 Generating default avatar...")
                                vizDelay((1000..1500).random().toLong())
                                logger.debug("✅ Default avatar generated")
                            },
                        )
                    setupJobs.forEach { it.join() }

                    // Step 5: Send notifications (parallel)
                    logger.debug("📨 Sending welcome notifications...")
                    val notificationJobs =
                        listOf(
                            vizLaunch("EmailService.sendWelcome") {
                                logger.debug("📧 Sending welcome email...")

                                if (shouldFailEmail) {
                                    // Simulate retry logic
                                    vizLaunch("EmailService.retry-1") {
                                        logger.debug("First attempt failed, retrying...")
                                        vizDelay(1500)
                                        throw demoFailure("SMTP server timeout")
                                    }.join()
                                }

                                vizDelay((2500..3500).random().toLong())
                                logger.debug("✅ Welcome email sent")
                            },
                            vizLaunch("SlackService.notifyTeam") {
                                logger.debug("💬 Notifying team on Slack...")
                                vizDelay((1500..2000).random().toLong())
                                logger.debug("✅ Team notified")
                            },
                            vizLaunch("AnalyticsService.trackSignup") {
                                logger.debug("📊 Tracking signup event...")
                                vizDelay((1000..1500).random().toLong())
                                logger.debug("✅ Signup tracked")
                            },
                        )

                    try {
                        notificationJobs.forEach { it.join() }
                    } catch (e: CancellationException) {
                        // Never swallow cancellation — rethrow to keep cooperative
                        // cancellation (and structured concurrency) intact.
                        throw e
                    } catch (e: Exception) {
                        logger.warn("⚠️ Some notifications failed: ${e.message}")
                        // Don't fail registration if notifications fail
                    }

                    logger.debug("🎉 User registration completed successfully!")
                }

            logger.info("User registration scenario launched...")
            job.join()
            job
        }

    /**
     * Report Generation Pipeline Scenario
     *
     * Simulates a data pipeline that:
     * - Fetches data from multiple sources in parallel
     * - Aggregates and transforms data
     * - Generates PDF report
     * - Uploads and sends via email
     * - Includes timeout handling
     */
    suspend fun runReportGenerationScenario(
        session: VizSession,
        shouldTimeout: Boolean = false,
    ): Job =
        coroutineScope {
            logger.info("Starting Report Generation scenario in session: ${session.sessionId}")

            val viz = session.demoScope()

            val job =
                viz.vizLaunch("ReportService.generateMonthlyReport") {
                    logger.debug("📊 Starting monthly report generation...")

                    // Step 1: Fetch data from multiple sources in parallel
                    logger.debug("📥 Fetching data from multiple sources...")
                    val dataFetchJobs =
                        listOf(
                            vizLaunch("UserApi.fetchActiveUsers") {
                                logger.debug("Fetching active users data...")
                                vizDelay((3000..4000).random().toLong())
                                logger.debug("✅ Users data fetched (1,234 records)")
                            },
                            vizLaunch("TransactionApi.fetchTransactions") {
                                logger.debug("Fetching transaction data...")
                                vizDelay((4000..5000).random().toLong())
                                logger.debug("✅ Transactions fetched (5,678 records)")
                            },
                            vizLaunch("AnalyticsApi.fetchMetrics") {
                                logger.debug("Fetching analytics metrics...")

                                if (shouldTimeout) {
                                    // Simulate timeout
                                    vizDelay(8000)
                                    throw demoFailure("Analytics API timeout after 8s")
                                }

                                vizDelay((2500..3500).random().toLong())
                                logger.debug("✅ Analytics metrics fetched")
                            },
                            vizLaunch("InventoryApi.fetchStockLevels") {
                                logger.debug("Fetching current stock levels...")
                                vizDelay((2000..3000).random().toLong())
                                logger.debug("✅ Stock levels fetched")
                            },
                        )

                    try {
                        dataFetchJobs.forEach { it.join() }
                        logger.debug("✅ All data sources fetched successfully")
                    } catch (e: CancellationException) {
                        // A timed-out fetch cancels this coroutine's join: the demo's failure, not
                        // an error. Rethrow to keep cooperative cancellation intact.
                        throw e
                    } catch (e: Exception) {
                        logger.error("❌ Data fetch failed: ${e.message}")
                        throw e
                    }

                    // Step 2: Aggregate and transform data
                    vizLaunch("DataProcessor.aggregateData") {
                        logger.debug("🔄 Aggregating data from all sources...")

                        vizLaunch("DataProcessor.calculateRevenue") {
                            logger.debug("Calculating revenue metrics...")
                            vizDelay((1500..2000).random().toLong())
                            logger.debug("✅ Revenue calculated")
                        }

                        vizLaunch("DataProcessor.calculateGrowth") {
                            logger.debug("Calculating growth metrics...")
                            vizDelay((1500..2000).random().toLong())
                            logger.debug("✅ Growth metrics calculated")
                        }

                        vizLaunch("DataProcessor.generateCharts") {
                            logger.debug("📈 Generating chart data...")
                            vizDelay((2000..2500).random().toLong())
                            logger.debug("✅ Charts generated")
                        }
                    }.join()

                    // Step 3: Generate PDF report
                    vizLaunch("PdfGenerator.createReport") {
                        logger.debug("📄 Generating PDF report...")

                        vizLaunch("PdfGenerator.renderHeader") {
                            logger.debug("Rendering report header...")
                            vizDelay((1000..1500).random().toLong())
                            logger.debug("✅ Header rendered")
                        }.join()

                        vizLaunch("PdfGenerator.renderCharts") {
                            logger.debug("Rendering charts section...")
                            vizDelay((2000..3000).random().toLong())
                            logger.debug("✅ Charts rendered")
                        }.join()

                        vizLaunch("PdfGenerator.renderTables") {
                            logger.debug("Rendering data tables...")
                            vizDelay((1500..2500).random().toLong())
                            logger.debug("✅ Tables rendered")
                        }.join()

                        vizLaunch("PdfGenerator.finalize") {
                            logger.debug("Finalizing PDF document...")
                            vizDelay((1000..1500).random().toLong())
                            logger.debug("✅ PDF finalized (2.4 MB)")
                        }.join()
                    }.join()

                    // Step 4: Delivery (parallel upload and email)
                    logger.debug("📤 Delivering report...")
                    val deliveryJobs =
                        listOf(
                            vizLaunch("S3Service.uploadReport") {
                                logger.debug("☁️ Uploading to S3...")
                                vizDelay((2500..3500).random().toLong())
                                logger.debug("✅ Report uploaded to S3")
                            },
                            vizLaunch("EmailService.sendReportEmail") {
                                logger.debug("📧 Sending report via email...")
                                vizDelay((2000..3000).random().toLong())
                                logger.debug("✅ Report emailed to stakeholders")
                            },
                            vizLaunch("SlackService.notifyChannel") {
                                logger.debug("💬 Notifying Slack channel...")
                                vizDelay((1000..1500).random().toLong())
                                logger.debug("✅ Slack notification sent")
                            },
                        )
                    deliveryJobs.forEach { it.join() }

                    logger.debug("🎉 Monthly report generation completed!")
                }

            logger.info("Report generation scenario launched...")
            job.join()
            job
        }
}
