package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/**
 * The Channels demos: how coroutines hand work to each other through a channel, told as everyday
 * work. Like the Basics ([BasicScenarios]) their steps are paced with [pause] so a person can
 * watch them: 0.5-3 s a step, 10-30 s a demo, at the configured `scenarios.timeScale`. Each returns
 * the demo's root coroutine, which keeps running after the call returns.
 *
 * A coroutine waiting in `send` or `receive` shows as active in the session tree; the Channels
 * panel shows the waiting operation and how full the channel is.
 */
object ChannelScenarios {
    private val logger = LoggerFactory.getLogger(ChannelScenarios::class.java)

    /** The dishes of the Rendezvous demo, in the order the chef cooks them. */
    private val PLATES = listOf("soup", "salad", "steak", "pasta", "dessert")

    /** How long each ticket of the Fan-Out demo takes to handle, by ticket number (1-based). */
    private val TICKET_HANDLING_MILLIS = listOf(3000L, 2000, 2500, 1500, 3000, 2000, 1000, 2500, 3000, 1500, 2000, 2500)

    /**
     * A rendezvous channel, as a restaurant pass: the chef cooks five plates and the waiter carries
     * them out, and a plate changes hands only when both are at the pass. The waiter is there first
     * and waits for the soup (2 s); after that the chef is always ready 1.5 s before the waiter is
     * back, so the chef waits at the pass with each plate. Plates change hands at 2, 5, 8, 11 and
     * 14 s; the chef closes the pass after the last one, and the waiter, back at 17 s, finds it
     * closed and is done.
     */
    suspend fun runRendezvous(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting channel rendezvous scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val pass = viz.vizChannel<String>(Channel.RENDEZVOUS, "pass")

            viz.vizLaunch("dinner-service") {
                vizLaunch("chef") {
                    PLATES.forEachIndexed { i, plate ->
                        pause(if (i == 0) 2000 else 1500) // cook
                        pass.send(plate) // waits at the pass until the waiter takes it
                    }
                    pass.close()
                    logger.debug("Chef: all plates handed over")
                }
                vizLaunch("waiter") {
                    while (true) {
                        val plate = pass.receiveCatching().getOrNull() ?: break // waits at the pass for a plate
                        logger.debug("Waiter carries $plate out")
                        pause(1500) // to the table
                        pause(1500) // and back to the pass
                    }
                    logger.debug("Waiter: the pass is closed")
                }
            }
        }

    /**
     * A buffered channel, as a print queue with 5 slots: the office sends 8 jobs, one every 0.6 s,
     * while the printer warms up for 3 s and then prints a job every 2.5 s. Jobs 1-4 queue up during
     * the warm-up, the queue is full at 3.6 s, and the office has to wait for a free slot with job 7
     * (from 4.2 s until 5.5 s) and job 8 (from 6.1 s until 8 s). Then it closes the queue and is
     * done, and the printer drains the queue until about 23 s.
     */
    suspend fun runBuffered(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting channel buffered scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val queue = viz.vizChannel<String>(PRINT_QUEUE_SLOTS, "print-queue")

            viz.vizLaunch("office-printing") {
                vizLaunch("office") {
                    for (job in 1..PRINT_JOBS) {
                        pause(600) // the next document is ready
                        queue.send("job-$job") // waits only while all slots are taken
                    }
                    queue.close()
                    logger.debug("Office: all jobs sent")
                }
                vizLaunch("printer") {
                    pause(1500) // warming up
                    pause(1500)
                    while (true) {
                        val job = queue.receiveCatching().getOrNull() ?: break
                        logger.debug("Printer prints $job")
                        pause(2500)
                    }
                    logger.debug("Printer: the queue is closed and empty")
                }
            }
        }

    /**
     * Fan-out, as a support desk: 12 tickets arrive into a queue that three agents read. Each ticket
     * goes to exactly one free agent, who handles it for 1-3 s. The morning rush brings six tickets
     * 0.5 s apart: the first three go straight to the agents, and tickets 4-6 wait in the queue
     * until an agent is free (about 1 s each). After that a ticket arrives every 1.2 s. The inbox
     * closes the queue after the last ticket, and each agent is done once the queue is empty, at
     * about 13 s.
     */
    suspend fun runFanOut(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting channel fan-out scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val queue = viz.vizChannel<String>(TICKET_QUEUE_SLOTS, "ticket-queue")

            viz.vizLaunch("support-desk") {
                vizLaunch("ticket-inbox") {
                    for (ticket in 1..TICKET_HANDLING_MILLIS.size) {
                        pause(if (ticket <= RUSH_TICKETS) 500 else 1200) // the next ticket arrives
                        queue.send("ticket-$ticket")
                    }
                    queue.close()
                    logger.debug("Inbox: all tickets queued")
                }
                for (agent in 1..AGENTS) {
                    vizLaunch("agent-$agent") {
                        while (true) {
                            val ticket = queue.receiveCatching().getOrNull() ?: break // waits for a ticket
                            pause(TICKET_HANDLING_MILLIS[ticket.removePrefix("ticket-").toInt() - 1])
                        }
                    }
                }
            }
        }

    private const val PRINT_QUEUE_SLOTS = 5
    private const val PRINT_JOBS = 8
    private const val TICKET_QUEUE_SLOTS = 3
    private const val AGENTS = 3
    private const val RUSH_TICKETS = 6
}
