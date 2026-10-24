package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.application.period.ConfirmationState
import com.dynokore.gst.tenancy.application.period.HistoryState
import com.dynokore.gst.tenancy.application.period.ItemState
import com.dynokore.gst.tenancy.application.period.RaisedCode
import com.dynokore.gst.tenancy.application.period.StoredPeriod
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponse
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponseConfirmationPeriod
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.FrequencyChange
import com.dynokore.platform.calendarandperiods.FrequencyHistory
import com.dynokore.platform.calendarandperiods.PeriodKind
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * changeFilingFrequency and confirmPeriods (INV-REQ-688, INV-REQ-705, INV-REQ-708, INV-REQ-84; INV-Q-9727 A,
 * INV-Q-9729 A, INV-Q-9733 A) through the real application context over PostgreSQL with forced row-level security.
 * A frequency change stores a PENDING entry, the proposal and the raised items, and changes nothing else until
 * confirmPeriods applies it. Test names carry the criterion key.
 */
class FilingFrequencyChangeTest {
    private fun run(block: (PeriodEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(PeriodEnv(ProfileEnv(context)))
        }
    }

    /** A quarterly filer registered on 1 Jan 2026 with the period records generated (to the end of the pack). */
    private fun quarterly(
        p: PeriodEnv,
        block: (EntityResponse) -> Unit,
    ) = p.env.withEntity { _, entity ->
        p.register(entity, "2026-01-01")
        p.change(entity, Freq.QUARTERLY, "2026-01-01")
        block(entity)
    }

    private fun propose(
        p: PeriodEnv,
        entity: EntityResponse,
        frequency: Freq = Freq.MONTHLY,
        from: String = "2026-02-01",
    ): FilingFrequencyResponse = p.change(entity, frequency, from).body!!

    private fun confirmProposal(
        p: PeriodEnv,
        entity: EntityResponse,
        proposal: FilingFrequencyResponse,
    ) = proposal.confirmationPeriod!!.let {
        p.confirm(entity, it.periodRef, it.periodStart.toString(), it.periodEnd.toString(), Kind.valueOf(it.kind.name))
    }

    private fun confirmAs(
        p: PeriodEnv,
        entity: EntityResponse,
        period: FilingFrequencyResponseConfirmationPeriod,
        kind: Kind,
    ) = p.confirm(entity, period.periodRef, period.periodStart.toString(), period.periodEnd.toString(), kind)

    private fun seededRecord(
        ref: String,
        start: String,
        end: String,
        form: String,
        kind: PeriodKind,
    ) = StoredPeriod(ref, day(start), day(end), form, day(end).plusMonths(2), setOf(kind))

    @Test
    fun `INV-AC-9544 one change to monthly raises PER-004 and PER-005 once each and stores the transitional end`() {
        run { p ->
            quarterly(p) { entity ->
                assertThat(p.items(entity, ItemState.OPEN)).isEmpty()
                val proposal = propose(p, entity)
                assertThat(proposal.status).isEqualTo(FilingFrequencyResponse.Status.PENDING_CONFIRMATION)
                assertThat(proposal.raisedCodes).containsExactly("PER-004", "PER-005")
                assertThat(p.items(entity, ItemState.OPEN))
                    .isEqualTo(mapOf(RaisedCode.PER_004 to 1, RaisedCode.PER_005 to 1))
                confirmProposal(p, entity, proposal)
                val transitional = p.records(entity).first { PeriodKind.TRANSITIONAL in it.kinds }
                assertThat(transitional.start).isEqualTo(day("2026-01-01"))
                assertThat(transitional.end).isEqualTo(day("2026-01-31")).isNotEqualTo(day("2026-03-31"))
                assertThat(p.items(entity, ItemState.RESOLVED))
                    .isEqualTo(mapOf(RaisedCode.PER_004 to 1, RaisedCode.PER_005 to 1))
            }
        }
    }

