package com.jh.coroutinevisualizer.receiver

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jh.coroutinevisualizer.PluginSessionManager
import com.jh.coroutinevisualizer.settings.VisualizerSettings
import com.jh.proj.coroutineviz.events.VizEvent
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lightweight HTTP server running inside the IDE that receives visualization events
 * from an instrumented application.
 *
 * The instrumented app sends events via HTTP POST to this receiver, which forwards
 * them to the plugin's VizSession for display in the tool window.
 *
 * Default port: 8090 (configurable in settings)
 */
@Service(Service.Level.PROJECT)
class PluginEventReceiver(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(PluginEventReceiver::class.java)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isRunning: Boolean get() = server != null

    fun start() {
        if (isRunning) return

        val settings = VisualizerSettings.getInstance()
        val port = settings.receiverPort

        try {
            server =
                embeddedServer(CIO, port = port) {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            },
                        )
                    }

                    routing {
                        // Health check
                        get("/health") {
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "project" to project.name))
                        }

                        // Receive events from instrumented application
                        post("/events") {
                            val session = PluginSessionManager.getOrCreateSession(project)
                            try {
                                val body = call.receiveText()
                                val json =
                                    Json {
                                        ignoreUnknownKeys = true
                                        isLenient = true
                                    }

                                // Parse JSON array of events
                                val jsonArray = json.parseToJsonElement(body)
                                val events =
                                    when (jsonArray) {
                                        is kotlinx.serialization.json.JsonArray ->
                                            jsonArray
                                                .map { element ->
                                                    parseEventFromJson(element, json)
                                                }.filterNotNull()
                                        is kotlinx.serialization.json.JsonObject -> {
                                            // Single event sent as object
                                            listOfNotNull(parseEventFromJson(jsonArray, json))
                                        }
                                        else -> emptyList()
                                    }

                                // Forward parsed events to the session
                                var forwarded = 0
                                for (event in events) {
                                    session.send(event)
                                    forwarded++
                                }

                                logger.debug("Received and forwarded $forwarded events for project: ${project.name}")
                                call.respond(HttpStatusCode.OK, mapOf("received" to true, "count" to forwarded))
                            } catch (e: Exception) {
                                logger.warn("Failed to process event", e)
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                            }
                        }

                        // Session info
                        get("/session") {
                            val session = PluginSessionManager.getSession(project)
                            if (session != null) {
                                call.respond(
                                    HttpStatusCode.OK,
                                    mapOf(
                                        "sessionId" to session.sessionId,
                                        "eventCount" to session.store.all().size,
                                        "coroutineCount" to session.snapshot.coroutines.size,
                                    ),
                                )
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No active session"))
                            }
                        }
                    }
                }

            scope.launch {
                server?.start(wait = false)
                logger.info("Coroutine Visualizer event receiver started on port $port for project: ${project.name}")
            }
        } catch (e: Exception) {
            logger.error("Failed to start event receiver on port $port", e)
            server = null
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        logger.info("Coroutine Visualizer event receiver stopped for project: ${project.name}")
    }

    override fun dispose() {
        stop()
        scope.cancel()
        PluginSessionManager.closeSession(project)
    }

    /**
     * Parse a VizEvent from a JSON element.
     * Uses the "kind" field to determine the event type and deserializes accordingly.
     * Returns null for unrecognized event kinds (forward-compatible).
     */
    private fun parseEventFromJson(
        element: JsonElement,
        json: Json,
    ): VizEvent? {
        return try {
            val obj = element.jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.content ?: return null

            // Use a generic event wrapper that captures all fields
            // The session's EventApplier will handle type-specific processing
            GenericPluginEvent(
                sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: "",
                seq = obj["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                tsNanos = obj["tsNanos"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.nanoTime(),
                eventKind = kind,
                rawJson = element.toString(),
            )
        } catch (e: Exception) {
            logger.debug("Failed to parse event: ${e.message}")
            null
        }
    }

    companion object {
        fun getInstance(project: Project): PluginEventReceiver = project.getService(PluginEventReceiver::class.java)
    }
}

/**
 * Generic event wrapper for events received from instrumented applications.
 * Preserves the raw JSON for downstream processing while implementing VizEvent.
 */
data class GenericPluginEvent(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val eventKind: String,
    val rawJson: String,
) : VizEvent {
    override val kind: String get() = eventKind
}
