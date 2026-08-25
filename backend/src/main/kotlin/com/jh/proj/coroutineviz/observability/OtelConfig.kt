package com.jh.proj.coroutineviz.observability

import io.ktor.server.config.ApplicationConfig

/**
 * Immutable view of the locked `observability.otel` config block (D-01).
 *
 * Read once at module init via [from]; everything downstream (the SDK factory in Plan 02,
 * the exporter subscriber, the wiring gate) consumes this type. This is PURE config — it
 * constructs no OTel SDK objects (the SDK is built only when `enabled=true`, in Plan 02),
 * preserving the construction gate (D-02): when OTel is off, nothing OTel is ever built.
 *
 * @property enabled construction gate — when `false`, no SDK/exporter/subscriber is built.
 * @property endpoint OTLP collector endpoint (gRPC, default `http://localhost:4317`).
 * @property serviceName `service.name` resource attribute (default `coroutine-visualizer`).
 * @property samplingRatio trace-id-ratio sampler input, always in `[0.0, 1.0]` (V5 clamp).
 */
data class OtelConfig(
    val enabled: Boolean,
    val endpoint: String,
    val serviceName: String,
    val samplingRatio: Double,
) {
    companion object {
        private const val DEFAULT_ENDPOINT = "http://localhost:4317"
        private const val DEFAULT_SERVICE_NAME = "coroutine-visualizer"
        private const val DEFAULT_SAMPLING_RATIO = 1.0
        private const val MIN_SAMPLING_RATIO = 0.0
        private const val MAX_SAMPLING_RATIO = 1.0

        /**
         * Read the four `observability.otel.*` knobs using the repo's
         * `propertyOrNull(...)?.getString()?.toX() ?: default` env-override idiom
         * (Application.kt `configureRateLimit`/`startDbRetention` analog).
         *
         * `samplingRatio` is parsed leniently (`toDoubleOrNull() ?: 1.0` — a malformed
         * value falls back to the default) and clamped into `[0.0, 1.0]` (V5 / T-12-02),
         * so an out-of-range or garbage operator value can never drive nonsensical
         * sampler behavior.
         */
        fun from(config: ApplicationConfig): OtelConfig =
            OtelConfig(
                enabled =
                    config.propertyOrNull("observability.otel.enabled")?.getString()?.toBoolean() ?: false,
                endpoint =
                    config.propertyOrNull("observability.otel.endpoint")?.getString() ?: DEFAULT_ENDPOINT,
                serviceName =
                    config.propertyOrNull("observability.otel.serviceName")?.getString() ?: DEFAULT_SERVICE_NAME,
                samplingRatio =
                    (config.propertyOrNull("observability.otel.samplingRatio")?.getString()?.toDoubleOrNull() ?: DEFAULT_SAMPLING_RATIO)
                        .coerceIn(MIN_SAMPLING_RATIO, MAX_SAMPLING_RATIO),
            )
    }
}
