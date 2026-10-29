package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.coding.MappingEntry
import com.dynokore.gst.rules.domain.coding.ScenarioData
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.tenancykernel.TenantId

/**
 * Read ports for the contents of the pack version in force on a date (INV-Q-11614). Each returns the domain type, or
 * null when no version of that pack is effective on the date; an adapter maps the verified artefact to the domain type
 * and no adapter, JSON or contract type crosses these ports. The date is the caller's, never the clock (INV-REQ-770).
 */
fun interface MappingEntriesPort {
    fun mappingAt(
        tenant: TenantId?,
        date: TransactionDate,
    ): List<MappingEntry>?
}

fun interface CodeCataloguePort {
    fun catalogueAt(
        tenant: TenantId?,
        date: TransactionDate,
    ): CodeCatalogue?
}

fun interface ScenarioDataPort {
    fun scenarioAt(
        tenant: TenantId?,
        date: TransactionDate,
    ): ScenarioData?
}
