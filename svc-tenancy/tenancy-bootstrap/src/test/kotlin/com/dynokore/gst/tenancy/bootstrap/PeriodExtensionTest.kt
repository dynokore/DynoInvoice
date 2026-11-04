package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * recordExtension (INV-REQ-81, INV-REQ-86): the only per-period change of a due date. The permission is decided per
 * request through the authority port (`period-extension.record`), never by a role name.
 */
class PeriodExtensionTest {
    private fun withPeriods(block: (PeriodEnv, EntityResponse) -> Unit) =
        runPeriods { period, entity ->
            period.insertRecord(entity, record("2026Q1", "2026-01-01", "2026-03-31", "2026-04-30"))
            period.insertRecord(entity, record("2026Q2", "2026-04-01", "2026-06-30", "2026-07-31"))
            period.actAs(entity, "tax.approver")
            block(period, entity)
        }

    @Test
    fun `INV-AC-16069 INV-AC-106 a recorded extension keeps reference, evidence and both dates`() {
        withPeriods { period, entity ->
            val response = period.extend(entity, "2026Q1", extension("2026-05-14"))
            assertThat(response.statusCode.value()).isEqualTo(201)
            assertThat(response.body!!.newDueDate.toString()).isEqualTo("2026-05-14")
            val stored = periodJson.readTree(period.table(entity, "period_extension", "recorded_at").single())
            assertThat(stored["original_filing_due_date"].asString()).isEqualTo("2026-04-30")
            assertThat(stored["new_filing_due_date"].asString()).isEqualTo("2026-05-14")
            assertThat(stored["iras_reference"].asString()).isEqualTo("IRAS-EXT-2026-118")
            assertThat(stored["evidence_ref"].asString()).isEqualTo("DOC-9911")
            assertThat(stored["recorded_by"].asString()).isEqualTo(PeriodEnv.PERIOD_CALLER)
            val q1 = period.listed(entity).single { it.periodRef == "2026Q1" }
            assertThat(q1.filingDueDate.toString()).isEqualTo("2026-05-14")
            assertThat(q1.extensionGranted).isTrue()
            val extended = period.events(PERIOD_EXTENDED).single()
            assertThat(extended["period"].asString()).isEqualTo("2026Q1")
            assertThat(extended["irasReference"].asString()).isEqualTo("IRAS-EXT-2026-118")
            assertThat(extended["target"].asString()).isEqualTo("FILING_DUE_DATE")
            assertThat(extended["newFilingDueDate"].asString()).isEqualTo("2026-05-14")
            assertThat(extended["scope"]["kind"].asString()).isEqualTo("PERIOD")
        }
    }

    @Test
    fun `INV-AC-197 INV-AC-115 an extended due date moves that period only and is published`() {
        withPeriods { period, entity ->
            period.extend(entity, "2026Q1", extension("2026-05-14"))
            val changed = period.events(PERIOD_DUE_DATE_CHANGED).single()
            assertThat(changed["period"].asString()).isEqualTo("2026Q1")
            assertThat(changed["oldFilingDueDate"].asString()).isEqualTo("2026-04-30")
            assertThat(changed["newFilingDueDate"].asString()).isEqualTo("2026-05-14")
            assertThat(changed["cause"]["irasReference"].asString()).isEqualTo("IRAS-EXT-2026-118")
            val records = period.listed(entity).associateBy { it.periodRef }
            assertThat(records.getValue("2026Q2").filingDueDate.toString()).isEqualTo("2026-07-31")
            assertThat(records.getValue("2026Q2").extensionGranted).isFalse()
        }
    }

