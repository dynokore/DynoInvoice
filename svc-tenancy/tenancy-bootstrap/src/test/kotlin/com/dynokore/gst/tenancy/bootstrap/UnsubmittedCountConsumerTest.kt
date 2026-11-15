package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.http.TenantsController
import com.dynokore.gst.tenancy.adapters.offboarding.JooqUnsubmittedCountReplica
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-23029 through the service boundary: the inbox consumer of [UnsubmittedCountConsumerConfiguration] is in the
 * application context, and an `UnsubmittedDocumentCountMeasured` event for tenant A leaves one replica row for A and
 * none for B. Only the event-log read is a list in memory.
 */
class UnsubmittedCountConsumerTest {
    private val json = EnvelopeJson()

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
            if (topic == UnsubmittedCountConsumerConfiguration.TOPIC) {
                records.filter { it.offset > afterOffset }.take(limit)
            } else {
                emptyList()
            }

        private companion object {
            // the offset row of the consumer is platform-scoped and the database is shared by the tests
            val OFFSETS = AtomicLong(System.currentTimeMillis() * 1000)
        }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
        val log: Log,
    ) {
        val tenantsApi: TenantsController = context.getBean(TenantsController::class.java)
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val consumer: JdbcInboxConsumer =
            context.getBean("unsubmittedCountMeasuredConsumer", JdbcInboxConsumer::class.java)

        fun newTenant(): TenantId {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            tenantsApi.createTenant(
                FIXED_FLOW_CONTEXT,
                UUID.randomUUID(),
                CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref),
            )
            return TenantRefs.idOf(ref)
        }

        fun stored(
            tenant: TenantId,
            asked: TenantId = tenant,
        ) = tenants.bind(tenant, "user:test") {
            tx.inTransaction { JooqUnsubmittedCountReplica(dsl).read(asked) }
        }
    }

    private fun run(block: (Env) -> Unit) {
        val log = Log()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, log))
            }
    }

    private fun measured(tenant: TenantId): String =
        json.write(
            Envelope(
                id = EventId("ev-${UUID.randomUUID()}"),
                source = "/gst/submission-svc",
                type = TenancyEventCatalogue.UNSUBMITTED_COUNT_MEASURED_TYPE,
                subject = "count-1",
                time = Instant.parse("2026-11-20T10:00:00Z"),
                tenant = tenant,
                entity = EntityRef.TENANT_WIDE,
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "submission-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":5,"entities":""" +
                        """[{"entityRef":"E1","unsubmittedDocumentCount":3},""" +
                        """{"entityRef":"E2","unsubmittedDocumentCount":2}]}""",
            ),
        )

    @Test
    fun `INV-AC-23029 the context holds the inbox consumer of the configuration on the catalogued topic`() {
        run { env ->
            assertThat(UnsubmittedCountConsumerConfiguration.CONSUMER).isEqualTo(TenancyEventCatalogue.SERVICE)
            assertThat(UnsubmittedCountConsumerConfiguration.TOPIC)
                .isEqualTo(TenancyEventCatalogue.UNSUBMITTED_COUNT_MEASURED_TOPIC)
            assertThat(env.context.getBeansOfType(JdbcInboxConsumer::class.java))
                .containsKey("unsubmittedCountMeasuredConsumer")
        }
    }

    @Test
    fun `INV-AC-23029 an event delivered through the consumer for tenant A leaves one row for A and none for B`() {
        run { env ->
            val a = env.newTenant()
            val b = env.newTenant()
            env.log.add(measured(a))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
            val row = env.stored(a)
            assertThat(row).isNotNull
            assertThat(row!!.measurement.total).isEqualTo(5)
            assertThat(env.stored(b)).isNull()
            assertThat(env.stored(b, asked = a)).isNull()
        }
    }
}
