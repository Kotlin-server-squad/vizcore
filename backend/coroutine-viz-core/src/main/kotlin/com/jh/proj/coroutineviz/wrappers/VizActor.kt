package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.ActorClosed
import com.jh.proj.coroutineviz.events.ActorCreated
import com.jh.proj.coroutineviz.events.ActorMailboxChanged
import com.jh.proj.coroutineviz.events.ActorMessageProcessed
import com.jh.proj.coroutineviz.events.ActorMessageProcessing
import com.jh.proj.coroutineviz.events.ActorMessageSent
import com.jh.proj.coroutineviz.events.ActorStateChanged
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.debugprobes.VizcoreInternal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Instrumented actor implementation that emits visualization events.
 *
 * Implements the actor pattern using a coroutine + channel combination, with full
 * event tracking for the visualization system. The actor:
 * - Receives messages through a [Channel]-backed mailbox
 * - Processes them sequentially in a dedicated coroutine
 * - Emits events for creation, message send/processing, state changes, and closure
 *
 * Lifecycle events:
 * 1. [ActorCreated] — when the actor is constructed and its processing loop starts
 * 2. [ActorMessageSent] — when a message is enqueued to the mailbox
 * 3. [ActorMessageProcessing] — when a message is dequeued and begins processing
 * 4. [ActorMessageProcessed] — when message processing completes (success or failure)
 * 5. [ActorMailboxChanged] — when the mailbox size changes
 * 6. [ActorStateChanged] — when the actor's state is explicitly updated
 * 7. [ActorClosed] — when the actor is shut down
 *
 * Usage via VizScope extension:
 * ```kotlin
 * val actor = scope.vizActor<String>(name = "printer") { message ->
 *     println("Received: $message")
 * }
 * actor.send("Hello")
 * actor.send("World")
 * actor.close()
 * ```
 *
 * @param M The message type accepted by this actor
 * @param session The visualization session for emitting events
 * @param scope The coroutine scope in which the actor's processing loop runs
 * @param name Optional human-readable name for the actor
 * @param capacity Mailbox channel capacity (defaults to [Channel.BUFFERED])
 * @param handler The suspend function invoked for each received message
 */
