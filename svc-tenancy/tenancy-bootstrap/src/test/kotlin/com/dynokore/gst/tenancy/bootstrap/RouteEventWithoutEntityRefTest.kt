package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ConsumedElectionEvents
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-15489, consumer side: the AccessPointRouteChanged domain event travels with a real `tenantid` and no
 * `entityref` (ruling INV-Q-15474 A). It is read through the platform envelope by the same inbox consumer as the
 * route test of [ElectionReplicasTest], and the route replica holds exactly the payload. Only the event-log read is a
 * list in memory.
 */
class RouteEventWithoutEntityRefTest {
    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(OFFSETS.incrementAndGet(), envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> =
            if (topic == ConsumedElectionEvents.ACCESS_POINT_ROUTE_CHANGED_TOPIC) {
                records.filter { it.offset > afterOffset }.take(limit)
            } else {
                emptyList()
            }

        private companion object {
            // the offset row of the consumer is platform-scoped and the database is shared by the tests
            val OFFSETS = AtomicLong(System.currentTimeMillis() * 1000 + 500)
        }
    }

    private data class Row(
        val tenant: String,
        val connection: String,
        val entityRefs: List<String>,
        val route: String?,
        val version: Long,
    )

    private fun run(block: (Log, InboxConsumer, ProfileEnv) -> Unit) {
        val log = Log()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                val consumer = context.getBean("accessPointRouteChangedConsumer", InboxConsumer::class.java)
                block(log, consumer, ProfileEnv(context))
            }
    }

    /** The structured JSON of a route event with a real tenant and no `entityref` member. */
    private fun record(
        type: String,
        tenant: TenantId,
        connection: String,
        entityRefs: List<String>,
        route: String?,
        version: Long,
    ): String {
        val refs = entityRefs.joinToString(",") { "\"$it\"" }
        val routeText = route?.let { ",\"acknowledgementRoute\":\"$it\"" }.orEmpty()
        return """{"specversion":"1.0","id":"ev-${UUID.randomUUID()}","source":"/gst/transport-svc","type":"$type",""" +
            """"subject":"$connection","time":"${Instant.now()}","datacontenttype":"application/json",""" +
            """"tenantid":"$tenant","instanceid":"transport-svc-0","correlationid":"corr-${UUID.randomUUID()}",""" +
            """"causationid":"cause-${UUID.randomUUID()}",""" +
            """"data":{"connectionRef":"$connection","entityRefs":[$refs]$routeText,"version":$version}}"""
    }

    private fun stored(
        tenant: TenantId,
        connection: String,
    ): Row? =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select tenant_id::text, connection_ref, entity_refs, route, version " +
                        "from tenancy.access_point_route where tenant_id = ?::uuid and connection_ref = ?",
                ).use { s ->
                    s.setString(1, tenant.toString())
                    s.setString(2, connection)
                    s.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            val refs = (rs.getArray(3).array as Array<String>).toList()
                            Row(rs.getString(1), rs.getString(2), refs, rs.getString(4), rs.getLong(5))
                        }
                    }
                }
        }

    private val type = ConsumedElectionEvents.ACCESS_POINT_ROUTE_CHANGED

    @Test
    fun `INV-AC-15489 a route event without entityref is consumed and the replica row equals the payload`() {
        run { log, consumer, env ->
            env.withEntity { tenant, entity ->
                val refs = listOf(entity.entityRef, "ENT-2")
                log.add(record(type, tenant, "AP-1", refs, "AP_CALLBACK", 3))
                assertThat(consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
                assertThat(stored(tenant, "AP-1")).isEqualTo(Row(tenant.toString(), "AP-1", refs, "AP_CALLBACK", 3))
            }
        }
    }

    @Test
    fun `INV-AC-15489 a later event with entityRefs empty replaces the stored list with the empty list`() {
        run { log, consumer, env ->
            env.withEntity { tenant, entity ->
                log.add(record(type, tenant, "AP-1", listOf(entity.entityRef), "POLLING", 1))
                assertThat(consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
                log.add(record(type, tenant, "AP-1", emptyList(), null, 2))
                assertThat(consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
                assertThat(stored(tenant, "AP-1")).isEqualTo(Row(tenant.toString(), "AP-1", emptyList(), null, 2))
            }
        }
    }

    @Test
    fun `INV-AC-15489 an older version leaves the row unchanged`() {
        run { log, consumer, env ->
            env.withEntity { tenant, entity ->
                val refs = listOf(entity.entityRef)
                log.add(record(type, tenant, "AP-1", refs, "POLLING", 5))
                assertThat(consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
                log.add(record(type, tenant, "AP-1", emptyList(), null, 4))
                val result = consumer.pollOnce()
                assertThat(result.deadLettered).isZero()
                assertThat(result.applied).isZero()
                assertThat(stored(tenant, "AP-1")).isEqualTo(Row(tenant.toString(), "AP-1", refs, "POLLING", 5))
            }
        }
    }

    @Test
    fun `INV-AC-15489 a record of another domain type without entityref is refused as a contract violation`() {
        run { log, consumer, env ->
            env.withEntity { tenant, entity ->
                val other = "com.dynokore.gst.tenancy.AccessPointRouteChanged.v1"
                val raw = record(other, tenant, "AP-1", listOf(entity.entityRef), "POLLING", 1)
                assertThatThrownBy { EnvelopeJson().read(raw) }
                    .isInstanceOf(ContractViolation::class.java)
                    .hasMessageContaining("entityref is required")
                log.add(raw)
                val result = consumer.pollOnce()
                assertThat(result.deadLettered).isEqualTo(1)
                assertThat(result.applied).isZero()
                assertThat(stored(tenant, "AP-1")).isNull()
            }
        }
    }
}
