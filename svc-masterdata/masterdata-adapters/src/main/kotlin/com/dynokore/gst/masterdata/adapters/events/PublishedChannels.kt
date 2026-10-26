package com.dynokore.gst.masterdata.adapters.events

/**
 * The asyncapi channels of the contract this build publishes, each through a mapper that appends in the command
 * transaction (TPL-05). The contract gate (`operations.tsv`) holds a channel `done` exactly when it is in this set, and
 * a test per channel validates the payload against the AsyncAPI schema. This build publishes `MasterDataResolved` and
 * `IdentityTripleCorrected`; the other channels belong to rates and signed links.
 */
object PublishedChannels {
    val ALL: Set<String> = setOf(MasterDataResolvedOutbox.CHANNEL, IdentityTripleCorrectedOutbox.CHANNEL)
}
