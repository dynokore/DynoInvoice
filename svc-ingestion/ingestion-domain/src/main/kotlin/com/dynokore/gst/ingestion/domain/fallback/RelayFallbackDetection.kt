package com.dynokore.gst.ingestion.domain.fallback

/** Local vocabulary: the member names are the contract strings of the tenancy `mode` enum. */
enum class IssuanceMode { RELAY, OBSERVE, NONE }

enum class NotFallbackReason { NO_NETWORK_ISSUANCE, RELAY_PATH, MODE_NOT_RELAY }

enum class FallbackReason { MODE_RELAY, MODE_UNKNOWN }

enum class AfterTheFactCheck { VALIDATION_LAYER_1, VALIDATION_LAYER_2, WRONGFUL_GST_CHARGE }

enum class FindingSeverity { WARN }

enum class RefusalReason { CONTRADICTORY_MARKERS }

data class FallbackInput(
    val networkIssuancePosted: Boolean,
    val relayIssuance: Boolean,
    val modeOnDocumentDate: IssuanceMode?,
)

sealed interface FallbackDecision {
    data class NotFallback(
        val reason: NotFallbackReason,
    ) : FallbackDecision

    data class Fallback(
        val reason: FallbackReason,
        val checks: List<AfterTheFactCheck>,
        val severity: FindingSeverity,
    ) : FallbackDecision

    data class Refused(
        val reason: RefusalReason,
    ) : FallbackDecision
}

/** INV-REQ-340 behavior 6: decides whether a posting is a Relay fallback document (INV-Q-7144 A). */
object RelayFallbackDetection {
    private val AFTER_THE_FACT_CHECKS =
        listOf(
            AfterTheFactCheck.VALIDATION_LAYER_1,
            AfterTheFactCheck.VALIDATION_LAYER_2,
            AfterTheFactCheck.WRONGFUL_GST_CHARGE,
        )

    fun decide(input: FallbackInput): FallbackDecision =
        when {
            input.networkIssuancePosted && input.relayIssuance -> {
                FallbackDecision.Refused(RefusalReason.CONTRADICTORY_MARKERS)
            }

            input.relayIssuance -> {
                FallbackDecision.NotFallback(NotFallbackReason.RELAY_PATH)
            }

            !input.networkIssuancePosted -> {
                FallbackDecision.NotFallback(NotFallbackReason.NO_NETWORK_ISSUANCE)
            }

            input.modeOnDocumentDate == IssuanceMode.RELAY -> {
                fallback(FallbackReason.MODE_RELAY)
            }

            input.modeOnDocumentDate == null -> {
                fallback(FallbackReason.MODE_UNKNOWN)
            }

            else -> {
                FallbackDecision.NotFallback(NotFallbackReason.MODE_NOT_RELAY)
            }
        }

    private fun fallback(reason: FallbackReason) =
        FallbackDecision.Fallback(reason, AFTER_THE_FACT_CHECKS, FindingSeverity.WARN)
}