    @Test
    fun `INV-AC-9547 INV-AC-1820 before the confirmation no period record changes and no event is published`() {
        run { p ->
            quarterly(p) { entity ->
                val recordsBefore = p.records(entity)
                val rowsBefore = p.rows(entity)
                val events = p.frequencyEvents().size
                val proposal = propose(p, entity)
                assertThat(p.records(entity)).isEqualTo(recordsBefore)
                assertThat(p.records(entity).map { it.start to it.end })
                    .isEqualTo(recordsBefore.map { it.start to it.end })
                assertThat(p.frequencyEvents()).hasSize(events)
                assertThat(BoundaryFixture.outboxRowsMentioning("FilingFrequencyChanged")).isZero()
                assertThat(p.items(entity, ItemState.OPEN).keys)
                    .containsExactlyInAnyOrder(RaisedCode.PER_004, RaisedCode.PER_005)
                // the period rows read after the call equal those before it; the only new rows are the change's own
                val periodRowsAfter = p.rows(entity).filter { it.contains("\"record_state\"") }
                assertThat(periodRowsAfter).isEqualTo(rowsBefore.filter { it.contains("\"record_state\"") })
                confirmProposal(p, entity, proposal)
                assertThat(p.frequencyEvents()).hasSize(events + 1)
            }
        }
    }

    @Test
    fun `INV-AC-152 INV-AC-154 the change regenerates nothing silently and the transitional record is flagged`() {
        run { p ->
            quarterly(p) { entity ->
                val before = p.records(entity)
                val proposal = propose(p, entity)
                assertThat(proposal.confirmationPeriod!!.kind)
                    .isEqualTo(FilingFrequencyResponseConfirmationPeriod.Kind.TRANSITIONAL)
                assertThat(proposal.confirmationPeriod!!.periodEnd).isEqualTo(day("2026-01-31"))
                assertThat(p.records(entity)).isEqualTo(before)
                assertThat(p.items(entity, ItemState.OPEN).keys).contains(RaisedCode.PER_004, RaisedCode.PER_005)
                confirmProposal(p, entity, proposal)
                val after = p.records(entity)
                val transitional = after.first()
                assertThat(transitional.kinds).containsExactly(PeriodKind.TRANSITIONAL)
                assertThat(transitional.irregular).isTrue()
                assertThat(transitional.confirmation).isEqualTo(ConfirmationState.CONFIRMED)
                assertThat(after.drop(1).first().start).isEqualTo(day("2026-02-01"))
                assertThat(after.none { it.start == day("2026-01-01") && it.end == day("2026-03-31") }).isTrue()
            }
        }
    }

    @Test
    fun `INV-AC-751 the transitional period takes its start and end from the stored record`() {
        run { p ->
            quarterly(p) { entity ->
                confirmProposal(p, entity, propose(p, entity))
                val records = p.records(entity)
                val transitional = records.first { PeriodKind.TRANSITIONAL in it.kinds }
                assertThat(listOf(day("2026-01-15"), day("2026-01-31")).all { it in transitional }).isTrue()
                assertThat(day("2026-02-01") in transitional).isFalse()
                assertThat(records.first { day("2026-02-01") in it }.start).isEqualTo(day("2026-02-01"))
                assertThat(transitional.end).isNotEqualTo(day("2026-03-31"))
            }
        }
    }

