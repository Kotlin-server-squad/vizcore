package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizDispatchers
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Basics demos: one coroutine idea each, told as an everyday app. Their steps are paced with
 * [pause] so a person can watch them: 0.5-3 s a step, 10-30 s a demo, at the configured
 * `scenarios.timeScale`. Each returns the demo's root coroutine, which keeps running after the
 * call returns.
 */
object BasicScenarios {
    private val logger = LoggerFactory.getLogger(BasicScenarios::class.java)

    /** The folders the Deep Nesting demo indexes, from the top: /home/ana/projects/vizcore/backend/src. */
    private val INDEXED_FOLDERS = listOf("index-home", "index-ana", "index-projects", "index-vizcore", "index-backend", "index-src")

    /**
     * Nested coroutines, as a product page: it loads the product, then three sections at once,
     * each with requests of its own, three levels deep. The page's own code ends after 2.5 s, but
     * its coroutine completes only with the last request below it, at about 16 s: a parent waits
     * for its children (structured concurrency).
     */
    suspend fun runNestedCoroutines(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting nested coroutines scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            viz.vizLaunch("load-product-page") {
                // The sections need the product, so it comes first.
                vizLaunch("fetch-product") {
                    pause(1500)
                    pause(1000)
                }.join()

                vizLaunch("load-price-box") {
                    vizLaunch("fetch-price") { pause(2000) }
                    vizLaunch("fetch-delivery-date") {
                        pause(1500)
                        pause(1500)
                    }
                }
                vizLaunch("load-reviews") {
                    vizLaunch("fetch-reviews") {
                        pause(2000)
                        pause(1500)
                        vizLaunch("fetch-review-photos") {
                            pause(2500)
                            pause(2000)
                        }
                    }
                }
                vizLaunch("load-recommendations") {
                    pause(1500)
                    vizLaunch("fetch-similar-products") {
                        pause(3000)
                        pause(2000)
                        vizLaunch("fetch-thumbnails") {
                            pause(2500)
                            pause(2500)
                            pause(2000)
                        }
                    }
                }
                logger.debug("Product page code done; its sections are still loading")
            }
        }

    /**
     * Parallel execution, as a dashboard: five widgets load at the same time and finish one by
     * one, 1.5 to 11 s in; the dashboard renders once the slowest is in. 31 s of requests take
     * about 14 s.
     */
    suspend fun runParallelExecution(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting parallel execution scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            viz.vizLaunch("load-dashboard") {
                listOf(
                    vizLaunch("fetch-sales") {
                        pause(3000)
                        pause(3000)
                        pause(2000)
                    },
                    vizLaunch("fetch-orders") {
                        pause(2000)
                        pause(2500)
                    },
                    vizLaunch("fetch-visitors") {
                        pause(3000)
                        pause(3000)
                        pause(3000)
                        pause(2000)
                    },
                    vizLaunch("fetch-alerts") { pause(1500) },
                    vizLaunch("fetch-top-products") {
                        pause(2500)
                        pause(2000)
                        pause(1500)
                    },
                ).joinAll()
                logger.debug("All widgets loaded")

                vizLaunch("render-dashboard") {
                    pause(1500)
                    pause(1000)
                }
            }
        }

    /**
     * Dispatcher switching, as thumbnails: three photos arrive 2 s apart, and each is downloaded
     * on Dispatchers.IO, resized on Dispatchers.Default and uploaded on IO again, while the next
     * one is already on its way. Runs through [VizDispatchers], so DispatcherSelected and
     * ThreadAssigned events fill the Dispatcher Overview. Unlike the
     * `/api/examples/dispatcher-scenario` demo (which uses a throwaway session), this runs against
     * the managed [session] so the result is a navigable, persisted session (F11).
     */
    suspend fun runDispatcherScenario(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting dispatcher scenario in session: ${session.sessionId}")

            val dispatchers = VizDispatchers(session, scopeId = "dispatcher")
            val viz = session.demoScope(dispatchers.default)

            viz.vizLaunch("make-thumbnails") {
                for (photo in 1..3) {
                    if (photo > 1) pause(2000)
                    vizLaunch("process-photo-$photo") {
                        vizLaunch("download-photo-$photo", context = dispatchers.io) {
                            pause(2000)
                            pause(1000)
                        }.join()
                        // Resizing is CPU work, so it runs on Default.
                        vizLaunch("resize-photo-$photo", context = dispatchers.default) {
                            pause(2000)
                            pause(2000)
                        }.join()
                        vizLaunch("upload-thumbnail-$photo", context = dispatchers.io) { pause(1500) }.join()
                    }
                }
            }
        }

