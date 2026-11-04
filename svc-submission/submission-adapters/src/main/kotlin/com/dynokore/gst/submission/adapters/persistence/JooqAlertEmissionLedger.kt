package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ComplianceAlertLedger
import com.dynokore.gst.submission.application.compliance.AlertEmissionLedger
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * [AlertEmissionLedger] over `submission.compliance_alert_ledger` (INV-Q-16751 A). [claim] is one upsert statement in
 * the caller's open tenant transaction; it opens, commits and rolls back no transaction.
 */
class JooqAlertEmissionLedger(
    private val dsl: DSLContext,
) : AlertEmissionLedger {
    override fun claim(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        measuredOn: LocalDate,
    ): Boolean {
        val ledger = ComplianceAlertLedger.COMPLIANCE_ALERT_LEDGER
        return dsl
            .insertInto(ledger)
            .set(ledger.TENANT_ID, tenant.value)
            .set(ledger.ENTITY_REF, entityRef)
            .set(ledger.PERIOD_REF, periodRef)
            .set(ledger.MEASURED_ON, measuredOn)
            .onConflict(ledger.TENANT_ID, ledger.ENTITY_REF, ledger.PERIOD_REF)
            .doUpdate()
            .set(ledger.MEASURED_ON, DSL.excluded(ledger.MEASURED_ON))
            .where(ledger.MEASURED_ON.lt(DSL.excluded(ledger.MEASURED_ON)))
            .execute() == 1
    }
}
