package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.approval.InstallationApproverFact
import com.dynokore.gst.access.application.approval.ReadInstallationFactUseCase
import com.dynokore.gst.access.application.approval.RecordInstallationFactUseCase
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.ResponseEntity
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.access.contract.model.InstallationApproverFact as FactResponse

/** The outcome of the idempotent record of the installation fact (API-15). */
sealed interface JournalOutcome {
    data class Done(
        val fact: InstallationApproverFact,
    ) : JournalOutcome

    data object InFlight : JournalOutcome

    data object DifferentRequest : JournalOutcome
}

/** Runs the record once per Idempotency-Key and request fingerprint; a replay returns the original fact. */
fun interface InstallationJournal {
    fun once(
        actor: String,
        key: UUID,
        fingerprint: String,
        record: () -> InstallationApproverFact,
    ): JournalOutcome
}

/**
 * The installation approver fact (INV-REQ-992, INV-T-4936). Only the installer's workload records it; a read is open to
 * an end user. The acting user ID, never a role, is what the audit entry names.
 */
class InstallationFactOperations(
    private val recordFact: RecordInstallationFactUseCase,
    private val readFact: ReadInstallationFactUseCase,
    private val installer: InstallerCaller,
    private val journal: InstallationJournal,
    private val platform: PlatformTenantBinding,
    private val tenants: TenantContext,
) {
    fun recordInstallationApproverFact(
        idempotencyKey: UUID,
        distinctTaxApprover: Boolean,
    ): ResponseEntity<FactResponse> {
        val actor = installerSubject()
        val fingerprint = "PUT /internal/v1/access/installation/approver-fact distinctTaxApprover=$distinctTaxApprover"
        val outcome =
            platform.bindPlatform(actor) {
                journal.once(actor, idempotencyKey, fingerprint) { recordFact.handle(actor, distinctTaxApprover) }
            }
        return ResponseEntity.ok(factOf(factOf(outcome)))
    }

    /** The subject of the installer's workload; no token is 401 SRT-003 and any other identity 403 SRT-004. */
    private fun installerSubject(): String =
        when (val caller = installer.identify()) {
            InstallerIdentity.Absent -> throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
            InstallerIdentity.Other -> throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            is InstallerIdentity.Installer -> caller.subject
        }

    private fun factOf(outcome: JournalOutcome): InstallationApproverFact =
        when (outcome) {
            is JournalOutcome.Done -> outcome.fact
            JournalOutcome.InFlight -> throw ApiRefusal(ClosedErrorCode.IN_FLIGHT)
            JournalOutcome.DifferentRequest -> throw ApiRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
        }

    fun getInstallationApproverFact(): ResponseEntity<FactResponse> {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        val fact = platform.bindPlatform(actor) { readFact.handle() } ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(factOf(fact))
    }

    private fun factOf(fact: InstallationApproverFact) =
        FactResponse(fact.distinctTaxApprover, fact.recordedAt.atOffset(ZoneOffset.UTC))
}
