package com.dynokore.gst.masterdata.adapters.events

/**
 * The asyncapi channels of the contract this build publishes, each through a mapper that appends in the command
 * transaction (TPL-05). The contract gate (`operations.tsv`) holds a channel `done` exactly when it is in this set, and
 * a test per channel validates the payload against the AsyncAPI schema. This task publishes none: the identity triple
 * operations raise no catalogued masterdata event (the four contract channels belong to counterparty resolution, rates
 * and signed links).
 */
object PublishedChannels {
    val ALL: Set<String> = emptySet()
}
