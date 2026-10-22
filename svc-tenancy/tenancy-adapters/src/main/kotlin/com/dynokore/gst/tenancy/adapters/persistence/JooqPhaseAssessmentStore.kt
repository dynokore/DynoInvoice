package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PHASE_ASSESSMENT
import com.dynokore.gst.tenancy.application.status.PhaseAssessmentStore
import com.dynokore.gst.tenancy.application.status.StoredAssessment
import com.dynokore.gst.tenancy.domain.GstReturn
import com.dynokore.gst.tenancy.domain.PhaseAssessment
import com.dynokore.gst.tenancy.domain.PhaseInputs
import com.dynokore.gst.tenancy.domain.PhaseWindow
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The assessPhase results of the bound tenant's entities (jOOQ only, CS-18); forced RLS hides other tenants' rows. */
class JooqPhaseAssessmentStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : PhaseAssessmentStore {
    override fun latest(entityId: UUID): StoredAssessment? =
        dsl
            .selectFrom(PHASE_ASSESSMENT)
            .where(PHASE_ASSESSMENT.ENTITY_ID.eq(entityId))
            .orderBy(PHASE_ASSESSMENT.SEQ.desc())
            .limit(1)
            .fetchOne()
            ?.let { row ->
                val returns =
                    mapper
                        .readTree(row.returnsUsed.column("returns_used"))
                        .values()
                        .map {
                            GstReturn(
                                LocalDate.parse(it["periodStart"].asString()),
                                LocalDate.parse(it["periodEnd"].asString()),
                                BigDecimal(it["box4"].asString()),
                            )
                        }
                StoredAssessment(
                    row.asOfDate.column("as_of_date"),
                    PhaseAssessment(
                        row.totalAnnualSupplies.column("total_annual_supplies"),
                        row.mandatoryDate.column("mandatory_date"),
                        PhaseInputs(
                            row.windowRow?.let(PhaseWindow::valueOf),
                            returns,
                            row.daysCovered?.toLong(),
                            row.extrapolated.column("extrapolated"),
                            row.summedBox4.column("summed_box4"),
                        ),
                    ),
                )
            }

    override fun insert(
        entityId: UUID,
        stored: StoredAssessment,
    ) {
        val result = stored.assessment
        val inputs = result.inputs
        val returns =
            inputs.returnsUsed.map {
                mapOf(
                    "periodStart" to it.periodStart.toString(),
                    "periodEnd" to it.periodEnd.toString(),
                    "box4" to it.box4.toPlainString(),
                )
            }
        dsl
            .insertInto(PHASE_ASSESSMENT)
            .set(PHASE_ASSESSMENT.ID, UUID.randomUUID())
            .set(PHASE_ASSESSMENT.TENANT_ID, tenants.current().value)
            .set(PHASE_ASSESSMENT.ENTITY_ID, entityId)
            .set(PHASE_ASSESSMENT.AS_OF_DATE, stored.asOfDate)
            .set(PHASE_ASSESSMENT.TOTAL_ANNUAL_SUPPLIES, result.totalAnnualSupplies)
            .set(PHASE_ASSESSMENT.MANDATORY_DATE, result.mandatoryDate)
            .set(PHASE_ASSESSMENT.PHASE, stored.phase.name)
            .set(PHASE_ASSESSMENT.WINDOW_ROW, inputs.window?.name)
            .set(PHASE_ASSESSMENT.DAYS_COVERED, inputs.daysCovered?.toInt())
            .set(PHASE_ASSESSMENT.EXTRAPOLATED, inputs.extrapolationChosen)
            .set(PHASE_ASSESSMENT.SUMMED_BOX4, inputs.summedBox4)
            .set(PHASE_ASSESSMENT.RETURNS_USED, mapper.writeValueAsString(returns))
            .set(PHASE_ASSESSMENT.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }
}
