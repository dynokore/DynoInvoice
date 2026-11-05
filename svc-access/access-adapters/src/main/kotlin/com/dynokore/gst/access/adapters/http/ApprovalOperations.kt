package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.approval.DecideOverrideReleaseUseCase
import com.dynokore.gst.access.application.approval.DecidePackApprovalUseCase
import com.dynokore.gst.access.application.approval.ReadMappingAcknowledgementUseCase
import com.dynokore.gst.access.contract.model.AcknowledgeMappingResponse
import com.dynokore.gst.access.contract.model.OverrideReleaseDecision
import com.dynokore.gst.access.contract.model.PackApprovalDecision
import com.dynokore.gst.access.domain.approval.Outstanding
import com.dynokore.gst.access.domain.approval.OverrideDenyReason
import com.dynokore.gst.access.domain.approval.OverrideDocument
import com.dynokore.gst.access.domain.approval.OverrideOutcome
import com.dynokore.gst.access.domain.approval.OverrideVerdict
import com.dynokore.gst.access.domain.approval.PackVerdict
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * The separation-of-duty decisions and the mapping acknowledgement read (INV-T-4936).
 * The people in a request are compared as written, whole: no trimming and no case folding. Every decision answers 200;
 * a DENY is the decision, not an HTTP error. A caller is the authenticated subject of the call, never a request field.
 */
class ApprovalOperations(
    private val packApproval: DecidePackApprovalUseCase,
    private val overrideRelease: DecideOverrideReleaseUseCase,
    private val acknowledgements: ReadMappingAcknowledgementUseCase,
) {
    fun decidePackApproval(
        entityRef: String,
        authorRef: String,
        approverRef: String,
        priorApproverRef: List<String>?,
    ): ResponseEntity<PackApprovalDecision> {
        val answer =
            packApproval.handle(
                entity = reference("entityRef") { EntityRef(entityRef) },
                author = reference("authorRef") { IdentityRef(authorRef) },
                approver = reference("approverRef") { IdentityRef(approverRef) },
                priorApprovers = people(priorApproverRef),
            )
        val option = PackApprovalDecision.Option.valueOf(answer.option.name)
        return ResponseEntity.ok(
            when (val verdict = answer.verdict) {
                is PackVerdict.Allow -> {
                    PackApprovalDecision(
                        PackApprovalDecision.Decision.ALLOW,
                        verdict.soleOperatorAck != null,
                        verdict.outstanding.map { outstanding(it) }.toSet(),
                        option = option,
                        soleOperatorAckRef = verdict.soleOperatorAck?.value,
                    )
                }

                is PackVerdict.Deny -> {
                    PackApprovalDecision(
                        PackApprovalDecision.Decision.DENY,
                        false,
                        emptySet(),
                        reasonCode = PackApprovalDecision.ReasonCode.valueOf(verdict.reason.name),
                        option = option,
                    )
                }
            },
        )
    }

    private fun outstanding(item: Outstanding) = PackApprovalDecision.Outstanding.valueOf(item.name)

    fun decideOverrideRelease(
        entityRef: String,
        errorCode: String,
        approverRef: String,
        documentRef: String?,
        documentVersion: Int?,
        documentTotalSgd: BigDecimal?,
        priorApproverRef: List<String>?,
    ): ResponseEntity<OverrideReleaseDecision> {
        val answer =
            overrideRelease.handle(
                entity = reference("entityRef") { EntityRef(entityRef) },
                errorCode = errorCode,
                document = documentOf(documentRef, documentVersion, documentTotalSgd),
                approver = reference("approverRef") { IdentityRef(approverRef) },
                priorApprovers = people(priorApproverRef),
            )
        val option = OverrideReleaseDecision.Option.valueOf(answer.option.name)
        return ResponseEntity.ok(
            when (val verdict = answer.verdict) {
                is OverrideVerdict.Allow -> {
                    OverrideReleaseDecision(
                        OverrideReleaseDecision.Decision.ALLOW,
                        verdict.soleOperatorAck != null,
                        outcome = outcomeOf(verdict.outcome),
                        option = option,
                        soleOperatorAckRef = verdict.soleOperatorAck?.value,
                    )
                }

                is OverrideVerdict.Deny -> {
                    OverrideReleaseDecision(
                        OverrideReleaseDecision.Decision.DENY,
                        false,
                        reasonCode = reasonOf(verdict.reason),
                        option = option,
                    )
                }
            },
        )
    }

    private fun outcomeOf(outcome: OverrideOutcome) = OverrideReleaseDecision.Outcome.valueOf(outcome.name)

    private fun reasonOf(reason: OverrideDenyReason) = OverrideReleaseDecision.ReasonCode.valueOf(reason.name)

    /**
     * The three document fields go together (INV-Q-10721): all present for a validation override, all absent for a
     * filing-gate override; some but not all is a contract violation. The total is a decimal with at most two places.
     */
    private fun documentOf(
        ref: String?,
        version: Int?,
        total: BigDecimal?,
    ): OverrideDocument? {
        val present = listOf(ref != null, version != null, total != null)
        if (present.none { it }) return null
        if (!present.all { it }) {
            violation("documentRef, documentVersion and documentTotalSgd are present together or absent together")
        }
        val amount = checkNotNull(total)
        if (amount.scale() !in 0..MONEY_PLACES || amount.signum() < 0) {
            violation("documentTotalSgd is a decimal of at least 0 with at most two decimal places")
        }
        if (ref.isNullOrBlank() || checkNotNull(version) < 1) violation("documentRef and documentVersion are invalid")
        return OverrideDocument(ref, version, amount)
    }

    fun getMappingAcknowledgement(
        packVersion: String,
        clientRef: String,
    ): ResponseEntity<AcknowledgeMappingResponse> {
        val found = acknowledgements.handle(packVersion, clientRef) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(
            AcknowledgeMappingResponse(
                found.packVersion,
                found.clientRef,
                found.acknowledgedAt.atOffset(ZoneOffset.UTC),
            ),
        )
    }

    private fun people(refs: List<String>?): List<IdentityRef> =
        refs.orEmpty().map { person -> reference("priorApproverRef") { IdentityRef(person) } }

    private fun <T> reference(
        field: String,
        build: () -> T,
    ): T = runCatching(build).getOrElse { throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "$field is invalid") }

    private fun violation(detail: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, detail)

    private companion object {
        const val MONEY_PLACES = 2
    }
}