    @Test
    fun `INV-AC-9545 INV-AC-1748 the history returns the frequency in force on the transaction date`() {
        run { p ->
            quarterly(p) { entity ->
                val proposal = propose(p, entity, Freq.MONTHLY, "2026-07-01")
                assertThat(proposal.raisedCodes).containsExactly("PER-004")
                assertThat(proposal.confirmationPeriod!!.kind)
                    .isEqualTo(FilingFrequencyResponseConfirmationPeriod.Kind.FREQUENCY_CHANGE)
                confirmProposal(p, entity, proposal)
                val history =
                    FrequencyHistory.of(
                        p.entries(entity).filter { it.state == HistoryState.ACTIVE }.map {
                            FrequencyChange(it.effectiveFrom, FilingFrequency(it.frequency))
                        },
                    )
                val read = listOf("2026-05-15", "2026-06-30", "2026-07-01").map { history.at(day(it))!!.code }
                assertThat(read).containsExactly("QUARTERLY", "QUARTERLY", "MONTHLY")
                assertThat(read.take(2)).doesNotContain("MONTHLY")
                // a document dated 15 May belongs to the quarter containing it, by the stored records
                assertThat(p.records(entity).first { day("2026-05-15") in it }.let { it.start to it.end })
                    .isEqualTo(day("2026-04-01") to day("2026-06-30"))
            }
        }
    }

    @Test
    fun `INV-AC-9543 confirming the FIRST record leaves FINAL and TRANSITIONAL unconfirmed, a regular one unflagged`() {
        run { p ->
            p.env.withEntity { _, entity ->
                p.register(entity, "2026-02-15")
                p.change(entity, Freq.QUARTERLY, "2026-01-01")
                val stored = p.records(entity)
                val first = stored.first()
                val regular = stored[1]
                val seeded =
                    listOf(
                        seededRecord("PRD-FINAL", "2028-01-01", "2028-01-15", "F8", PeriodKind.FINAL),
                        seededRecord("PRD-TRANS", "2028-02-01", "2028-02-29", "F5", PeriodKind.TRANSITIONAL),
                    )
                seeded.forEach { p.insertRecord(entity, it) }
                val before = p.records(entity).associateBy { it.periodRef }
                p.actAs(entity, "entity.admin")
                p.confirm(entity, first.periodRef, first.start.toString(), first.end.toString(), Kind.FIRST)
                val after = p.records(entity).associateBy { it.periodRef }
                assertThat(after.getValue(first.periodRef).confirmation).isEqualTo(ConfirmationState.CONFIRMED)
                for (ref in listOf("PRD-FINAL", "PRD-TRANS")) {
                    assertThat(after.getValue(ref)).isEqualTo(before.getValue(ref))
                    assertThat(after.getValue(ref).confirmation).isEqualTo(ConfirmationState.UNCONFIRMED)
                    assertThat(after.getValue(ref).irregular).isTrue()
                }
                assertThat(after.getValue(regular.periodRef)).isEqualTo(before.getValue(regular.periodRef))
                assertThat(after.getValue(regular.periodRef).irregular).isFalse()
                // a record already confirmed with the same values replays and publishes nothing
                val rows = p.rows(entity)
                p.confirm(entity, first.periodRef, first.start.toString(), first.end.toString(), Kind.FIRST)
                assertThat(p.rows(entity)).isEqualTo(rows)
                assertThat(p.frequencyEvents()).isEmpty()
            }
        }
    }

    @Test
    fun `the same change twice raises each code once and returns the recorded response`() {
        run { p ->
            quarterly(p) { entity ->
                val first = propose(p, entity)
                val rows = p.rows(entity)
                val second = propose(p, entity)
                assertThat(second).isEqualTo(first)
                assertThat(p.rows(entity)).isEqualTo(rows)
                assertThat(p.items(entity, ItemState.OPEN).values).containsOnly(1)
            }
        }
    }

    @Test
    fun `a second change with another effectiveFrom while one is pending is refused with nothing changed`() {
        run { p ->
            quarterly(p) { entity ->
                propose(p, entity)
                val rows = p.rows(entity)
                assertThatThrownBy { p.change(entity, Freq.MONTHLY, "2026-03-01") }
                    .isInstanceOf(ConstraintRefusal::class.java)
                assertThat(p.rows(entity)).isEqualTo(rows)
            }
        }
    }

