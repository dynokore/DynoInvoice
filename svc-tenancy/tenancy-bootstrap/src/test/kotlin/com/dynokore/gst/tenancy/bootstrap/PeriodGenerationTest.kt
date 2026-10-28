package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.CalendarPackSource
import com.dynokore.gst.tenancy.application.period.ConfirmKind
import com.dynokore.gst.tenancy.application.period.ConfirmOutcome
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.ConfirmRequest
import com.dynokore.gst.tenancy.application.period.ConfirmationState
import com.dynokore.gst.tenancy.application.period.FilingFrequencyChangePublisher
import com.dynokore.gst.tenancy.application.period.FilingFrequencyCommands
import com.dynokore.gst.tenancy.application.period.FrequencyOutcome
import com.dynokore.gst.tenancy.application.period.FrequencyRequest
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.PeriodGeneration
import com.dynokore.gst.tenancy.application.period.PeriodOutcome
import com.dynokore.gst.tenancy.application.period.PeriodPlanner
import com.dynokore.gst.tenancy.application.period.PeriodStores
import com.dynokore.gst.tenancy.application.period.StoredPeriod
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.calendarandperiods.PeriodKind
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext

/**
 * Period generation (INV-REQ-83, INV-REQ-84, INV-REQ-707; INV-Q-9723 A, INV-Q-9725 A, INV-Q-9731 A) through the real
 * application context over PostgreSQL with forced row-level security, the fixture pack and the stores. Test names carry
 * the criterion key.
 */
class PeriodGenerationTest {
    private fun run(block: (PeriodEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(PeriodEnv(ProfileEnv(context)))
        }
    }

