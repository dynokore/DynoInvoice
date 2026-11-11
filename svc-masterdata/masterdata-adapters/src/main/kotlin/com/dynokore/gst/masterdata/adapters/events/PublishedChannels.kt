package com.dynokore.gst.masterdata.adapters.events

/**
 * The asyncapi channels of the contract this build publishes, each through a mapper that appends in the command
 * transaction (TPL-05). The contract gate (`operations.tsv`) holds a channel `done` exactly when it is in this set, and
 * a test per channel validates the payload against the AsyncAPI schema. This build publishes `MasterDataResolved`,
 * `IdentityTripleCorrected`, `IdentityTripleChanged` and `ReferenceRateLoaded`; the other channel is signed links.
 */
object PublishedChannels {
    val ALL: Set<String> =
        setOf(
            MasterDataResolvedOutbox.CHANNEL,
            IdentityTripleCorrectedOutbox.CHANNEL,
            IdentityTripleChangedOutbox.CHANNEL,
            ReferenceRateLoadedOutbox.CHANNEL,
        )

    /**
     * The asyncapi channels of other services this build consumes, each through an idempotent consumer of the link
     * bindings (`SignedLinkIssued`, `SignedLinkInvalidated` of access-svc). The contract gate holds these `done`
     * exactly as the published ones: [IMPLEMENTED] is the union.
     */
    val CONSUMED: Set<String> =
        setOf(ConsumedSignedLinkEvents.ISSUED_CHANNEL, ConsumedSignedLinkEvents.INVALIDATED_CHANNEL)

    val IMPLEMENTED: Set<String> = ALL + CONSUMED
}
