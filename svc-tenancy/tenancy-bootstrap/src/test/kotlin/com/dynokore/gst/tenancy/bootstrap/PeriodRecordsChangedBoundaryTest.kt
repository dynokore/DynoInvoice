package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.adapters.events.PeriodRecordsChangedOutbox
import com.dynokore.gst.tenancy.adapters.events.ShortCodeUsedHandler
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.ConfirmKind
import com.dynokore.gst.tenancy.application.period.ConfirmOutcome
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.ConfirmRequest
import com.dynokore.gst.tenancy.application.period.FilingFrequencyChangePublisher
import com.dynokore.gst.tenancy.application.period.FilingFrequencyCommands
import com.dynokore.gst.tenancy.application.period.FrequencyOutcome
import com.dynokore.gst.tenancy.application.period.FrequencyRequest
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.PeriodGeneration
import com.dynokore.gst.tenancy.application.period.PeriodOutcome
import com.dynokore.gst.tenancy.application.period.PeriodPlanner
import com.dynokore.gst.tenancy.application.period.PeriodRecordsChangePublisher
import com.dynokore.gst.tenancy.application.period.PeriodStores
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RegisterBusinessUnitRequest
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** The outbox rows of the channel PeriodRecordsChanged, read as the superuser (they are the stored `data`). */
internal object PeriodRecordsRows {
    fun of(entityRef: String): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select data::text from tenancy.outbox where entity_ref = ? and type = ? order by seq",
                ).use { s ->
                    s.setString(1, entityRef)
                    s.setString(2, PeriodRecordsChangedOutbox.EVENT_TYPE)
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    /** Every outbox row of the tenant, of every event type. */
    fun countOfTenant(tenant: UUID): Long =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select count(*) from tenancy.outbox where tenant_id = ?").use { s ->
                s.setObject(1, tenant)
                s.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }
}

/**
 * PeriodRecordsChanged through the real composition over PostgreSQL (INV-REQ-83, INV-REQ-84; INV-Q-16535 A): the rows
 * the period commands append, in the command's transaction, and the termination of the `tenancy -> aggregation` loop
 * (the ShortCodeUsed handler appends nothing). The criteria INV-AC-113 and INV-AC-153 are proved by the tests of
 * PeriodGenerationTest; this class proves the event that carries their records.
 */
class PeriodRecordsChangedBoundaryTest {
    private val json = JsonMapper.builder().build()

    private fun run(block: (PeriodEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(PeriodEnv(ProfileEnv(context)))
        }
    }

    /** The records in force read back through the period store (listPeriodRecords pages them by a horizon). */
    private fun spans(
        p: PeriodEnv,
        entity: EntityResponse,
    ) = p.records(entity).map { Triple(it.periodRef, it.start, it.end) }

    private fun spansOf(row: String): List<Triple<String, LocalDate, LocalDate>> {
        val periods = json.readTree(row)["periods"]
        return (0 until periods.size()).map { i ->
            val node = periods[i]
            Triple(
                node["periodRef"].asString(),
                LocalDate.parse(node["periodStart"].asString()),
                LocalDate.parse(node["periodEnd"].asString()),
            )
        }
    }

    private fun versionOf(row: String): Long = json.readTree(row)["version"].asLong()

