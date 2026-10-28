package com.dynokore.platform.serviceruntime.securityevents

import com.dynokore.platform.serviceruntime.binding.TelemetryExport
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

/**
 * [SecurityEventEmitter] that posts an OTLP/HTTP JSON `LogsData` with one `LogRecord` to
 * `<TelemetryExport.otlpEndpoint()>/v1/logs` (INV-Q-10939). The record body is the class wire value and its
 * attributes are the configured class attribute, `user.id` and `gst.tenant.id`: no other attribute, no invoice content
 * (INV-REQ-2578).
 *
 * [emit] only builds the record and offers it to a bounded queue of `queue-capacity` records; one daemon thread sends
 * them with at most `retry-max-attempts` attempts, `retry-backoff-millis` apart (INV-REQ-1384: the events are
 * retried). A record that exhausts its attempts or finds the queue full is counted
 * in [DROPPED_METER] and logged at ERROR with the identifier [DROPPED_CODE] and a reason, no catalogue code and no record content (INV-Q-11052).
 */
class OtlpSecurityEventEmitter(
    private val properties: SecurityEventProperties,
    private val export: () -> TelemetryExport?,
    private val meters: MeterRegistry?,
    private val clock: Clock = Clock.systemUTC(),
    private val client: HttpClient = HttpClient.newHttpClient(),
) : SecurityEventEmitter,
    AutoCloseable {
    private val mapper = ObjectMapper()
    private val queue: BlockingQueue<String> = ArrayBlockingQueue(properties.queueCapacity)
    private val retry: Retry =
        Retry.of(
            "gst-security-events",
            RetryConfig
                .custom<Any>()
                .maxAttempts(properties.retryMaxAttempts)
                .waitDuration(Duration.ofMillis(properties.retryBackoffMillis))
                .retryExceptions(Exception::class.java)
                .build(),
        )
    private val worker =
        Thread(::drain, "gst-security-events").apply {
            isDaemon = true
            start()
        }

    override fun emit(
        securityClass: SecurityClass,
        actor: String,
        tenantId: String,
    ) {
        try {
            val body = body(securityClass, actor, tenantId)
            if (!queue.offer(body)) dropped(QUEUE_FULL)
        } catch (expected: Throwable) {
            // emit never throws to the caller; a record that cannot even be built is lost like one whose attempts ran out
            dropped(RETRIES_EXHAUSTED)
        }
    }

    override fun close() {
        worker.interrupt()
    }

    private fun body(
        securityClass: SecurityClass,
        actor: String,
        tenantId: String,
    ): String {
        val attributes =
            linkedMapOf(
                properties.classAttribute to securityClass.wireValue,
                USER_ID to actor,
                TENANT_ID to tenantId,
            )
        require(properties.allowedAttributes.containsAll(attributes.keys)) { "attribute not allowed" }
        val record = mapper.createObjectNode()
        record.put("timeUnixNano", (clock.instant().let { it.epochSecond * NANOS + it.nano }).toString())
        record.putObject("body").put("stringValue", securityClass.wireValue)
        val array = record.putArray("attributes")
        attributes.forEach { (key, value) ->
            array
                .addObject()
                .put("key", key)
                .putObject("value")
                .put("stringValue", value)
        }
        val root: ObjectNode = mapper.createObjectNode()
        root
            .putArray("resourceLogs")
            .addObject()
            .putArray("scopeLogs")
            .addObject()
            .putArray("logRecords")
            .add(record)
        return mapper.writeValueAsString(root)
    }

    private fun drain() {
        while (!Thread.currentThread().isInterrupted) {
            val body =
                try {
                    queue.take()
                } catch (interrupted: InterruptedException) {
                    return
                }
            try {
                retry.executeRunnable { send(body) }
            } catch (interrupted: InterruptedException) {
                return
            } catch (expected: Throwable) {
                dropped(RETRIES_EXHAUSTED)
            }
        }
    }

    private fun send(body: String) {
        val endpoint = checkNotNull(export()) { "no TelemetryExport" }.otlpEndpoint().trimEnd('/')
        val request =
            HttpRequest
                .newBuilder(URI.create("$endpoint/v1/logs"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        check(status in 200..299) { "collector answered $status" }
    }

    private fun dropped(reason: String) {
        meters?.counter(DROPPED_METER)?.increment()
        log.error("{} reason={}", DROPPED_CODE, reason)
    }

    companion object {
        const val DROPPED_METER = "gst_security_events_dropped_total"
        const val DROPPED_CODE = "SECURITY_EVENT_DROPPED"
        const val USER_ID = "user.id"
        const val TENANT_ID = "gst.tenant.id"

        /** The closed set of reasons in the drop line (INV-Q-11052); listed in SECURITY-EVENTS.md. */
        const val QUEUE_FULL = "QUEUE_FULL"
        const val RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED"
        private const val NANOS = 1_000_000_000L
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val log = LoggerFactory.getLogger(OtlpSecurityEventEmitter::class.java)
    }
}
