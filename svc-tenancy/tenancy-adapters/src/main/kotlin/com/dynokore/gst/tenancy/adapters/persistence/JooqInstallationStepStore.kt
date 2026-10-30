package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INSTALLATION_ACTION
import com.dynokore.gst.tenancy.application.installation.InstallationAck
import com.dynokore.gst.tenancy.application.installation.InstallationStep
import com.dynokore.gst.tenancy.application.installation.InstallationStepStore
import com.dynokore.gst.tenancy.application.installation.StoredInstallationStep
import com.dynokore.platform.serviceruntime.persistence.LedgerAppender
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The generated POJO of `tenancy.installation_action`. */
typealias InstallationActionRow =
    com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.pojos.InstallationAction

/**
 * The installer run-record steps, append-only (CS-22): `LedgerAppender` writes a step once (TPL-11) and the table
 * grants
 * the runtime login no update and no delete. The caller holds the transaction under the platform binding.
 */
class JooqInstallationStepStore(
    private val dsl: DSLContext,
    private val ledger: LedgerAppender<InstallationActionRow>,
) : InstallationStepStore {
    override fun find(
        runId: UUID,
        step: Int,
    ): StoredInstallationStep? =
        dsl
            .selectFrom(INSTALLATION_ACTION)
            .where(INSTALLATION_ACTION.RUN_ID.eq(runId))
            .and(INSTALLATION_ACTION.STEP.eq(step))
            .fetchOne()
            ?.let {
                StoredInstallationStep(
                    InstallationAck(
                        it.runId.column("run_id"),
                        it.step.column("step"),
                        it.auditEntryId.column("audit_entry_id"),
                        it.recordedAt.column("recorded_at").toInstant(),
                    ),
                    it.fingerprint.column("fingerprint"),
                )
            }

    override fun append(
        step: InstallationStep,
        fingerprint: String,
        auditEntryId: UUID,
        recordedAt: Instant,
    ) {
        ledger.append(
            InstallationActionRow(
                runId = step.runId,
                step = step.step,
                command = step.command,
                stepName = step.stepName,
                outcome = step.outcome,
                occurredAt = step.occurredAt.atOffset(ZoneOffset.UTC),
                runIdentity = step.runIdentity,
                deploymentOperator = step.deploymentOperator,
                approvingEngineer = step.approvingEngineer,
                trainFrom = step.trainFrom,
                trainTo = step.trainTo,
                fingerprint = fingerprint,
                auditEntryId = auditEntryId,
                recordedAt = recordedAt.atOffset(ZoneOffset.UTC),
            ),
        )
    }
}
