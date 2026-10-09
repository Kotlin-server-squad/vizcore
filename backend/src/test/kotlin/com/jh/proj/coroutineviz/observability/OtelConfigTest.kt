package com.jh.proj.coroutineviz.observability

import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Wave-0 proof for [OtelConfig.from] (D-01, V5):
 *  - the four `observability.otel.*` knobs default correctly on an empty config,
 *  - explicit values read through,
 *  - `samplingRatio` is clamped into `[0.0, 1.0]` and a malformed value falls back to `1.0`.
 *
 * Uses `@org.junit.jupiter.api.Test` (repo convention — kotlin-test-junit alone is
 * undiscovered under `useJUnitPlatform`).
 */
class OtelConfigTest {
    @Test
    fun `empty config yields the documented defaults (off)`() {
        val cfg = MapApplicationConfig()

        val otel = OtelConfig.from(cfg)

        assertFalse(otel.enabled, "OTel must default OFF (construction-gated)")
        assertEquals("http://localhost:4317", otel.endpoint)
        assertEquals("coroutine-visualizer", otel.serviceName)
        assertEquals(1.0, otel.samplingRatio)
    }

    @Test
    fun `explicit enabled + endpoint + serviceName read through`() {
        val cfg =
            MapApplicationConfig(
                "observability.otel.enabled" to "true",
                "observability.otel.endpoint" to "http://collector:4317",
                "observability.otel.serviceName" to "my-app",
            )

        val otel = OtelConfig.from(cfg)

        assertEquals(true, otel.enabled)
        assertEquals("http://collector:4317", otel.endpoint)
        assertEquals("my-app", otel.serviceName)
    }

    @Test
    fun `samplingRatio above 1 is clamped to 1`() {
        val cfg = MapApplicationConfig("observability.otel.samplingRatio" to "2.5")

        assertEquals(1.0, OtelConfig.from(cfg).samplingRatio)
    }

    @Test
    fun `samplingRatio below 0 is clamped to 0`() {
        val cfg = MapApplicationConfig("observability.otel.samplingRatio" to "-0.5")

        assertEquals(0.0, OtelConfig.from(cfg).samplingRatio)
    }

    @Test
    fun `malformed samplingRatio falls back to the default 1`() {
        val cfg = MapApplicationConfig("observability.otel.samplingRatio" to "abc")

        assertEquals(1.0, OtelConfig.from(cfg).samplingRatio)
    }
}