    @Test
    fun `different values for the pending date supersede the proposal and raise new items`() {
        run { p ->
            quarterly(p) { entity ->
                val first = propose(p, entity)
                val second = propose(p, entity, Freq.MONTHLY, "2026-02-01").let { it }
                assertThat(second).isEqualTo(first)
                p.change(entity, Freq.MONTHLY, "2026-02-01", iras = "IRAS-2")
                assertThat(p.items(entity, ItemState.SUPERSEDED).values).containsOnly(1)
                assertThat(p.items(entity, ItemState.OPEN))
                    .isEqualTo(mapOf(RaisedCode.PER_004 to 1, RaisedCode.PER_005 to 1))
            }
        }
    }

    @Test
    fun `TRANSITIONAL for a proposal whose kind is FREQUENCY_CHANGE is refused with nothing changed`() {
        run { p ->
            quarterly(p) { entity ->
                val proposal = propose(p, entity, Freq.MONTHLY, "2026-07-01").confirmationPeriod!!
                val rows = p.rows(entity)
                assertThatThrownBy {
                    confirmAs(p, entity, proposal, Kind.TRANSITIONAL)
                }.isInstanceOf(ConstraintRefusal::class.java)
                assertThat(p.rows(entity)).isEqualTo(rows)
                assertThat(p.frequencyEvents()).isEmpty()
            }
        }
    }

    @Test
    fun `a periodRef of another entity is a 404 and an end date one day off is refused`() {
        run { p ->
            quarterly(p) { entity ->
                val proposal = propose(p, entity).confirmationPeriod!!
                p.env.withEntity { _, other ->
                    p.actAs(other, "entity.admin")
                    val foreign =
                        assertThatThrownBy {
                            p.confirm(other, proposal.periodRef, "2026-01-01", "2026-01-31", Kind.TRANSITIONAL)
                        }
                    foreign.isInstanceOf(ApiRefusal::class.java)
                    assertThat((foreign.actual() as ApiRefusal).code).isEqualTo(ClosedErrorCode.NOT_FOUND)
                }
                val rows = p.rows(entity)
                assertThatThrownBy {
                    p.confirm(
                        entity,
                        proposal.periodRef,
                        "2026-01-01",
                        "2026-01-30",
                        Kind.TRANSITIONAL,
                    )
                }.isInstanceOf(ConstraintRefusal::class.java)
                assertThat(p.rows(entity)).isEqualTo(rows)
                val record = p.records(entity).first()
                val oneDayOff = record.end.plusDays(1).toString()
                assertThatThrownBy {
                    p.confirm(
                        entity,
                        record.periodRef,
                        record.start.toString(),
                        oneDayOff,
                        Kind.FIRST,
                    )
                }.isInstanceOf(ConstraintRefusal::class.java)
            }
        }
    }

