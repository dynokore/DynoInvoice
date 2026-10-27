package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.http.EntitiesController
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.adapters.http.TenantsController
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INBOX
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SHORT_CODE
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.RegisterBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.UpdateBusinessUnitRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
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
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-2779 through the service boundary: a `ShortCodeUsed` event in the event log is consumed by the inbox consumer
 * of [ShortCodeUsedConsumerConfiguration], and `updateBusinessUnit` then refuses a code change with 409 TNC-003. Only
 * the event-log read is a list in memory: the `eventlog` schema belongs to the event-log instance.
 */
class ShortCodeUsedConsumerTest {
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
            if (topic == "gst.aggregation.short-code-used.v1") {
                records.filter { it.offset > afterOffset }.take(limit)
            } else {
                emptyList()
            }

        private companion object {
            // the offset row of consumer tenancy-svc is platform-scoped and the database is shared by the tests
            val OFFSETS = AtomicLong(System.currentTimeMillis() * 1000)
        }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
        val log: Log,
    ) {
        val tenantsApi: TenantsController = context.getBean(TenantsController::class.java)
        val entitiesApi: EntitiesController = context.getBean(EntitiesController::class.java)
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val consumer: JdbcInboxConsumer = context.getBean(JdbcInboxConsumer::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, "user:test") { block() }

        fun newTenant(): TenantId {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref))
            return TenantRefs.idOf(ref)
        }

        fun businessUnit(
            tenant: TenantId,
            code: String,
        ): String =
            inTenant(tenant) {
                val group =
                    entitiesApi
                        .createGroup(
                            UUID.randomUUID(),
                            CreateGroupRequest(
                                tenant.value.toString(),
                                "Group ${UUID.randomUUID()}",
                                CreateGroupRequest.GroupKind.GST_GROUP,
                                "M2-GSTN",
                            ),
                        ).body!!
                val entity =
                    entitiesApi
                        .createEntity(
                            UUID.randomUUID(),
                            CreateEntityRequest(
                                tenant.value.toString(),
                                group.groupRef,
                                "Entity ${UUID.randomUUID()}",
                                CreateEntityRequest.Constitution.PRIVATE_LIMITED,
                                null,
                                null,
                                "H" +
                                    UUID
                                        .randomUUID()
                                        .toString()
                                        .take(8)
                                        .uppercase()
                                        .replace("-", "A"),
                            ),
                        ).body!!
                entitiesApi
                    .registerBusinessUnit(
                        UUID.randomUUID(),
                        RegisterBusinessUnitRequest(tenant.value.toString(), entity.entityRef, "Outlet", code),
                    ).body!!
                    .businessUnitRef
            }

        fun rename(
            tenant: TenantId,
            unit: String,
            code: String,
        ) = inTenant(tenant) {
            entitiesApi.updateBusinessUnit(unit, UUID.randomUUID(), UpdateBusinessUnitRequest("Outlet", code))
        }

        fun storedCodes(tenant: TenantId): Set<String> =
            inTenant(tenant) {
                tx.inTransaction {
                    dsl
                        .select(SHORT_CODE.CODE)
                        .from(SHORT_CODE)
                        .fetch(SHORT_CODE.CODE)
                        .filterNotNull()
                        .toSet()
                }
            }

        fun inboxRows(tenant: TenantId): Int = inTenant(tenant) { tx.inTransaction { dsl.fetchCount(INBOX) } }
    }

    private fun run(block: (Env) -> Unit) {
        val log = Log()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .withBean(RebuildRequiredSink::class.java, { RebuildRequiredSink { } })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, log))
            }
    }

    private fun used(
        tenant: TenantId,
        eventId: String,
        code: String,
    ): String =
        json.write(
            Envelope(
                id = EventId(eventId),
                source = "/gst/aggregation-svc",
                type = TenancyEventCatalogue.SHORT_CODE_USED_TYPE,
                subject = "agg-1",
                time = Instant.parse("2026-10-27T10:00:00Z"),
                tenant = tenant,
                entity = EventEntityRef("ENT-1"),
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "aggregation-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"code":"$code","nodeKind":"BUSINESS_UNIT","nodeRef":"unit-1","aggregateId":"agg-1",""" +
                        """"documentNumber":"AGG-1"}""",
            ),
        )

    @Test
    fun `INV-AC-2779 a consumed ShortCodeUsed event makes updateBusinessUnit refuse a code change with TNC-003`() {
        run { env ->
            val tenant = env.newTenant()
            val unit = env.businessUnit(tenant, "OUT1")
            env.rename(tenant, unit, "OUT2") // before the event the same call is accepted
            assertThat(env.storedCodes(tenant)).contains("OUT2").doesNotContain("OUT1")

            env.log.add(used(tenant, "ev-${UUID.randomUUID()}", "OUT2"))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))

            val thrown = runCatching { env.rename(tenant, unit, "OUT3") }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(TenancyRefusal::class.java)
            assertThat((thrown as TenancyRefusal).code).isEqualTo(TenancyErrorCode.SHORT_CODE_FIXED)
            assertThat(thrown.message).startsWith("TNC-003")
            assertThat(env.storedCodes(tenant)).contains("OUT2").doesNotContain("OUT3")
        }
    }

    @Test
    fun `INV-AC-2779 a redelivery of the same event id applies nothing twice`() {
        run { env ->
            val tenant = env.newTenant()
            env.businessUnit(tenant, "RED1")
            val eventId = "ev-${UUID.randomUUID()}"
            env.log.add(used(tenant, eventId, "RED1"))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
            env.log.add(used(tenant, eventId, "RED1"))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(0, 0, 1, 0, 0))
            assertThat(env.inboxRows(tenant)).isEqualTo(1)
        }
    }
}
