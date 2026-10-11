package com.jh.proj.coroutineviz.events

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Return this serialized [VizEvent] with its top-level `sessionId` member replaced
 * by [sessionId], leaving every other member (and the polymorphic type
 * discriminator) untouched.
 *
 * Used on the ingest path: the session a frame belongs to is resolved server-side
 * from the request, so the `sessionId` the client put in the frame body is not
 * authoritative and must not reach the store, the snapshot or the event bus.
 * [VizEvent.sessionId] is an immutable `val` implemented by every event data
 * class, so the value is re-stamped here, on the decoded JSON, before the event
 * object exists — the wire format is unchanged and no event class is touched.
 *
 * An element that is not a JSON object is returned unchanged: it cannot decode
 * to a [VizEvent] anyway, so the caller's malformed-frame path still handles it.
 */
fun JsonElement.withSessionId(sessionId: String): JsonElement {
    val obj = this as? JsonObject ?: return this
    if ((obj["sessionId"] as? JsonPrimitive)?.content == sessionId) return obj
    return JsonObject(obj + ("sessionId" to JsonPrimitive(sessionId)))
}