    @Test
    fun `a caller holding neither entity admin nor tax approver is refused 403, a tax approver may confirm`() {
        run { p ->
            quarterly(p) { entity ->
                val proposal = propose(p, entity).confirmationPeriod!!
                val rows = p.rows(entity)
                for (roles in listOf(emptyList(), listOf("tenant.admin"), listOf("entity.admin2"))) {
                    p.actAs(entity, *roles.toTypedArray())
                    val refusal =
                        assertThatThrownBy {
                            p.confirm(entity, proposal.periodRef, "2026-01-01", "2026-01-31", Kind.TRANSITIONAL)
                        }
                    refusal.isInstanceOf(ApiRefusal::class.java)
                    assertThat((refusal.actual() as ApiRefusal).code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                    assertThat(p.rows(entity)).isEqualTo(rows)
                }
                p.actAs(entity, "tax.approver")
                p.confirm(entity, proposal.periodRef, "2026-01-01", "2026-01-31", Kind.TRANSITIONAL)
                assertThat(p.frequencyEvents()).hasSize(1)
            }
        }
    }

    private fun codeOf(block: () -> Unit): ClosedErrorCode {
        val refusal = assertThatThrownBy { block() }
        refusal.isInstanceOf(ApiRefusal::class.java)
        return (refusal.actual() as ApiRefusal).code
    }

    @Test
    fun `a repeat of the same change returns the recorded response before and after the apply`() {
        run { p ->
            quarterly(p) { entity ->
                val first = propose(p, entity)
                assertThat(propose(p, entity)).isEqualTo(first)
                val rows = p.rows(entity)
                confirmProposal(p, entity, first)
                val events = p.frequencyEvents()
                val after = propose(p, entity)
                assertThat(after).isEqualTo(first)
                assertThat(after.status).isEqualTo(FilingFrequencyResponse.Status.PENDING_CONFIRMATION)
                assertThat(after.raisedCodes).containsExactly("PER-004", "PER-005")
                assertThat(p.frequencyEvents()).isEqualTo(events)
                assertThat(p.items(entity, ItemState.RESOLVED).values).containsOnly(1)
                assertThat(rows).isNotEmpty()
            }
        }
    }

    @Test
    fun `changeFilingFrequency needs filing-frequency change and refuses every other caller with nothing stored`() {
        run { p ->
            quarterly(p) { entity ->
                val rows = p.rows(entity)
                val denied =
                    listOf(listOf("tax.approver"), listOf("tenant.admin"), emptyList(), listOf("entity.admin2"))
                for (roles in denied) {
                    p.actAs(entity, *roles.toTypedArray())
                    assertThat(codeOf { p.change(entity, Freq.MONTHLY, "2026-02-01") })
                        .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                    assertThat(p.rows(entity)).isEqualTo(rows)
                }
                // the permission on another entity only
                p.env.withEntity { _, other ->
                    p.actAs(other, "entity.admin")
                    assertThat(codeOf { p.change(entity, Freq.MONTHLY, "2026-02-01") })
                        .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                    assertThat(p.rows(entity)).isEqualTo(rows)
                }
                assertThat(p.people.asked.toSet()).isEqualTo(setOf("filing-frequency.change"))
                // look-alike keys are not the catalogue key, even for the holder
                p.actAs(entity, "entity.admin")
                for (key in listOf("filing-frequency.change ", "Filing-frequency.change", "period.confirm")) {
                    assertThat(p.people.holds(PeriodEnv.PERIOD_CALLER, entity.entityRef, key)).describedAs(key).isFalse()
                }
                assertThat(p.people.holds(PeriodEnv.PERIOD_CALLER, entity.entityRef, "filing-frequency.change")).isTrue()
                assertThat(propose(p, entity).status).isEqualTo(FilingFrequencyResponse.Status.PENDING_CONFIRMATION)
            }
        }
    }

    @Test
    fun `confirmPeriods asks period confirm, and an entity that does not exist is 404 before the permission`() {
        run { p ->
            quarterly(p) { entity ->
                val proposal = propose(p, entity).confirmationPeriod!!
                p.actAs(entity, "tenant.admin")
                p.people.asked.clear()
                assertThat(codeOf { confirmAs(p, entity, proposal, Kind.TRANSITIONAL) })
                    .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                assertThat(p.people.asked.toSet()).isEqualTo(setOf("period.confirm"))
                for (key in listOf("period.confirm ", "Period.confirm", "filing-frequency.change")) {
                    p.actAs(entity, "entity.admin")
                    assertThat(p.people.holds(PeriodEnv.PERIOD_CALLER, entity.entityRef, key)).describedAs(key).isFalse()
                }
                p.people.asked.clear()
                val missing = entity.copy(entityRef = "ENT-DOES-NOT-EXIST")
                p.actAs(missing, "tenant.admin")
                assertThat(codeOf { p.change(missing, Freq.MONTHLY, "2026-02-01") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
                assertThat(codeOf { confirmAs(p, missing, proposal, Kind.TRANSITIONAL) })
                    .isEqualTo(ClosedErrorCode.NOT_FOUND)
                assertThat(p.people.asked).isEmpty()
            }
        }
    }
}
