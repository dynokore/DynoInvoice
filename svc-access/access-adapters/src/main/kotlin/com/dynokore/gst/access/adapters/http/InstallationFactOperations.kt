package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.approval.InstallationApproverFact
import com.dynokore.gst.access.application.approval.ReadInstallationFactUseCase
import com.dynokore.gst.access.application.approval.RecordInstallationFactUseCase
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
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

/**
 * Runs the record per Idempotency-Key (API-15, INV-Q-15370): the same key and body return the stored response while the
 * stored fact still equals the body's value, and apply the body as an update when the fact has changed since; the same
 * key with another body is refused.
 */
fun interface InstallationJournal {
    fun once(
        actor: String,
        key: UUID,
        distinctTaxApprover: Boolean,
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
    private val serviceCaller: ServiceCaller,
    private val journal: InstallationJournal,
    private val platform: PlatformTenantBinding,
    private val tenants: TenantContext,
) {
    fun recordInstallationApproverFact(
        idempotencyKey: UUID,
        distinctTaxApprover: Boolean,
    ): ResponseEntity<FactResponse> {
        val actor = installerSubject()
        val outcome =
            platform.bindPlatform(actor) {
                journal.once(
                    actor,
                    idempotencyKey,
                    distinctTaxApprover,
                ) { recordFact.handle(actor, distinctTaxApprover) }
            }
        return ResponseEntity.ok(factOf(factOf(outcome)))
    }

    /**
     * The verified calling workload. That it is the installer is decided before the call by the caller check that
     * service-runtime generates from the contract for the edge `installer->access-svc` (INV-Q-17002 A); the audit entry
     * names this identifier, never a role.
     */
    private fun installerSubject(): String =
        WorkloadPrincipal(
            serviceCaller.current() ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED),
        ).value

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
