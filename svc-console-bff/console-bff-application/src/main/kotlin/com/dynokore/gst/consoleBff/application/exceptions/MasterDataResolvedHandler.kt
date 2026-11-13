package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.exceptions.DocumentReleasedFacts
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/** Codes whose disposition is "AUTO_RELEASE on counterparty resolution" in the error-code catalogue (MDM-001 only). */
val MASTER_DATA_HOLD_CODES: Set<String> = setOf("MDM-001")

/**
 * Resolves the OPEN master-data hold rows of exactly the documents a `MasterDataResolved` event lists (INV-AC-21062).
 * No document is guessed: an absent `docUuids` member changes nothing. Other codes of a listed document stay open.
 */
class MasterDataResolvedHandler(
    private val tenants: TenantContext,
    private val reader: MasterDataResolvedReader,
    private val store: ExceptionStore,
    private val release: RecordDocumentReleased,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val docUuids = reader.read(event.dataJson)
        val tenant = tenants.current().value.toString()
        var changed = false
        for (docUuid in docUuids) {
            val kept = store.openRowsOfDocument(tenant, docUuid).filter { it.code in MASTER_DATA_HOLD_CODES }
            if (kept.isEmpty()) continue
            val facts = DocumentReleasedFacts(docUuid, kept.first().docsequence, kept.map { it.code }.distinct())
            if (release.record(tenant, facts)) changed = true
        }
        return if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
