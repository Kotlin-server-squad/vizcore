package com.jh.proj.coroutineviz.observability

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Programmatic OpenTelemetry tracing SDK, built ONLY when [build] is invoked (D-02 / OTEL-01).
 *
 * Holds a local [SdkTracerProvider] + [Tracer] — it deliberately NEVER calls
 * `GlobalOpenTelemetry.set(...)` and NEVER uses `AutoConfiguredOpenTelemetrySdk`, so the instance
 * stays local to the gated wiring: when OTel is disabled nothing here is ever constructed (the
 * construction gate Plan 03 relies on). The builder chain is RESEARCH Pattern 2 verbatim:
 * `OtlpGrpcSpanExporter` → bounded `BatchSpanProcessor` → [DropCountingSpanProcessor] → a
 * `SdkTracerProvider` carrying a `service.name` resource and a trace-id-ratio sampler.
 *
 * @property provider the SDK tracer provider (owns the processor + exporter; [shutdown] flushes it).
 * @property tracer the application tracer the [CoroutineSpanExporter] spans are built from.
 * @property dropCounting the drop-counting processor wrapper (D-11 span-ended counter).
 */
class OtelTracing private constructor(
    val provider: SdkTracerProvider,
    val tracer: Tracer,
    val dropCounting: DropCountingSpanProcessor,
) {
    /**
     * Flush and shut down the SDK pipeline, blocking up to [SHUTDOWN_TIMEOUT_SECONDS] for the
     * batch processor to drain (RESEARCH "Shutdown/flush"; the `DbRetentionPolicy.stop()` analog
     * wired from `ApplicationStopping`). Idempotent at the SDK level.
     */
    fun shutdown() {
        provider.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OtelTracing::class.java)

        private const val INSTRUMENTATION_SCOPE = "coroutine-visualizer"
        private const val MAX_QUEUE_SIZE = 2048
        private const val MAX_EXPORT_BATCH_SIZE = 512
        private const val SCHEDULE_DELAY_SECONDS = 5L
        private const val EXPORTER_TIMEOUT_SECONDS = 30L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 10L

        /**
         * Construct the tracing SDK from [cfg]. This is the ONLY place OTel SDK objects come into
         * existence — callers (Plan 03's `configureObservability`) invoke it solely when
         * `cfg.enabled` is already true, preserving the construction gate (D-02). Nothing global is
         * set; the returned [OtelTracing] is the local owner of the pipeline.
         */
        fun build(cfg: OtelConfig): OtelTracing {
            val exporter =
                OtlpGrpcSpanExporter
                    .builder()
                    .setEndpoint(cfg.endpoint)
                    .build()

            val batch =
                BatchSpanProcessor
                    .builder(exporter)
                    .setMaxQueueSize(MAX_QUEUE_SIZE)
                    .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                    .setScheduleDelay(Duration.ofSeconds(SCHEDULE_DELAY_SECONDS))
                    .setExporterTimeout(Duration.ofSeconds(EXPORTER_TIMEOUT_SECONDS))
                    .build()

            val dropCounting = DropCountingSpanProcessor(batch)

            val resource =
                Resource.getDefault().merge(
                    Resource
                        .builder()
                        .put("service.name", cfg.serviceName)
                        .build(),
                )

            val provider =
                SdkTracerProvider
                    .builder()
                    .setResource(resource)
                    .setSampler(Sampler.traceIdRatioBased(cfg.samplingRatio))
                    .addSpanProcessor(dropCounting)
                    .build()

            val tracer = provider.get(INSTRUMENTATION_SCOPE)

            logger.info(
                "OpenTelemetry tracing SDK constructed (endpoint={}, service.name={}, samplingRatio={})",
                cfg.endpoint,
                cfg.serviceName,
                cfg.samplingRatio,
            )

            return OtelTracing(provider, tracer, dropCounting)
        }
    }
}