    /**
     * Cancellation, as a user closing a screen: the profile screen loads the avatar, the friends
     * and the activity feed, and settings sync in the background, outside the screen. After 6.5 s
     * the user closes the screen, which cancels it and every request still running under it; the
     * avatar is already loaded, and settings sync carries on to the end (about 12 s).
     */
    suspend fun runCancellationScenario(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting cancellation scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            viz.vizLaunch("app-session") {
                val screen =
                    vizLaunch("profile-screen") {
                        vizLaunch("fetch-avatar") { pause(2000) }
                        vizLaunch("fetch-friends") {
                            pause(3000)
                            pause(3000)
                            pause(3000)
                        }
                        vizLaunch("fetch-activity-feed") {
                            pause(2500)
                            pause(2500)
                            pause(2500)
                            pause(2500)
                        }
                    }
                val sync =
                    vizLaunch("sync-settings") {
                        pause(2500)
                        pause(2500)
                        pause(2500)
                        pause(2500)
                        pause(2000)
                    }

                // The user looks at the screen for a while, then closes it. Only the screen is
                // cancelled (FIX-04): the app session and settings sync go on.
                pause(2500)
                pause(2000)
                pause(2000)
                logger.debug("The user closes the profile screen")
                screen.cancel()

                sync.join()
            }
        }

    /**
     * Deep nesting, as a search indexer walking folders: each folder lists its contents, starts
     * indexing its subfolder one level deeper, indexes its own files meanwhile, and adds the
     * subfolder's results once those are in, so the folders complete one by one from the deepest
     * up. [depth] subfolders below the first, 5 by default (about 16 s).
     */
    suspend fun runDeepNesting(
        session: VizSession,
        depth: Int = 5,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting deep nesting scenario (depth=$depth) in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            suspend fun VizScope.indexFolder(level: Int): Job =
                vizLaunch(INDEXED_FOLDERS.getOrElse(level) { "index-folder-$level" }) {
                    // List the folder.
                    pause(1000)
                    if (level < depth) {
                        val subfolder = indexFolder(level + 1)
                        // Index this folder's own files while the subfolder is walked.
                        pause(1500)
                        subfolder.join()
                        // Add the subfolder's results to this folder's index.
                        pause(800)
                    } else {
                        // The deepest folder holds most of the files.
                        pause(2000)
                        pause(2000)
                        pause(2000)
                    }
                    logger.debug("Folder at level $level indexed")
                }

            viz.indexFolder(0)
        }

    /**
     * Sequential and parallel steps mixed, as a checkout: validate the cart first; then reserve
     * the stock, charge the card and calculate shipping at once; then create the order, then send
     * the receipt (about 14 s).
     */
    suspend fun runMixedScenario(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting mixed scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            viz.vizLaunch("checkout") {
                vizLaunch("validate-cart") {
                    pause(1500)
                    pause(1000)
                }.join()

                listOf(
                    vizLaunch("reserve-stock") {
                        pause(3000)
                        pause(1000)
                    },
                    vizLaunch("charge-card") {
                        pause(2000)
                        pause(2500)
                        pause(1500)
                    },
                    vizLaunch("calculate-shipping") { pause(2000) },
                ).joinAll()

                vizLaunch("create-order") { pause(2000) }.join()
                vizLaunch("send-receipt") {
                    pause(1500)
                    pause(1500)
                }.join()
                logger.debug("Checkout completed")
            }
        }

    /**
     * Exception handling, as one backend call failing: the account page loads five things at
     * once. The delivery status and the profile arrive; then, 12 s in, the loyalty service
     * answers HTTP 500. The failure cancels the orders and invoices still loading and fails the
     * page; what already arrived stays done.
     */
    suspend fun runExceptionScenario(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting exception scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            viz.vizLaunch("load-account-page") {
                vizLaunch("fetch-delivery-status") {
                    pause(2000)
                    pause(1500)
                }
                vizLaunch("fetch-profile") {
                    pause(2000)
                    pause(2000)
                    pause(2000)
                }
                vizLaunch("fetch-orders") {
                    repeat(5) { pause(3000) }
                }
                vizLaunch("fetch-invoices") {
                    repeat(6) { pause(2500) }
                }
                vizLaunch("fetch-loyalty-points") {
                    repeat(4) { pause(3000) }
                    throw demoFailure("Loyalty service answered HTTP 500")
                }
            }
        }
}