    @Test
    fun `generate appends one row equal to the records read back and a second generate appends none`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                // the initial change generated once; remove the records so that `generate` has them to generate
                assertThat(PeriodRecordsRows.of(entity.entityRef)).hasSize(1)
                BoundaryFixture.asSuperuser { c ->
                    c.prepareStatement("delete from tenancy.period_record where entity_id = ?").use {
                        it.setObject(1, p.entityId(entity))
                        it.executeUpdate()
                    }
                }
                assertThat(p.generation.generate(entity.entityRef)).isInstanceOf(PeriodOutcome.Done::class.java)
                val rows = PeriodRecordsRows.of(entity.entityRef)
                assertThat(rows).hasSize(2)
                assertThat(spansOf(rows.last())).isEqualTo(spans(p, entity))
                assertThat(versionOf(rows.last())).isGreaterThan(versionOf(rows.first()))
                p.generation.generate(entity.entityRef)
                assertThat(PeriodRecordsRows.of(entity.entityRef)).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `a frequency change confirmed through confirmPeriods appends one row beside FilingFrequencyChanged`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val proposal = p.change(entity, Freq.MONTHLY, "2026-02-01").body!!.confirmationPeriod!!
                val rowsBefore = PeriodRecordsRows.of(entity.entityRef)
                assertThat(p.frequencyEvents()).isEmpty()
                p.confirm(entity, proposal.periodRef, "2026-01-01", "2026-01-31", Kind.TRANSITIONAL)
                val rows = PeriodRecordsRows.of(entity.entityRef)
                assertThat(rows).hasSize(rowsBefore.size + 1)
                assertThat(p.frequencyEvents()).hasSize(1)
                assertThat(spansOf(rows.last())).isEqualTo(spans(p, entity))
            }
        }
    }

    @Test
    fun `the final period of a deregistration appends one row whose last period is the final record`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val before = PeriodRecordsRows.of(entity.entityRef)
                p.generation.finalPeriod(entity.entityRef, day("2026-08-15"))
                val rows = PeriodRecordsRows.of(entity.entityRef)
                assertThat(rows).hasSize(before.size + 1)
                val final = p.records(entity).last()
                assertThat(spansOf(rows.last()).last() == Triple(final.periodRef, final.start, final.end)).isTrue()
                assertThat(final.end).isEqualTo(day("2026-08-15"))
                assertThat(spansOf(rows.last())).isEqualTo(spans(p, entity))
            }
        }
    }

    @Test
    fun `a call refused for permission appends none and the outbox is equal before and after`() {
        run { p ->
            p.env.withEntity { tenant, entity ->
                p.register(entity, "2026-01-01")
                val before = PeriodRecordsRows.countOfTenant(tenant.value)
                val rows = PeriodRecordsRows.of(entity.entityRef)
                val (commands, confirm) = unboundAuthorityUseCases(p.env.context)
                val change =
                    commands.changeFilingFrequency(
                        entity.entityRef,
                        FrequencyRequest("QUARTERLY", day("2026-01-01"), "IRAS-1"),
                    )
                assertThat(change).isInstanceOf(FrequencyOutcome.Forbidden::class.java)
                val confirmed =
                    confirm.confirm(
                        entity.entityRef,
                        ConfirmRequest("PRD-X", day("2026-01-01"), day("2026-03-31"), ConfirmKind.FIRST),
                    )
                assertThat(confirmed).isInstanceOf(ConfirmOutcome.Forbidden::class.java)
                assertThat(PeriodRecordsRows.of(entity.entityRef)).isEqualTo(rows)
                assertThat(PeriodRecordsRows.countOfTenant(tenant.value)).isEqualTo(before)
            }
        }
    }

    @Test
    fun `the row is written in the transaction of the command so a forced rollback leaves none`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                BoundaryFixture.asSuperuser { c ->
                    c.prepareStatement("delete from tenancy.period_record where entity_id = ?").use {
                        it.setObject(1, p.entityId(entity))
                        it.executeUpdate()
                    }
                }
                val before = PeriodRecordsRows.of(entity.entityRef)
                val tx = p.env.context.getBean(TransactionRunner::class.java)
                assertThatThrownBy {
                    tx.inTransaction {
                        p.generation.generate(entity.entityRef)
                        error("forced rollback")
                    }
                }.hasMessageContaining("forced rollback")
                assertThat(PeriodRecordsRows.of(entity.entityRef)).isEqualTo(before)
                assertThat(p.records(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `handling ShortCodeUsed sets the flag and leaves the outbox rows of every event type unchanged`() {
        run { p ->
            val env = p.env
            env.withEntity { tenant, entity ->
                val code =
                    "T" +
                        UUID
                            .randomUUID()
                            .toString()
                            .take(8)
                            .uppercase()
                            .replace("-", "A")
                env.api.registerBusinessUnit(
                    UUID.randomUUID(),
                    RegisterBusinessUnitRequest(tenant.value.toString(), entity.entityRef, "Outlet", code),
                )
                val handler = ShortCodeUsedHandler(env.context.getBean(org.jooq.DSLContext::class.java))
                val tx = env.context.getBean(TransactionRunner::class.java)
                val before = PeriodRecordsRows.countOfTenant(tenant.value)
                assertThat(used(tenant.value, code)).isFalse()
                env.inTenant(tenant) { tx.inTransaction { handler.handle(shortCodeUsed(tenant, code)) } }
                assertThat(used(tenant.value, code)).isTrue()
                assertThat(PeriodRecordsRows.countOfTenant(tenant.value)).isEqualTo(before)
            }
        }
    }

    private fun used(
        tenant: UUID,
        code: String,
    ): Boolean =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select used_in_aggregate_number from tenancy.short_code where tenant_id = ? and code = ?",
                ).use { s ->
                    s.setObject(1, tenant)
                    s.setString(2, code)
                    s.executeQuery().use {
                        it.next()
                        it.getBoolean(1)
                    }
                }
        }

    private fun shortCodeUsed(
        tenant: TenantId,
        code: String,
    ) = Envelope(
        id = EventId("ev-${UUID.randomUUID()}"),
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
    )

    /** The two use cases over the production binding of the authority port: no replica, so every check is false. */
    private fun unboundAuthorityUseCases(
        context: org.springframework.context.ConfigurableApplicationContext,
    ): Pair<FilingFrequencyCommands, ConfirmPeriods> {
        val access =
            PeriodAccess(
                ReplicaActionAuthority(null, context.getBean(TenantContext::class.java)),
                context.getBean(CallerSource::class.java),
            )
        val tx = context.getBean(TransactionRunner::class.java)
        val structure = context.getBean(StructureStore::class.java)
        val stores = context.getBean(PeriodStores::class.java)
        val commands =
            FilingFrequencyCommands(
                tx,
                structure,
                context.getBean(PeriodPlanner::class.java),
                context.getBean(PeriodGeneration::class.java),
                access,
                stores,
            )
        val confirm =
            ConfirmPeriods(
                tx,
                structure,
                access,
                stores,
                context.getBean(FilingFrequencyChangePublisher::class.java),
                context.getBean(PeriodRecordsChangePublisher::class.java),
            )
        return commands to confirm
    }
}
