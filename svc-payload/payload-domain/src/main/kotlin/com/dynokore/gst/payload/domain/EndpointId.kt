package com.dynokore.gst.payload.domain

import com.dynokore.gst.payload.domain.party.SG_UEN_SCHEME

/** The outcome of the endpoint ID (IBT-049) construction (INV-REQ-321, INV-Q-7189 A). Nothing is held here. */
sealed interface EndpointIdResult {
    data class Built(
        val value: String,
    ) : EndpointIdResult

    /** The buyer scheme is not SG_UEN: there is no UEN to append (the hold rule is a later slice, INV-Q-1445). */
    data object NoUenToAppend : EndpointIdResult

    data object ReachabilityUnknown : EndpointIdResult

    data object PrefixNotConfigured : EndpointIdResult

    /** Off-network Type 1A or 1B: no published text states the form. */
    data class Unpublished(
        val submissionType: SubmissionType,
    ) : EndpointIdResult
}

/** The off-network prefix of the active MDE pack version (INV-Q-7189 A); null when the pack configures none. */
interface OffNetworkPrefixSource {
    fun prefix(): String?
}

/** The endpoint ID (IBT-049) as a pure function (INV-REQ-321, INV-Q-7189 A); the first rule that applies wins. */
object EndpointId {
    private const val ON_NETWORK_PREFIX = "SGUEN"

    fun construct(
        onNetwork: Boolean?,
        submissionType: SubmissionType,
        buyerIdentifierScheme: String,
        buyerIdentifierValue: String,
        offNetworkPrefix: String?,
    ): EndpointIdResult =
        when {
            buyerIdentifierScheme != SG_UEN_SCHEME -> {
                EndpointIdResult.NoUenToAppend
            }

            onNetwork == null -> {
                EndpointIdResult.ReachabilityUnknown
            }

            onNetwork -> {
                EndpointIdResult.Built(ON_NETWORK_PREFIX + buyerIdentifierValue)
            }

            submissionType == SubmissionType.TYPE_2 || submissionType == SubmissionType.TYPE_3 -> {
                offNetworkPrefix?.let { EndpointIdResult.Built(it + buyerIdentifierValue) }
                    ?: EndpointIdResult.PrefixNotConfigured
            }

            else -> {
                EndpointIdResult.Unpublished(submissionType)
            }
        }
}
