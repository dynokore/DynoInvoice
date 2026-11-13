package com.dynokore.gst.consoleBff.application.resolvegroup

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply

data class ResolveGroupRequest(
    val errorCode: String,
    val counterpartyRef: String,
    val docUuids: List<String>,
    val resolvedIdentifiers: List<ResolvedIdentifier>,
    val evidenceRef: String,
)

sealed interface ResolveGroupOutcome {
    data class Resolved(
        val resolvedCount: Int,
        val unchangedCount: Int,
    ) : ResolveGroupOutcome

    data class GroupSubmitted(
        val submittedCount: Int,
    ) : ResolveGroupOutcome

    data class Relayed(
        val refusal: OwnerRefusal,
    ) : ResolveGroupOutcome

    data object TooMany : ResolveGroupOutcome

    data class Invalid(
        val reason: String,
    ) : ResolveGroupOutcome

    data object Forbidden : ResolveGroupOutcome

    data class Unavailable(
        val reason: String,
    ) : ResolveGroupOutcome
}

/**
 * Validate, check the permission, refuse a group with submitted members, then relay the owner's write.
 * Fail closed: an inconsistent boundary answer is Unavailable and nothing is written.
 */
class ResolveGroup(
    private val authority: ResolveGroupAuthority,
    private val boundary: SubmissionBoundary,
    private val bulk: MasterdataBulk,
) {
    fun execute(
        tenantId: String,
        userId: String,
        entityRef: String,
        idempotencyKey: String,
        request: ResolveGroupRequest,
    ): ResolveGroupOutcome {
        val docs = request.docUuids.distinct()
        val invalid = invalidReason(idempotencyKey, request, docs)
        return when {
            invalid != null -> ResolveGroupOutcome.Invalid(invalid)
            docs.size > MAX_DOCUMENTS -> ResolveGroupOutcome.TooMany
            !authority.holds(tenantId, userId, entityRef) -> ResolveGroupOutcome.Forbidden
            else -> afterAuthority(entityRef, idempotencyKey, request, docs)
        }
    }

    private fun invalidReason(
        idempotencyKey: String,
        request: ResolveGroupRequest,
        docs: List<String>,
    ): String? =
        when {
            docs.isEmpty() -> "docUuids must not be empty"
            docs.any { it.isBlank() } -> "docUuids must not hold a blank member"
            request.errorCode.isBlank() -> "errorCode must not be blank"
            request.counterpartyRef.isBlank() -> "counterpartyRef must not be blank"
            request.evidenceRef.isBlank() -> "evidenceRef must not be blank"
            idempotencyKey.isBlank() -> "idempotencyKey must not be blank"
            else -> null
        }

    private fun afterAuthority(
        entityRef: String,
        idempotencyKey: String,
        request: ResolveGroupRequest,
        docs: List<String>,
    ): ResolveGroupOutcome =
        when (val answer = boundary.check(docs)) {
            is OwnerReply.Refused -> {
                ResolveGroupOutcome.Relayed(answer.refusal)
            }

            is OwnerReply.Unavailable -> {
                ResolveGroupOutcome.Unavailable(answer.reason)
            }

            is OwnerReply.Ok -> {
                val b = answer.value
                when {
                    b.checkedCount != docs.size ||
                        b.submittedCount != b.submittedMembers.size ||
                        b.submittedCount > b.checkedCount ||
                        b.submittedCount < 0 -> ResolveGroupOutcome.Unavailable("inconsistent boundary answer")

                    b.submittedCount > 0 -> ResolveGroupOutcome.GroupSubmitted(b.submittedCount)

                    else -> write(entityRef, idempotencyKey, request, docs)
                }
            }
        }

    private fun write(
        entityRef: String,
        idempotencyKey: String,
        request: ResolveGroupRequest,
        docs: List<String>,
    ): ResolveGroupOutcome =
        when (
            val answer =
                bulk.resolve(
                    BulkCommand(
                        entityRef,
                        idempotencyKey,
                        request.errorCode,
                        request.counterpartyRef,
                        docs,
                        request.resolvedIdentifiers,
                        request.evidenceRef,
                    ),
                )
        ) {
            is OwnerReply.Ok -> ResolveGroupOutcome.Resolved(answer.value.resolvedCount, answer.value.unchangedCount)
            is OwnerReply.Refused -> ResolveGroupOutcome.Relayed(answer.refusal)
            is OwnerReply.Unavailable -> ResolveGroupOutcome.Unavailable(answer.reason)
        }

    private companion object {
        const val MAX_DOCUMENTS = 5000
    }
}
