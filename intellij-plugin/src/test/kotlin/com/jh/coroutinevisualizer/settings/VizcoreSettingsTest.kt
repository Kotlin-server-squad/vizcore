package com.jh.coroutinevisualizer.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit gate for the poll-interval clamp. Uses the pure [VizcoreSettings.clampPollIntervalMs]
 * companion helper so no IDE application fixture is needed.
 */
class VizcoreSettingsTest {
    @Test fun `default poll interval is 200`() {
        assertEquals(200, VizcoreSettings.DEFAULT_POLL_INTERVAL_MS)
    }

    @Test fun `in-range value is unchanged`() {
        assertEquals(200, VizcoreSettings.clampPollIntervalMs(200))
    }

    @Test fun `below-minimum value clamps up to the minimum`() {
        assertEquals(VizcoreSettings.MIN_POLL_INTERVAL_MS, VizcoreSettings.clampPollIntervalMs(0))
        assertEquals(VizcoreSettings.MIN_POLL_INTERVAL_MS, VizcoreSettings.clampPollIntervalMs(-100))
        assertEquals(VizcoreSettings.MIN_POLL_INTERVAL_MS, VizcoreSettings.clampPollIntervalMs(10))
    }

    @Test fun `above-maximum value clamps down to the maximum`() {
        assertEquals(VizcoreSettings.MAX_POLL_INTERVAL_MS, VizcoreSettings.clampPollIntervalMs(999_999))
    }

    @Test fun `minimum is at least 50`() {
        assert(VizcoreSettings.MIN_POLL_INTERVAL_MS >= 50)
    }
}