class VizActor<M>(
    private val session: VizSession,
    private val scope: CoroutineScope,
    val name: String? = null,
    capacity: Int = Channel.BUFFERED,
    private val handler: suspend (M) -> Unit,
) {
    val actorId: String = "actor-${session.nextSeq()}"

    private val channel = Channel<TimestampedMessage<M>>(capacity)
    private val mailboxSize = AtomicInteger(0)
    private val messagesProcessed = AtomicLong(0L)
    private val resolvedCapacity = resolveCapacity(capacity)

    @Volatile
    private var closed = false

    private val job: Job

    /**
     * Internal wrapper that pairs a message with its enqueue timestamp,
     * allowing queue wait time to be measured.
     */
    internal data class TimestampedMessage<M>(
        val message: M,
        val enqueuedAtNanos: Long,
    )

    init {
        // Determine coroutine ID if running inside a viz context
        val coroutineId =
            runCatching {
                kotlinx.coroutines.runBlocking(VizcoreInternal) {
                    currentCoroutineContext()[VizCoroutineElement]?.coroutineId
                }
            }.getOrNull() ?: "actor-loop-$actorId"

        // Emit ActorCreated
        session.send(
            ActorCreated(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                actorId = actorId,
                coroutineId = coroutineId,
                name = name,
                mailboxCapacity = resolvedCapacity,
            ),
        )

        // Launch the processing loop
        job =
            scope.launch {
                try {
                    for (timestamped in channel) {
                        val msg = timestamped.message
                        val queueWaitNanos = System.nanoTime() - timestamped.enqueuedAtNanos
                        val currentSize = mailboxSize.decrementAndGet().coerceAtLeast(0)

                        val messageType = msg?.let { it::class.simpleName } ?: "null"
                        val messagePreview = msg?.toString()?.take(200) ?: "null"

                        // Emit mailbox changed (message dequeued)
                        session.send(
                            ActorMailboxChanged(
                                sessionId = session.sessionId,
                                seq = session.nextSeq(),
                                tsNanos = System.nanoTime(),
                                actorId = actorId,
                                currentSize = currentSize,
                                capacity = resolvedCapacity,
                                pendingSenders = 0,
                            ),
                        )

                        // Emit processing start
                        session.send(
                            ActorMessageProcessing(
                                sessionId = session.sessionId,
                                seq = session.nextSeq(),
                                tsNanos = System.nanoTime(),
                                actorId = actorId,
                                messageType = messageType,
                                messagePreview = messagePreview,
                                queueWaitNanos = queueWaitNanos,
                            ),
                        )

                        // Process the message
                        val processingStart = System.nanoTime()
                        var success = true
                        try {
                            handler(msg)
                        } catch (e: CancellationException) {
                            throw e // Don't swallow cancellation
                        } catch (e: Exception) {
                            success = false
                            logger.warn(
                                "Actor '$name' ($actorId) failed to process message: ${e.message}",
                                e,
                            )
                        }
                        val processingDuration = System.nanoTime() - processingStart

                        messagesProcessed.incrementAndGet()

                        // Emit processing completed
                        session.send(
                            ActorMessageProcessed(
                                sessionId = session.sessionId,
                                seq = session.nextSeq(),
                                tsNanos = System.nanoTime(),
                                actorId = actorId,
                                messageType = messageType,
                                processingDurationNanos = processingDuration,
                                success = success,
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    // Actor loop cancelled - normal shutdown
                    logger.debug("Actor '$name' ($actorId) processing loop cancelled")
                } finally {
                    if (!closed) {
                        emitClosed("Processing loop ended")
                    }
                }
            }

        logger.debug(
            "Actor created: actorId=$actorId, name=$name, capacity=$resolvedCapacity",
        )
    }

    /**
     * Send a message to the actor's mailbox.
     *
     * Suspends if the mailbox is full (depending on capacity). Emits [ActorMessageSent]
     * and [ActorMailboxChanged] events.
     *
     * @param message The message to send
     * @throws IllegalStateException if the actor has been closed
     */
    suspend fun send(message: M) {
        check(!closed) { "Actor '$name' ($actorId) is closed" }

        val senderElement = currentCoroutineContext()[VizCoroutineElement]
        val senderId = senderElement?.coroutineId ?: "unknown-${System.nanoTime()}"

        val messageType = message?.let { it::class.simpleName } ?: "null"
        val messagePreview = message?.toString()?.take(200) ?: "null"

        // Enqueue the message
        val timestamped = TimestampedMessage(message, System.nanoTime())
        channel.send(timestamped)

        val currentSize = mailboxSize.incrementAndGet()

        // Emit message sent
        session.send(
            ActorMessageSent(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                actorId = actorId,
                senderId = senderId,
                messageType = messageType,
                messagePreview = messagePreview,
                mailboxSize = currentSize,
            ),
        )

        // Emit mailbox changed (message enqueued)
        session.send(
            ActorMailboxChanged(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                actorId = actorId,
                currentSize = currentSize,
                capacity = resolvedCapacity,
                pendingSenders = 0,
            ),
        )

        logger.trace("Message sent to actor '$name' ($actorId): $messagePreview")
    }

    /**
     * Try to send a message without suspending.
     *
     * @param message The message to send
     * @return true if the message was enqueued, false if the mailbox is full
     */
    fun trySend(message: M): Boolean {
        if (closed) return false

        val timestamped = TimestampedMessage(message, System.nanoTime())
        val result = channel.trySend(timestamped)

        if (result.isSuccess) {
            val currentSize = mailboxSize.incrementAndGet()
            val messageType = message?.let { it::class.simpleName } ?: "null"
            val messagePreview = message?.toString()?.take(200) ?: "null"

            session.send(
                ActorMessageSent(
                    sessionId = session.sessionId,
                    seq = session.nextSeq(),
                    tsNanos = System.nanoTime(),
                    actorId = actorId,
                    senderId = "trySend",
                    messageType = messageType,
                    messagePreview = messagePreview,
                    mailboxSize = currentSize,
                ),
            )

            session.send(
                ActorMailboxChanged(
                    sessionId = session.sessionId,
                    seq = session.nextSeq(),
                    tsNanos = System.nanoTime(),
                    actorId = actorId,
                    currentSize = currentSize,
                    capacity = resolvedCapacity,
                    pendingSenders = 0,
                ),
            )
        }

        return result.isSuccess
    }

    /**
     * Report a state change in the actor.
     *
     * Call this from within the handler to track internal state transitions.
     *
     * @param oldState Preview of the previous state
     * @param newState Preview of the new state
     * @param stateType Type/class name of the state object
     */
    fun reportStateChange(
        oldState: String,
        newState: String,
        stateType: String,
    ) {
        session.send(
            ActorStateChanged(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                actorId = actorId,
                oldStatePreview = oldState,
                newStatePreview = newState,
                stateType = stateType,
            ),
        )
    }

    /**
     * Close the actor and stop processing messages.
     *
     * Any messages already in the mailbox will be processed before the actor stops.
     * Emits an [ActorClosed] event.
     *
     * @param reason Optional reason for closing the actor
     */
    fun close(reason: String? = null) {
        if (closed) return
        closed = true
        channel.close()
        emitClosed(reason)
        logger.debug("Actor '$name' ($actorId) closed: reason=$reason")
    }

    /**
     * Cancel the actor immediately, discarding any pending messages.
     *
     * @param reason Optional reason for cancellation
     */
    fun cancel(reason: String? = null) {
        if (closed) return
        closed = true
        channel.cancel(CancellationException(reason ?: "Actor cancelled"))
        job.cancel(CancellationException(reason ?: "Actor cancelled"))
        emitClosed(reason ?: "Cancelled")
        logger.debug("Actor '$name' ($actorId) cancelled: reason=$reason")
    }

    /**
     * Whether this actor has been closed or cancelled.
     */
    val isClosed: Boolean get() = closed

    /**
     * The total number of messages processed by this actor.
     */
    val totalMessagesProcessed: Long get() = messagesProcessed.get()

    /**
     * The current mailbox size (approximate).
     */
    val currentMailboxSize: Int get() = mailboxSize.get().coerceAtLeast(0)

    /**
     * The Job backing this actor's processing loop.
     */
    val processingJob: Job get() = job

    private fun emitClosed(reason: String?) {
        session.send(
            ActorClosed(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                actorId = actorId,
                reason = reason,
                totalMessagesProcessed = messagesProcessed.get(),
            ),
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(VizActor::class.java)

        /**
         * Resolve the effective capacity for display purposes.
         * Channel constants (BUFFERED, RENDEZVOUS, etc.) are negative sentinel values.
         */
        private fun resolveCapacity(capacity: Int): Int =
            when (capacity) {
                Channel.BUFFERED -> 64 // Default buffered capacity in kotlinx.coroutines
                Channel.RENDEZVOUS -> 0
                Channel.CONFLATED -> 1
                Channel.UNLIMITED -> Int.MAX_VALUE
                else -> capacity
            }
    }
}

// ============================================================================
// Extension function for creating VizActor in VizScope
// ============================================================================

/**
 * Create a new instrumented actor within this VizScope.
 *
 * The actor runs a dedicated coroutine that sequentially processes messages
 * received through its mailbox channel, emitting visualization events
 * for each lifecycle step.
 *
 * Example:
 * ```kotlin
 * val counter = scope.vizActor<Int>(name = "counter", capacity = Channel.BUFFERED) { delta ->
 *     total += delta
 *     reportStateChange("${total - delta}", "$total", "Int")
 * }
 *
 * counter.send(1)
 * counter.send(5)
 * counter.close()
 * ```
 *
 * @param M The message type accepted by the actor
 * @param name Optional human-readable name for the actor
 * @param capacity Mailbox channel capacity (defaults to [Channel.BUFFERED])
 * @param handler The suspend function invoked for each received message
 * @return A [VizActor] that can receive messages and be closed
 */
fun <M> VizScope.vizActor(
    name: String? = null,
    capacity: Int = Channel.BUFFERED,
    handler: suspend (M) -> Unit,
): VizActor<M> {
    return VizActor(
        session = session,
        scope = this,
        name = name,
        capacity = capacity,
        handler = handler,
    )
}
