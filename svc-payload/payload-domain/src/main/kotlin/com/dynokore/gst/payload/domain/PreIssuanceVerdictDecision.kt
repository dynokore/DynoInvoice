package com.dynokore.gst.payload.domain

import java.math.BigDecimal

enum class PreIssuanceSubmissionType { TYPE_1A, TYPE_2, TYPE_1B, TYPE_3 }

enum class PreIssuanceRegisterStatus { REGISTERED, NOT_REGISTERED, REGISTER_UNAVAILABLE }

enum class PreIssuanceVerdict { PASS, WARN, BLOCK }

enum class PreIssuanceFlag { REGISTER_UNAVAILABLE }

/** A reason of a pre-issuance verdict. */
data class PreIssuanceReason(
    val code: String,
    val field: String,
    val overridable: Boolean,
)

/** Decision input. [gstAmount] is the document-level GST amount as a decimal string; null means missing. */
data class PreIssuanceInput(
    val submissionType: PreIssuanceSubmissionType,
    val registerStatus: PreIssuanceRegisterStatus,
    val gstAmount: String?,
)

/** Typed input error: invalid input is refused, never decided as PASS (OWASP ASVS V5, NIST SP 800-53 SI-10). */
enum class PreIssuanceInputError { MISSING_AMOUNT, INVALID_AMOUNT, INVALID_STATUS, INVALID_SUBMISSION_TYPE }

sealed interface PreIssuanceOutcome {
    data class Decided(
        val verdict: PreIssuanceVerdict,
        val reasons: List<PreIssuanceReason>,
        val flags: Set<PreIssuanceFlag>,
    ) : PreIssuanceOutcome

    data class Refused(
        val error: PreIssuanceInputError,
    ) : PreIssuanceOutcome
}

/**
 * The wrongful GST charge decision of a pre-issuance check (INV-REQ-475 behavior 2, INV-Q-13371 A), pure.
 * Wrongful exactly when the status is NOT_REGISTERED and the document GST amount is not equal to zero by
 * BigDecimal.compareTo. Line amounts are not read.
 */
object PreIssuanceVerdictDecision {
    const val WRONGFUL_GST_CODE = "PAY-011"
    const val WRONGFUL_GST_FIELD = "supplierGstn"

    private val AMOUNT = Regex("-?\\d{1,17}(\\.\\d{1,2})?")

    fun decide(input: PreIssuanceInput): PreIssuanceOutcome {
        val text = input.gstAmount
        return when {
            text == null -> PreIssuanceOutcome.Refused(PreIssuanceInputError.MISSING_AMOUNT)
            !AMOUNT.matches(text) -> PreIssuanceOutcome.Refused(PreIssuanceInputError.INVALID_AMOUNT)
            else -> decideValid(input.submissionType, input.registerStatus, BigDecimal(text))
        }
    }

    private fun decideValid(
        type: PreIssuanceSubmissionType,
        status: PreIssuanceRegisterStatus,
        amount: BigDecimal,
    ): PreIssuanceOutcome {
        val wrongful = status == PreIssuanceRegisterStatus.NOT_REGISTERED && amount.compareTo(BigDecimal.ZERO) != 0
        return when {
            wrongful -> {
                val verdict =
                    if (type == PreIssuanceSubmissionType.TYPE_1A) PreIssuanceVerdict.BLOCK else PreIssuanceVerdict.WARN
                decided(verdict, listOf(PreIssuanceReason(WRONGFUL_GST_CODE, WRONGFUL_GST_FIELD, true)))
            }

            status == PreIssuanceRegisterStatus.REGISTER_UNAVAILABLE -> {
                decided(PreIssuanceVerdict.WARN, flags = setOf(PreIssuanceFlag.REGISTER_UNAVAILABLE))
            }

            else -> {
                decided(PreIssuanceVerdict.PASS)
            }
        }
    }

    /** Strict string entry: names must match the enum constants exactly (case-sensitive), else refused. */
    fun decideRaw(
        submissionType: String?,
        registerStatus: String?,
        gstAmount: String?,
    ): PreIssuanceOutcome {
        val type = PreIssuanceSubmissionType.entries.firstOrNull { it.name == submissionType }
        val status = PreIssuanceRegisterStatus.entries.firstOrNull { it.name == registerStatus }
        return when {
            type == null -> PreIssuanceOutcome.Refused(PreIssuanceInputError.INVALID_SUBMISSION_TYPE)
            status == null -> PreIssuanceOutcome.Refused(PreIssuanceInputError.INVALID_STATUS)
            else -> decide(PreIssuanceInput(type, status, gstAmount))
        }
    }

    private fun decided(
        verdict: PreIssuanceVerdict,
        reasons: List<PreIssuanceReason> = emptyList(),
        flags: Set<PreIssuanceFlag> = emptySet(),
    ) = PreIssuanceOutcome.Decided(verdict, reasons, flags)
}
