package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorResponseTest {
    @Test
    fun `an error without a code keeps the documented error-only shape`() {
        assertEquals(
            """{"error":"Session not found"}""",
            appJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("Session not found")),
        )
    }

    @Test
    fun `an error with a code carries it next to the message`() {
        assertEquals(
            """{"error":"busy","code":"demo-limit"}""",
            appJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("busy", code = "demo-limit")),
        )
    }
}
