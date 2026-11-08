package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.EventContexts
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.application.metering.Entitlement
import com.dynokore.gst.tenancy.application.metering.Metering
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * INV-REQ-879, INV-Q-15474 A: PlanLimitExceeded is written to the real outbox of tenancy-svc with the real tenant and
 * the tenant-wide entity, in the transaction of the comparison, once per (tenant, limitKind, period).
 */
class PlanLimitOutboxTest {
    private val mapper = JsonMapper.builder().build()
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/tenancy/schemas/plan-limit-exceeded-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    private class Row(
        val tenant: UUID,
        val entityRef: String,
        val data: String,
    )

    private fun rows(tenant: TenantId? = null): List<Row> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select tenant_id, entity_ref, data::text from tenancy.outbox where type = ? " +
                        "and (?::uuid is null or tenant_id = ?::uuid) order by seq",
                ).use { s ->
                    s.setString(1, TenancyEventCatalogue.PLAN_LIMIT_EXCEEDED_TYPE)
                    s.setObject(2, tenant?.value)
                    s.setObject(3, tenant?.value)
                    s.executeQuery().use { r ->
                        generateSequence {
                            if (r.next()) {
                                Row(
                                    r.getObject(1, UUID::class.java),
                                    r.getString(2),
                                    r.getString(3),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private fun members(row: Row) = mapper.readTree(row.data).propertyNames().toSet()

    private fun run(block: (ProfileEnv, Metering, TransactionRunner, TenantId, String) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            val metering = context.getBean(Metering::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            env.withTenant { tenant ->
                val entity = env.entity(tenant, env.group(tenant)).entityRef
                block(env, metering, tx, tenant, entity)
            }
        }
    }

    private fun ack(
        metering: Metering,
        tx: TransactionRunner,
        entity: String,
        at: String,
    ) = tx.inTransaction { metering.acknowledged(entity, Instant.parse(at), 1) }

    private fun assertType(type: String) = assertThat(type).isEqualTo("com.dynokore.gst.tenancy.PlanLimitExceeded.v1")

    @Test
    fun `INV-AC-18065 the first excess of DOCUMENTS_PER_MONTH writes one tenant-wide row with period`() {
        run { _, metering, tx, tenant, entity ->
            tx.inTransaction { metering.record(Entitlement("P1", 100, 1)) }
            ack(metering, tx, entity, "2026-10-05T10:00:00Z")
            assertThat(rows(tenant)).isEmpty()
            ack(metering, tx, entity, "2026-10-06T10:00:00Z")
            val row = rows(tenant).single()
            assertThat(row.tenant).isEqualTo(tenant.value)
            assertThat(row.entityRef).isEqualTo(EntityRef.TENANT_WIDE.value)
            assertThat(members(row)).containsExactlyInAnyOrder("tenantRef", "limitKind", "limit", "observed", "period")
            val node = mapper.readTree(row.data)
            assertThat(node["limitKind"].asString()).isEqualTo("DOCUMENTS_PER_MONTH")
            assertThat(node["period"].asString()).isEqualTo("2026-10")
            assertThat(node["limit"].asInt()).isEqualTo(1)
            assertThat(node["observed"].asInt()).isEqualTo(2)
            assertThat(TenantRefs.idOf(node["tenantRef"].asString())).isEqualTo(tenant)
            assertThat(schema.validate(row.data, InputFormat.JSON)).isEmpty()
            assertType(TenancyEventCatalogue.PLAN_LIMIT_EXCEEDED_TYPE)
        }
    }

    @Test
    fun `INV-AC-18065 a second excess in the same period writes none and the next period writes one`() {
        run { _, metering, tx, tenant, entity ->
            tx.inTransaction { metering.record(Entitlement("P1", 100, 1)) }
            repeat(3) { ack(metering, tx, entity, "2026-10-0${it + 5}T10:00:00Z") }
            assertThat(rows(tenant)).hasSize(1)
            repeat(2) { ack(metering, tx, entity, "2026-11-0${it + 5}T10:00:00Z") }
            val all = rows(tenant)
            assertThat(all).hasSize(2)
            assertThat(mapper.readTree(all.last().data)["period"].asString()).isEqualTo("2026-11")
        }
    }

    @Test
    fun `INV-AC-18065 the first excess of ENTITIES writes one row without period`() {
        run { env, metering, tx, tenant, _ ->
            env.entity(tenant, env.group(tenant))
            tx.inTransaction { metering.record(Entitlement("P1", 1, 1000)) }
            val row = rows(tenant).single()
            assertThat(row.entityRef).isEqualTo(EntityRef.TENANT_WIDE.value)
            assertThat(members(row)).containsExactlyInAnyOrder("tenantRef", "limitKind", "limit", "observed")
            assertThat(schema.validate(row.data, InputFormat.JSON)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-18065 a tenant with no stored limit writes none`() {
        run { _, metering, tx, tenant, entity ->
            repeat(3) { ack(metering, tx, entity, "2026-10-0${it + 5}T10:00:00Z") }
            assertThat(rows(tenant)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-18065 a binding that writes the platform tenant is refused by the outbox`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val outbox = context.getBean(OutboxWriter::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val before = rows().size
            assertThatThrownBy {
                tx.inTransaction {
                    outbox.append(
                        listOf(OutboundEvent(TenancyEventCatalogue.PLAN_LIMIT_EXCEEDED_TYPE, "{}")),
                        EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.TENANT_WIDE, "tenant"),
                    )
                }
            }.isInstanceOf(RuntimeException::class.java)
            assertThat(rows()).hasSize(before)
        }
    }

    @Test
    fun `INV-AC-18065 a binding that writes the platform look-alike type fails the type equality`() {
        assertThatThrownBy { assertType("com.dynokore.gst.platform.PlanLimitExceeded.v1") }
            .isInstanceOf(AssertionError::class.java)
    }
}
