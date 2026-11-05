package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SessionKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Mirrors `frontend/src/lib/session-kind.ts`: the ids vizcore mints for its own runs are demos. */
class SessionKindsTest {
    @Test
    fun `vizcore-minted ids are demos and every other id is an attached app`() {
        assertEquals(SessionKind.DEMO, sessionKindOf("scenario-order-processing-1792000000000"))
        assertEquals(SessionKind.DEMO, sessionKindOf("auto-1792000000000-1792000000001"))
        assertEquals(SessionKind.DEMO, sessionKindOf("api-session-1792000000000"))
        assertEquals(SessionKind.ATTACHED, sessionKindOf("order-service-1792000000000"))
        assertEquals(SessionKind.ATTACHED, sessionKindOf("my-scenario-1792000000000"))
        assertEquals(SessionKind.ATTACHED, sessionKindOf("auto-x"))
    }
}