    @Test
    fun `INV-AC-105 an entity registered on 15 Feb 2026 gets an irregular unconfirmed first period`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-02-15")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val first = p.records(entity).first()
                assertThat(first.start).isEqualTo(day("2026-02-15"))
                assertThat(first.end).isEqualTo(day("2026-03-31"))
                assertThat(first.kinds).containsExactly(PeriodKind.FIRST)
                assertThat(first.irregular).isTrue()
                assertThat(first.confirmation).isEqualTo(ConfirmationState.UNCONFIRMED)
                assertThat(p.records(entity).drop(1).none { it.irregular }).isTrue()
            }
        }
    }

    @Test
    fun `INV-AC-113 an entity registered on 1 Jan 2026 gets explicit dates, form F5 and the filing due dates`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val records = p.records(entity)
                assertThat(records.take(2).map { listOf(it.start, it.end, it.returnForm, it.dueDate) })
                    .containsExactly(
                        listOf(day("2026-01-01"), day("2026-03-31"), "F5", day("2026-04-30")),
                        listOf(day("2026-04-01"), day("2026-06-30"), "F5", day("2026-07-31")),
                    )
                assertThat(records.none { it.irregular }).isTrue()
            }
        }
    }

    @Test
    fun `INV-AC-9542 generation run again without a change leaves every stored record equal by every field`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val before = p.rows(entity)
                val recordsBefore = p.records(entity)
                val again = p.generation.generate(entity.entityRef)
                assertThat(again).isInstanceOf(PeriodOutcome.Done::class.java)
                assertThat(p.records(entity)).isEqualTo(recordsBefore).hasSameSizeAs(recordsBefore)
                assertThat(p.rows(entity)).isEqualTo(before)
            }
        }
    }

    @Test
    fun `INV-AC-9546 membership of a date follows the stored start and end of the record`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val proposal = p.change(entity, Freq.MONTHLY, "2026-02-01").body!!.confirmationPeriod!!
                p.confirm(entity, proposal.periodRef, "2026-01-01", "2026-01-31", Kind.TRANSITIONAL)
                val stored = p.records(entity).first { PeriodKind.TRANSITIONAL in it.kinds }
                val shorter: StoredPeriod = stored.copy(end = day("2026-01-30"))
                val dates = listOf(day("2026-01-31"), day("2026-02-01"))
                assertThat(stored.start).isEqualTo(day("2026-01-01"))
                assertThat(dates.map { it in stored }).containsExactly(true, false)
                assertThat(dates.map { it in shorter }).containsExactly(false, false)
            }
        }
    }

    @Test
    fun `INV-AC-153 the final period of a registration end on 15 Aug 2026 is F8, kind FINAL and unconfirmed`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val outcome = p.generation.finalPeriod(entity.entityRef, day("2026-08-15"))
                assertThat(outcome).isInstanceOf(PeriodOutcome.Done::class.java)
                val records = p.records(entity)
                val final = records.last()
                assertThat(listOf(final.start, final.end, final.returnForm))
                    .containsExactly(day("2026-07-01"), day("2026-08-15"), "F8")
                assertThat(final.kinds).containsExactly(PeriodKind.FINAL)
                assertThat(final.confirmation).isEqualTo(ConfirmationState.UNCONFIRMED)
                assertThat(records.map { it.start }).containsExactly(
                    day("2026-01-01"),
                    day("2026-04-01"),
                    day("2026-07-01"),
                )
                // a repeat changes nothing, and the records after the end date are kept, marked superseded
                val rows = p.rows(entity)
                p.generation.finalPeriod(entity.entityRef, day("2026-08-15"))
                assertThat(p.rows(entity)).isEqualTo(rows)
                assertThat(rows.count { it.contains("\"record_state\":\"SUPERSEDED\"") }).isGreaterThan(0)
            }
        }
    }

    @Test
    fun `fail closed with nothing stored when no registration date is recorded`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.actAs(entity, "entity.admin")
                val refusal = assertThatThrownBy { p.change(entity, Freq.QUARTERLY, "2026-01-01") }
                refusal.isInstanceOf(ConstraintRefusal::class.java)
                assertThat(
                    (refusal.actual() as ConstraintRefusal).detail,
                ).isEqualTo("registration start date not recorded")
                assertThat(p.rows(entity)).isEmpty()
                assertThat(p.generation.generate(entity.entityRef)).isEqualTo(
                    PeriodOutcome.Refused("registration start date not recorded"),
                )
            }
        }
    }

    @Test
    fun `fail closed with nothing stored when the pack has no windows for the frequency code`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                p.calendar.pack = FixturePack.pack(setOf(FixturePack.QUARTERLY))
                val refusal = assertThatThrownBy { p.change(entity, Freq.MONTHLY, "2026-01-01") }
                refusal.isInstanceOf(ConstraintRefusal::class.java)
                assertThat((refusal.actual() as ConstraintRefusal).detail).contains("MONTHLY")
                assertThat(p.rows(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `fail closed with nothing stored for an initial entry effective the day after the registration start`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-01-01")
                val refusal = assertThatThrownBy { p.change(entity, Freq.QUARTERLY, "2026-01-02") }
                refusal.isInstanceOf(ApiRefusal::class.java)
                assertThat((refusal.actual() as ApiRefusal).code).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(p.rows(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `the production profile starts with the calendar pack port unbound and refuses the initial frequency`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("spring.profiles.active=prod")
            .run { context: AssertableWebApplicationContext ->
                context.startupFailure?.let { throw it }
                assertThat(context.getBeansOfType(CalendarPackSource::class.java)).isEmpty()
                assertThat(context.getBeansOfType(PeriodGeneration::class.java)).hasSize(1)
                val env = ProfileEnv(context)
                val period = PeriodEnv(env)
                env.withEntity { _, entity ->
                    period.register(entity, "2026-01-01")
                    val refusal = assertThatThrownBy { period.change(entity, Freq.QUARTERLY, "2026-01-01") }
                    refusal.isInstanceOf(ConstraintRefusal::class.java)
                    assertThat((refusal.actual() as ConstraintRefusal).detail).isEqualTo("calendar pack unknown")
                }
            }
    }

    @Test
    fun `the production profile with the authority port unbound starts and both operations answer 403`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("spring.profiles.active=prod")
            .run { context: AssertableWebApplicationContext ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                val period = PeriodEnv(env)
                val (commands, confirm) = unboundAuthorityUseCases(context)
                env.withEntity { _, entity ->
                    period.register(entity, "2026-01-01")
                    val rows = period.rows(entity)
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
                    assertThat(period.rows(entity)).isEqualTo(rows).isEmpty()
                }
            }
    }

    /** The two use cases over the production binding of the authority port: no replica is bound, every check is false. */
    private fun unboundAuthorityUseCases(
        context: AssertableWebApplicationContext,
    ): Pair<FilingFrequencyCommands, ConfirmPeriods> {
        val access =
            PeriodAccess(
                ReplicaActionAuthority(null, context.getBean(TenantContext::class.java)),
                context.getBean(CallerSource::class.java),
            )
        val tx = context.getBean(TransactionRunner::class.java)
        val structure = context.getBean(StructureStore::class.java)
        val stores = context.getBean(PeriodStores::class.java)
        return FilingFrequencyCommands(
            tx,
            structure,
            context.getBean(PeriodPlanner::class.java),
            context.getBean(PeriodGeneration::class.java),
            access,
            stores,
        ) to ConfirmPeriods(tx, structure, access, stores, context.getBean(FilingFrequencyChangePublisher::class.java))
    }
}
