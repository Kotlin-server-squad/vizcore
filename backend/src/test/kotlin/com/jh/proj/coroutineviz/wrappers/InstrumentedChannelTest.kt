package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.channel.*
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class InstrumentedChannelTest {
    private lateinit var session: VizSession

    @BeforeEach
    fun setup() {
        session = VizSession("test-channel-${System.currentTimeMillis()}")
    }

    // ========================================================================
    // CREATION TESTS
    // ========================================================================

    @Test
    @DisplayName("vizChannel emits ChannelCreated event on creation")
    fun `channel creation emits event`() {
        val scope = VizScope(session)
        val channel = scope.vizChannel<Int>(Channel.RENDEZVOUS, "test-channel")

        val createdEvents = session.store.all().filterIsInstance<ChannelCreated>()
        assertEquals(1, createdEvents.size)
        assertEquals("test-channel", createdEvents.first().name)
        assertEquals("RENDEZVOUS", createdEvents.first().channelType)
        assertEquals(Channel.RENDEZVOUS, createdEvents.first().capacity)
    }

    @Test
    @DisplayName("vizChannel with buffered capacity reports BUFFERED type")
    fun `buffered channel type`() {
        val scope = VizScope(session)
        val channel = scope.vizChannel<String>(5, "buffered")

        val createdEvents = session.store.all().filterIsInstance<ChannelCreated>()
        assertEquals(1, createdEvents.size)
        assertEquals("BUFFERED", createdEvents.first().channelType)
        assertEquals(5, createdEvents.first().capacity)
    }

    @Test
    @DisplayName("vizChannel with CONFLATED capacity reports CONFLATED type")
    fun `conflated channel type`() {
        val scope = VizScope(session)
        val channel = scope.vizChannel<Int>(Channel.CONFLATED, "conflated")

        val createdEvents = session.store.all().filterIsInstance<ChannelCreated>()
        assertEquals("CONFLATED", createdEvents.first().channelType)
    }

    @Test
    @DisplayName("vizChannel with UNLIMITED capacity reports UNLIMITED type")
    fun `unlimited channel type`() {
        val scope = VizScope(session)
        val channel = scope.vizChannel<Int>(Channel.UNLIMITED, "unlimited")

        val createdEvents = session.store.all().filterIsInstance<ChannelCreated>()
        assertEquals("UNLIMITED", createdEvents.first().channelType)
    }

    // ========================================================================
    // SEND/RECEIVE TESTS
    // ========================================================================

    @Test
    @DisplayName("send and receive emit correct events in sequence")
    fun `send and receive events`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.UNLIMITED, "send-recv")

            channel.send(42)
            val value = channel.receive()

            assertEquals(42, value)

            val allEvents = session.store.all()
            assertTrue(allEvents.any { it is ChannelSendStarted })
            assertTrue(allEvents.any { it is ChannelSendCompleted })
            assertTrue(allEvents.any { it is ChannelReceiveStarted })
            assertTrue(allEvents.any { it is ChannelReceiveCompleted })

            val sendCompleted = allEvents.filterIsInstance<ChannelSendCompleted>().first()
            assertEquals("42", sendCompleted.valueDescription)

            val receiveCompleted = allEvents.filterIsInstance<ChannelReceiveCompleted>().first()
            assertEquals("42", receiveCompleted.valueDescription)
        }

    @Test
    @DisplayName("buffer state changes tracked correctly")
    fun `buffer state tracking`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.UNLIMITED, "buffer-track")

            channel.send(1)
            channel.send(2)
            channel.send(3)

            val bufferEvents = session.store.all().filterIsInstance<ChannelBufferStateChanged>()
            // After each send, a buffer state event is emitted
            assertTrue(bufferEvents.size >= 3, "Expected at least 3 buffer state events, got ${bufferEvents.size}")

            channel.receive()
            channel.receive()

            val allBufferEvents = session.store.all().filterIsInstance<ChannelBufferStateChanged>()
            // Additional events after receives
            assertTrue(allBufferEvents.size >= 5, "Expected at least 5 buffer state events, got ${allBufferEvents.size}")
        }

    // ========================================================================
    // CLOSE TESTS
    // ========================================================================

    @Test
    @DisplayName("close emits ChannelClosed event")
    fun `channel close event`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.UNLIMITED, "close-test")

            channel.send(1)
            channel.close()

            val closedEvents = session.store.all().filterIsInstance<ChannelClosed>()
            assertEquals(1, closedEvents.size)
            assertNull(closedEvents.first().cause)
        }

    @Test
    @DisplayName("close with exception includes cause in event")
    fun `channel close with cause`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.UNLIMITED, "close-cause")

            channel.close(IllegalStateException("test error"))

            val closedEvents = session.store.all().filterIsInstance<ChannelClosed>()
            assertEquals(1, closedEvents.size)
            assertEquals("test error", closedEvents.first().cause)
        }

    // ========================================================================
    // TRYSEND / TRYRECEIVE TESTS
    // ========================================================================

    @Test
    @DisplayName("trySend emits events on success")
    fun `trySend success events`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.UNLIMITED, "try-send")

            val result = channel.trySend(99)
            assertTrue(result.isSuccess)

            val sendCompleted = session.store.all().filterIsInstance<ChannelSendCompleted>()
            assertTrue(sendCompleted.isNotEmpty())
            assertEquals("99", sendCompleted.first().valueDescription)
        }

    @Test
    @DisplayName("tryReceive emits events on success")
    fun `tryReceive success events`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.UNLIMITED, "try-recv")

            channel.send(77)
            val result = channel.tryReceive()
            assertTrue(result.isSuccess)
            assertEquals(77, result.getOrNull())

            val receiveCompleted = session.store.all().filterIsInstance<ChannelReceiveCompleted>()
            assertTrue(receiveCompleted.isNotEmpty())
        }

    // ========================================================================
    // RENDEZVOUS BEHAVIOR TESTS
    // ========================================================================

    @Test
    @DisplayName("rendezvous channel suspends sender until receiver is ready")
    fun `rendezvous suspension behavior`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.RENDEZVOUS, "rendezvous")

            var senderCompleted = false

            val sender =
                launch {
                    channel.send(1)
                    senderCompleted = true
                }

            // Give sender time to start but it should be suspended
            delay(50)
            assertFalse(senderCompleted, "Sender should be suspended in rendezvous channel")

            // Receive unblocks the sender
            val value = channel.receive()
            assertEquals(1, value)

            sender.join()
            assertTrue(senderCompleted, "Sender should complete after receive")
        }

    // ========================================================================
    // MULTIPLE SENDER/RECEIVER TESTS
    // ========================================================================

    @Test
    @DisplayName("fan-out: multiple receivers compete for channel values")
    fun `fan-out pattern`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<Int>(Channel.BUFFERED, "fan-out")
            val received =
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet<Int>()

            // Launch receivers
            val receivers =
                (1..3).map {
                    launch {
                        try {
                            while (true) {
                                val value = channel.receive()
                                received.add(value)
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            // expected
                        }
                    }
                }

            // Send values
            for (i in 1..10) {
                channel.send(i)
            }
            channel.close()

            receivers.forEach { it.join() }

            // All values should have been received exactly once
            assertEquals((1..10).toSet(), received)
        }

    // ========================================================================
    // EVENT ORDERING TESTS
    // ========================================================================

    @Test
    @DisplayName("events are emitted in correct order: created -> send -> receive -> close")
    fun `event ordering`() =
        runBlocking {
            val scope = VizScope(session)
            val channel = scope.vizChannel<String>(Channel.UNLIMITED, "ordering")

            channel.send("hello")
            channel.receive()
            channel.close()

            val allEvents = session.store.all()
            val channelEvents =
                allEvents.filter { event ->
                    event is ChannelCreated ||
                        event is ChannelSendStarted ||
                        event is ChannelSendCompleted ||
                        event is ChannelReceiveStarted ||
                        event is ChannelReceiveCompleted ||
                        event is ChannelClosed ||
                        event is ChannelBufferStateChanged
                }

            // First event should be ChannelCreated
            assertTrue(channelEvents.first() is ChannelCreated, "First channel event should be ChannelCreated")

            // Last event should be ChannelClosed
            assertTrue(channelEvents.last() is ChannelClosed, "Last channel event should be ChannelClosed")

            // Verify sequence numbers are monotonically increasing
            val seqs = channelEvents.map { it.seq }
            for (i in 1 until seqs.size) {
                assertTrue(seqs[i] > seqs[i - 1], "Sequence numbers should be increasing: ${seqs[i - 1]} -> ${seqs[i]}")
            }
        }

    // ========================================================================
    // SERIALIZATION TESTS
    // ========================================================================

    @Test
    @DisplayName("ChannelCreated serialization round-trip")
    fun `channelCreated serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelCreated(
                sessionId = "test-session",
                seq = 1L,
                tsNanos = 12345L,
                channelId = "channel-1",
                name = "test-channel",
                capacity = 5,
                channelType = "BUFFERED",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelCreated>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"channelId\":\"channel-1\""))
        assertTrue(serialized.contains("\"channelType\":\"BUFFERED\""))
        assertTrue(serialized.contains("\"capacity\":5"))
    }

    @Test
    @DisplayName("ChannelSendCompleted serialization round-trip")
    fun `channelSendCompleted serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelSendCompleted(
                sessionId = "test-session",
                seq = 2L,
                tsNanos = 12345L,
                channelId = "channel-1",
                coroutineId = "coroutine-1",
                valueDescription = "42",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelSendCompleted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"valueDescription\":\"42\""))
    }

    @Test
    @DisplayName("ChannelReceiveCompleted serialization round-trip")
    fun `channelReceiveCompleted serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelReceiveCompleted(
                sessionId = "test-session",
                seq = 3L,
                tsNanos = 12345L,
                channelId = "channel-1",
                coroutineId = "coroutine-2",
                valueDescription = "hello",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelReceiveCompleted>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    @DisplayName("ChannelClosed serialization with null cause")
    fun `channelClosed serialization null cause`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelClosed(
                sessionId = "test-session",
                seq = 4L,
                tsNanos = 12345L,
                channelId = "channel-1",
                cause = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelClosed>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    @DisplayName("ChannelClosed serialization with cause")
    fun `channelClosed serialization with cause`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelClosed(
                sessionId = "test-session",
                seq = 5L,
                tsNanos = 12345L,
                channelId = "channel-1",
                cause = "Test error",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelClosed>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"cause\":\"Test error\""))
    }

    @Test
    @DisplayName("ChannelSendSuspended serialization round-trip")
    fun `channelSendSuspended serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelSendSuspended(
                sessionId = "test-session",
                seq = 6L,
                tsNanos = 12345L,
                channelId = "channel-1",
                coroutineId = "coroutine-1",
                bufferSize = 5,
                capacity = 5,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelSendSuspended>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"bufferSize\":5"))
    }

    @Test
    @DisplayName("ChannelBufferStateChanged serialization round-trip")
    fun `channelBufferStateChanged serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelBufferStateChanged(
                sessionId = "test-session",
                seq = 7L,
                tsNanos = 12345L,
                channelId = "channel-1",
                currentSize = 3,
                capacity = 10,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelBufferStateChanged>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"currentSize\":3"))
        assertTrue(serialized.contains("\"capacity\":10"))
    }

    @Test
    @DisplayName("ChannelReceiveSuspended serialization round-trip")
    fun `channelReceiveSuspended serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelReceiveSuspended(
                sessionId = "test-session",
                seq = 8L,
                tsNanos = 12345L,
                channelId = "channel-1",
                coroutineId = "coroutine-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelReceiveSuspended>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    @DisplayName("ChannelSendStarted serialization round-trip")
    fun `channelSendStarted serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelSendStarted(
                sessionId = "test-session",
                seq = 9L,
                tsNanos = 12345L,
                channelId = "channel-1",
                coroutineId = "coroutine-1",
                valueDescription = "test-value",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelSendStarted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"valueDescription\":\"test-value\""))
    }

    @Test
    @DisplayName("ChannelReceiveStarted serialization round-trip")
    fun `channelReceiveStarted serialization`() {
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = false
                encodeDefaults = true
            }
        val event =
            ChannelReceiveStarted(
                sessionId = "test-session",
                seq = 10L,
                tsNanos = 12345L,
                channelId = "channel-1",
                coroutineId = "coroutine-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelReceiveStarted>(serialized)

        assertEquals(event, deserialized)
    }
}
