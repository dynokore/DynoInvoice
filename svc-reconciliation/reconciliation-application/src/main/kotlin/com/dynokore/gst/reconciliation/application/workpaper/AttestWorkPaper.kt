package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.application.run.RunReader
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

private const val HTTP_FORBIDDEN = 403
private const val HTTP_CONFLICT = 409

enum class AttestationRefusal(
    val status: Int,
) {
    SEPARATION_OF_DUTIES(HTTP_FORBIDDEN),
    UNEXPLAINED_VARIANCE(HTTP_CONFLICT),
}

sealed interface AttestationResult {
    data class Attested(
        val stored: StoredAttestation,
    ) : AttestationResult

    data class Replayed(
        val stored: StoredAttestation,
    ) : AttestationResult

    data object RunNotFound : AttestationResult

    data class Refused(
        val reason: AttestationRefusal,
    ) : AttestationResult
}

/**
 * attestWorkPaper (INV-REQ-947 item 2): the approver is the acting user ID of the caller; it must differ from the
 * preparer unless the sole-operator path is acknowledged and in force (NIST SP 800-53 AC-5). Fails closed when the
 * preparer or the policy cannot be read.
 */
@Suppress("LongParameterList")
class AttestWorkPaper(
    private val runs: RunReader,
    private val workPaper: GetWorkPaper,
    private val preparers: RunPreparers,
    private val policy: SingleAttestationPolicy,
    private val attestations: RunAttestations,
    private val writer: RunAttestationWriter,
    private val clock: Clock,
) {
    fun execute(
        tenant: String,
        runId: String,
        soleAttesterAcknowledgement: Boolean,
        actorUserId: String,
    ): AttestationResult {
        require(actorUserId.isNotBlank()) { "actorUserId must not be blank" }
        val view =
            if (runs.find(tenant, runId) == null) null else workPaper.execute(tenant, runId)
        return if (view == null) {
            AttestationResult.RunNotFound
        } else {
            attestations.get(tenant, runId)?.let { AttestationResult.Replayed(it) }
                ?: refusalOrStore(tenant, view, soleAttesterAcknowledgement, actorUserId)
        }
    }

    private fun refusalOrStore(
        tenant: String,
        view: WorkPaperView,
        acknowledged: Boolean,
        actor: String,
    ): AttestationResult {
        val runId = view.runId
        val now = clock.instant()
        val preparer =
            if (BigDecimal(view.unexplainedResidual).compareTo(BigDecimal.ZERO) == 0) {
                preparers.preparerOf(tenant, runId) ?: error("the preparer of run $runId cannot be determined")
            } else {
                return AttestationResult.Refused(AttestationRefusal.UNEXPLAINED_VARIANCE)
            }
        val sole =
            if (actor == preparer && acknowledged) {
                policy.soleOperator(tenant, view.header.entityRef, LocalDate.now(clock))
            } else {
                null
            }
        return if (actor == preparer && sole == null) {
            AttestationResult.Refused(AttestationRefusal.SEPARATION_OF_DUTIES)
        } else {
            store(tenant, runId, StoredAttestation(actor, now, sole))
        }
    }

    private fun store(
        tenant: String,
        runId: String,
        stored: StoredAttestation,
    ): AttestationResult =
        if (writer.insert(tenant, runId, stored)) {
            AttestationResult.Attested(stored)
        } else {
            val existing =
                attestations.get(tenant, runId)
                    ?: error("attestation of run $runId lost the write race but none is stored")
            AttestationResult.Replayed(existing)
        }
}
