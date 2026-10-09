package com.jh.coroutinevisualizer.toolwindow

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ToolWindowStateTest {
    @Test fun `no correlation means not launched`() {
        assertEquals(ContentState.NOT_LAUNCHED, ContentState.of(correlation = null, backendDown = false, hasModel = false))
    }

    @Test fun `backend down means backend down`() {
        assertEquals(ContentState.BACKEND_DOWN, ContentState.of(correlation = "c", backendDown = true, hasModel = false))
    }

    @Test fun `armed but no model yet means connecting`() {
        assertEquals(ContentState.CONNECTING, ContentState.of(correlation = "c", backendDown = false, hasModel = false))
    }

    @Test fun `armed with a model means live`() {
        assertEquals(ContentState.LIVE, ContentState.of(correlation = "c", backendDown = false, hasModel = true))
    }
}
