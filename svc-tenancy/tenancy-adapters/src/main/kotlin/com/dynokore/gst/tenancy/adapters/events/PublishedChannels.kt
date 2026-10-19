package com.dynokore.gst.tenancy.adapters.events

/**
 * The asyncapi channels of the contract this build publishes, each through a mapper that appends in the command
 * transaction (TPL-05); a test per channel validates the payload against the AsyncAPI schema.
 */
object PublishedChannels {
    val ALL: Set<String> = setOf(OrganisationStructureChangedOutbox.CHANNEL)
}