    @Test
    fun `INV-AC-155 INV-AC-107 an extension without reference or evidence is refused`() {
        withPeriods { period, entity ->
            val rowsBefore = period.rows(entity)
            listOf(
                extension("2026-05-14", iras = ""),
                extension("2026-05-14", iras = "  "),
                extension("2026-05-14", evidence = ""),
                extension("2026-05-14", evidence = " "),
            ).forEach { request ->
                assertThat(refusalOf { period.extend(entity, "2026Q1", request) }).describedAs("$request").isNotNull()
            }
            assertThat(period.table(entity, "period_extension", "recorded_at")).isEmpty()
            assertThat(period.rows(entity)).isEqualTo(rowsBefore)
            assertThat(
                period
                    .listed(entity)
                    .first()
                    .filingDueDate
                    .toString(),
            ).isEqualTo("2026-04-30")
            assertThat(period.events(PERIOD_EXTENDED)).isEmpty()
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).isEmpty()
        }
    }

    @Test
    fun `the catalogue cells of period-extension record are read through RolePermissions`() {
        assertThat(PeriodPermissions.EXTENSION_RECORD).isEqualTo("period-extension.record")
        listOf("period-extensions.record", "period-extension.recorded", "Period-extension.record").forEach {
            assertThat(PeriodPermissions.EXTENSION_RECORD).describedAs(it).isNotEqualTo(it)
            DeploymentForm.entries.forEach { form ->
                assertThat(RolePermissions.cells(form).values.any { cell -> it in cell }).describedAs(it).isFalse()
            }
        }
        // a cell that is absent is DENIED; only PERMITTED and WITHIN_SCOPE cells are listed
        DeploymentForm.entries.forEach { form ->
            val cells = RolePermissions.cells(form)
            val holders = cells.filterValues { it[PeriodPermissions.EXTENSION_RECORD] != null }
            assertThat(holders.keys).describedAs(form.name).containsExactly("tax.approver")
            val approver = holders.getValue("tax.approver")
            assertThat(approver[PeriodPermissions.EXTENSION_RECORD]).isEqualTo(RoleAllowance.PERMITTED)
            listOf("entity.admin", "tenant.admin", "tax.operator").forEach {
                assertThat(cells[it]?.get(PeriodPermissions.EXTENSION_RECORD)).describedAs("${form.name} $it").isNull()
            }
        }
    }

    @Test
    fun `INV-AC-156 only the holder of the extension permission may record an extension`() {
        withPeriods { period, entity ->
            for (roles in listOf(arrayOf("tax.operator"), arrayOf("entity.admin"), emptyArray())) {
                period.actAs(entity, *roles)
                assertThat(refusalOf { period.extend(entity, "2026Q1", extension("2026-05-14")) })
                    .describedAs(roles.joinToString())
                    .isNotNull()
            }
            assertThat(period.people.asked.toSet()).isEqualTo(setOf("period-extension.record"))
            assertThat(period.table(entity, "period_extension", "recorded_at")).isEmpty()
            assertThat(
                period
                    .listed(entity)
                    .first()
                    .filingDueDate
                    .toString(),
            ).isEqualTo("2026-04-30")
            assertThat(period.events(PERIOD_EXTENDED)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-16067 INV-AC-107 another period stays unchanged and only the extended one is named`() {
        withPeriods { period, entity ->
            period.extend(entity, "2026Q1", extension("2026-05-14"))
            val records = period.listed(entity).associateBy { it.periodRef }
            assertThat(records.getValue("2026Q1").filingDueDate.toString()).isEqualTo("2026-05-14")
            assertThat(records.getValue("2026Q2").filingDueDate.toString()).isEqualTo("2026-07-31")
            assertThat(period.events(PERIOD_EXTENDED).map { it["irasReference"].asString() })
                .containsExactly("IRAS-EXT-2026-118")
            val named = period.events(PERIOD_EXTENDED) + period.events(PERIOD_DUE_DATE_CHANGED)
            assertThat(named.map { it["period"].asString() }).doesNotContain("2026Q2")
        }
    }

    private fun shape(
        target: Tgt = Tgt.SUBMISSION_DUE_DATE,
        submission: String? = null,
        scope: Scope? = null,
    ) = Req(day("2026-05-14"), "R-1", "E-1", target, submission?.let(::day), scope)

    @Test
    fun `a broken rule of the extension request shape is a 400 that stores nothing and publishes nothing`() {
        withPeriods { period, entity ->
            val docs = Scope(ScopeKind.DOCUMENT_SET, listOf(UUID.randomUUID()))
            val noDocs = Scope(ScopeKind.DOCUMENT_SET, emptyList())
            val backwards = Scope(ScopeKind.DATE_RANGE, null, day("2026-04-10"), day("2026-04-01"))
            val noEnd = Scope(ScopeKind.DATE_RANGE, null, day("2026-04-01"), null)
            val bad =
                mapOf(
                    "BOTH without newSubmissionDueDate" to shape(Tgt.BOTH),
                    "filing target with a submission date" to shape(Tgt.FILING_DUE_DATE, "2026-05-20"),
                    "filing target with scope DOCUMENT_SET" to shape(Tgt.FILING_DUE_DATE, scope = docs),
                    "DOCUMENT_SET without documentRefs" to shape(scope = noDocs),
                    "DATE_RANGE with dateFrom after dateTo" to shape(scope = backwards),
                    "DATE_RANGE without dateTo" to shape(scope = noEnd),
                )
            bad.forEach { (what, request) ->
                val refusal = refusalOf { period.extend(entity, "2026Q1", request) }
                assertThat(refusal).describedAs(what).isInstanceOf(ApiRefusal::class.java)
            }
            assertThat(period.table(entity, "period_extension", "recorded_at")).isEmpty()
            assertThat(period.events(PERIOD_EXTENDED)).isEmpty()
            assertThat(
                period
                    .listed(entity)
                    .first()
                    .filingDueDate
                    .toString(),
            ).isEqualTo("2026-04-30")
        }
    }

    @Test
    fun `a submission-only extension is recorded and published but moves no filing due date`() {
        withPeriods { period, entity ->
            val range = Scope(ScopeKind.DATE_RANGE, null, day("2026-04-01"), day("2026-04-03"))
            val request = Req(day("2026-04-20"), "R-2", "E-2", Tgt.SUBMISSION_DUE_DATE, null, range)
            val response = period.extend(entity, "2026Q1", request)
            assertThat(response.body!!.target!!.value).isEqualTo("SUBMISSION_DUE_DATE")
            val event = period.events(PERIOD_EXTENDED).single()
            assertThat(event["newSubmissionDueDate"].asString()).isEqualTo("2026-04-20")
            assertThat(event.has("newFilingDueDate")).isFalse()
            assertThat(event["scope"]["dateFrom"].asString()).isEqualTo("2026-04-01")
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).isEmpty()
            assertThat(
                period
                    .listed(entity)
                    .first()
                    .filingDueDate
                    .toString(),
            ).isEqualTo("2026-04-30")
        }
    }

    @Test
    fun `target BOTH moves the filing date and a repeat of the same request changes nothing`() {
        withPeriods { period, entity ->
            val request = Req(day("2026-05-14"), "R-3", "E-3", Tgt.BOTH, day("2026-05-21"))
            period.extend(entity, "2026Q1", request)
            period.extend(entity, "2026Q1", request)
            val event = period.events(PERIOD_EXTENDED).single()
            assertThat(event["newFilingDueDate"].asString()).isEqualTo("2026-05-14")
            assertThat(event["newSubmissionDueDate"].asString()).isEqualTo("2026-05-21")
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).hasSize(1)
            assertThat(period.table(entity, "period_extension", "recorded_at")).hasSize(1)
            val other = Req(day("2026-06-01"), "R-3", "E-3", Tgt.BOTH, day("2026-06-02"))
            assertThat(refusalOf { period.extend(entity, "2026Q1", other) }).isNotNull()
            assertThat(period.table(entity, "period_extension", "recorded_at")).hasSize(1)
        }
    }

    @Test
    fun `an unknown period or entity is 404 and stores nothing`() {
        withPeriods { period, entity ->
            assertThat(refusalOf { period.extend(entity, "2026Q9", extension("2026-05-14")) }).isNotNull()
            assertThat(period.table(entity, "period_extension", "recorded_at")).isEmpty()
        }
    }
}
