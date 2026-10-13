package com.dynokore.platform.serviceruntime.connection

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.jobs.JobPayloads
import com.dynokore.platform.serviceruntime.jobs.JobsHarness
import com.dynokore.platform.serviceruntime.jobs.MutableClock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/**
 * The connection contract (INV-REQ-836 b3-b4, INV-Q-2983) over PostgreSQL 17 and 18: an instance publishes
 * `ConnectionStatusChanged` only when its own breaker-mapped state changes, with its instance id in the envelope.
 */
class ConnectionStatusIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `each instance publishes its own state changes only`(image: String) {
        JobsHarness(image).use { h ->
            val clock = MutableClock(Instant.parse("2026-10-12T00:00:00Z"))
            val one = h.replica("transport-svc-1", clock)
            val two = h.replica("transport-svc-2", clock)
            val reporter1 = OutboxConnectionStatusReporter(one.scope, one.outbox)
            val reporter2 = OutboxConnectionStatusReporter(two.scope, two.outbox)
            val ap =
                ConnectionRecord(
                    "ap-primary",
                    "https://ap.example",
                    SecretRef(SECRET),
                    "production",
                    "transport-svc",
                    JobsHarness.TENANT_A,
                    EntityRef.TENANT_WIDE,
                )
            val failure = ConnectionFailure(Instant.parse("2026-10-12T00:00:01Z"), "TRN-004")

            reporter1.report(ap, CircuitBreakerState.CLOSED) shouldBe false
            reporter1.report(ap, CircuitBreakerState.OPEN, failure) shouldBe true
            reporter1.report(ap, CircuitBreakerState.OPEN, failure) shouldBe false
            reporter2.report(ap, CircuitBreakerState.HALF_OPEN) shouldBe true
            reporter1.report(ap, CircuitBreakerState.CLOSED) shouldBe true

            val events =
                h.rows(
                    "select instance_id, tenant_id, data::text as data from submission.outbox " +
                        "where type = ? order by seq",
                    JobPayloads.CONNECTION_STATUS_CHANGED,
                )
            events.map { it["instance_id"] } shouldContainExactly
                listOf("transport-svc-1", "transport-svc-2", "transport-svc-1")
            events.map { it["tenant_id"] }.toSet() shouldBe setOf(JobsHarness.TENANT_A.value)
            events[0]["data"].toString() shouldContain """"state": "down""""
            events[0]["data"].toString() shouldContain """"reason": "TRN-004""""
            events[1]["data"].toString() shouldContain """"state": "degraded""""
            events[2]["data"].toString() shouldContain """"state": "up""""
        }
    }

    private companion object {
        const val SECRET = "gst/transport/ap-primary"
    }
}
