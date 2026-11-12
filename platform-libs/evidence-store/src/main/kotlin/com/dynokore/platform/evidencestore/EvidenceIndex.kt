package com.dynokore.platform.evidencestore

import java.util.UUID

/**
 * The FR-9.15 index keys carried alongside a record's chain position: "indexed
 * by Doc UUID, invoice number, counterparty, prescribed period and GST
 * category code" — entity and period are the ledger's own scope ([EntityRef],
 * [PeriodRef]); these four are additional and evidence-type-dependent (not
 * every kind carries every key, so all four are optional). Stored so a future
 * consumer can query them directly; this kernel module exposes no lookup
 * method keyed on them itself (FR-X.9/FR-X.10's own lookups are evidence-svc's,
 * "served from evidence-svc's own indexes" — out of this task's declared
 * scope).
 *
 * [version] (INV-REQ-942) is the optional document version of a
 * [EvidenceType.DECISION_TRACE] record; it is index data, outside the content
 * hash, and only a ledger told its table has a version column accepts it.
 */
data class EvidenceIndex(
    val docUuid: UUID? = null,
    val invoiceNumber: String? = null,
    val counterpartyUen: String? = null,
    val categoryCode: String? = null,
    val version: Int? = null,
) {
    companion object {
        val EMPTY = EvidenceIndex()
    }
}
