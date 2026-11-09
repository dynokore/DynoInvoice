package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.persistence.MappingEntryView
import com.dynokore.gst.rules.application.MappingApproval
import com.dynokore.gst.rules.application.MappingPackResult
import com.dynokore.gst.rules.application.RefusalReason
import com.dynokore.gst.rules.contract.model.MappingEntry
import com.dynokore.gst.rules.contract.model.MappingPack
import com.dynokore.gst.rules.contract.model.MappingPackActivation
import com.dynokore.gst.rules.contract.model.MappingPackApproval
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import java.time.ZoneOffset

/**
 * A refused mapping pack command as the API-20 envelope states it (API-21, API-10). The catalogue code is the RUL code
 * of the sign-off refusals (422: nothing is recorded), SRT-004 for a caller without the permission (403), and the
 * closed service-runtime codes for the rest; the detail names entries but never a person (INV-Q-1949).
 */
class MappingRefusal(
    val status: Int,
    val code: String,
    val title: String,
    val detail: String?,
    val severity: String? = null,
    val disposition: String? = null,
) : RuntimeException("$code: $title")

private const val UNPROCESSABLE = 422
private const val CONFLICT = 409
private const val HEX = 16
private const val UNICODE_WIDTH = 4

/** 503 SRT-013 (the service is not ready to decide; nothing is decided or changed, the caller retries later). */
fun unavailable(detail: String) =
    ClosedErrorCode.SERVICE_NOT_READY.let { MappingRefusal(it.httpStatus, it.code, "A dependency is unavailable", detail) }

/** 409 SRT-008: the tenant already holds a mapping version with this label; nothing is stored. */
fun labelTaken() = MappingPackMapping.labelTakenRefusal()

/** The mapping of use case results and stored rows to the contract types and to the API-20 refusal envelope. */
object MappingPackMapping {
    /** The refusal a use case result stands for; null for a result that is not a refusal. */
    fun refusalOf(result: MappingPackResult): MappingRefusal? =
        when (result) {
            is MappingPackResult.Done -> null
            MappingPackResult.NotFound -> closed(ClosedErrorCode.NOT_FOUND, null)
            is MappingPackResult.Forbidden -> closed(ClosedErrorCode.ACCESS_DENIED, result.detail)
            is MappingPackResult.Refused -> refusedOf(result)
        }

    private fun closed(
        code: ClosedErrorCode,
        detail: String?,
    ) = MappingRefusal(code.httpStatus, code.code, code.title, detail)

    private fun catalogued(
        code: ErrorCode,
        detail: String,
    ) = MappingRefusal(
        UNPROCESSABLE,
        code.code,
        "Mapping pack refused",
        detail,
        code.severity.name,
        code.disposition?.name,
    )

    private fun refusedOf(result: MappingPackResult.Refused): MappingRefusal {
        val named = result.names.joinToString(", ")
        return when (result.reason) {
            RefusalReason.INPUT_INVALID -> {
                closed(ClosedErrorCode.CONTRACT_VIOLATION, "the request is not valid for a mapping pack")
            }

            RefusalReason.UNKNOWN_ENTRY -> {
                closed(ClosedErrorCode.NOT_FOUND, "no such entry: $named")
            }

            RefusalReason.CODE_CHECK, RefusalReason.SIGN_OFF_BLOCKED, RefusalReason.RATE_SOURCE_BLOCKED -> {
                catalogued(checkNotNull(result.code), "entries: $named")
            }

            RefusalReason.LIFECYCLE -> {
                conflict("the pack is ${result.status} and the command does not apply")
            }

            RefusalReason.PRIMARY_ENTRY -> {
                conflict("the entry is PRIMARY and needs no acceptance: $named")
            }

            RefusalReason.NO_DRY_RUN_DELTA -> {
                conflict("no completed dry run covers the current content of the pack")
            }

            RefusalReason.RATE_SOURCE_UNAVAILABLE,
            RefusalReason.DECISION_UNAVAILABLE,
            RefusalReason.HOLDER_COPY_UNAVAILABLE,
            -> {
                unavailable("the command is retryable once the dependency answers")
            }
        }
    }

    fun labelTakenRefusal() = conflict("a mapping version with this label already exists")

    private fun conflict(detail: String) =
        MappingRefusal(CONFLICT, ClosedErrorCode.STALE_AGGREGATE.code, "Conflict with the recorded state", detail)

    /** The envelope body of [refusal] as JSON (API-20). */
    fun problemBody(
        refusal: MappingRefusal,
        correlationId: String,
    ): String =
        buildString {
            append("{")
            append("\"type\":${text("https://errors.invoice.internal/${refusal.code}")}")
            append(",\"title\":${text(refusal.title)}")
            append(",\"status\":${refusal.status}")
            refusal.detail?.let { append(",\"detail\":${text(it)}") }
            append(",\"correlationId\":${text(correlationId)}")
            append(",\"code\":${text(refusal.code)}")
            refusal.severity?.let { append(",\"severity\":${text(it)}") }
            refusal.disposition?.let { append(",\"disposition\":${text(it)}") }
            append("}")
        }

    private fun text(value: String): String =
        buildString {
            append('"')
            value.forEach {
                when {
                    it == '"' -> append("\\\"")
                    it == '\\' -> append("\\\\")
                    it < ' ' -> append("\\u" + it.code.toString(HEX).padStart(UNICODE_WIDTH, '0'))
                    else -> append(it)
                }
            }
            append('"')
        }

    /** ARMED is shown as ACTIVE; every other status by name equality. */
    fun statusOf(status: PackStatus): MappingPack.Status {
        val shown = if (status == PackStatus.ARMED) "ACTIVE" else status.name
        val found = MappingPack.Status.entries.firstOrNull { it.value == shown }
        return checkNotNull(found) { "no contract status $shown" }
    }

    fun approvalOf(approval: MappingApproval): MappingPackApproval =
        MappingPackApproval(
            approvedBy = approval.approverUserId,
            approvedAt = approval.at.atOffset(ZoneOffset.UTC),
            soleOperator = approval.soleOperator,
            outstanding =
                MappingPackApproval.Outstanding.entries
                    .filter { it.value in approval.outstanding }
                    .toSet(),
            soleOperatorAckRef = approval.soleOperatorAckRef,
            option = approval.option,
        )

    /** True exactly when at least one approval row has sole_operator true (INV-REQ-992). */
    fun activationOf(approvals: List<MappingApproval>): MappingPackActivation =
        MappingPackActivation(noDistinctApprover = approvals.any { it.soleOperator })

    fun entryOf(view: MappingEntryView): MappingEntry =
        MappingEntry(
            entryId = view.entryId,
            internalTaxCode = view.internalTaxCode,
            irasCode = view.irasCode,
            sourceClass = MappingEntry.SourceClass.entries.first { it.value == view.sourceClass.name },
            accepted = view.accepted,
            schemeTag = view.schemeTag?.let { tag -> MappingEntry.SchemeTag.entries.firstOrNull { it.value == tag } },
        )
}
