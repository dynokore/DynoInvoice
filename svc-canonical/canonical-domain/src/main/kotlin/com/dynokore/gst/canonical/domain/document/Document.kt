package com.dynokore.gst.canonical.domain.document

import java.time.LocalDate
import java.util.UUID

/** INV-NOTE-3 section 10.2 `document.direction`, as the canonical contract names it. */
enum class Direction { SALE, PURCHASE }

/** `document.state`: the document-level states of the state model up to READY and BLOCKED. */
enum class DocumentState { INGESTED, HELD, BLOCKED, NOT_SUBMITTABLE, READY }

/** `document_version.scope_verdict`: the document-level OR rule over the lines (INV-NOTE-3 section 4.2.4). */
enum class ScopeVerdict { IN_SCOPE, EXCLUDED_TXN, OUT_OF_SCOPE }

/** `document_version.specification_version`: the specification family. */
enum class SpecificationVersion { PINT_SG, SG_PEPPOL_BIS }

/** The content of one immutable version. [canonical] is the canonical document as JSON text, stored as received. */
data class NewVersion(
    val canonical: String,
    val scopeVerdict: ScopeVerdict,
    val scopePackVersion: String,
    val mappingPackVersion: String,
    val mdePackVersion: String?,
    val specificationVersion: SpecificationVersion,
    val transactionDate: LocalDate,
    val createdBy: String,
)

/**
 * A new logical document with its version 1. `partition_date` and the first `transaction_date` are the transaction
 * date of [first].
 */
data class NewDocument(
    val docUuid: UUID,
    val tenantId: UUID,
    val entityId: UUID,
    val businessUnitId: UUID?,
    val direction: Direction,
    val documentTypeCode: Short,
    val documentNumber: String,
    val issuerPartyKey: String,
    val fiscalYear: Short,
    val internalReference: String?,
    val state: DocumentState,
    val prescribedPeriodId: UUID?,
    val first: NewVersion,
) {
    init {
        require(documentNumber.length in 1..MAX_DOCUMENT_NUMBER) { "document number length is outside 1..100" }
    }

    private companion object {
        const val MAX_DOCUMENT_NUMBER = 100
    }
}

/** One document of a window read: its current version's canonical JSON. */
data class WindowDocument(
    val docUuid: UUID,
    val documentTypeCode: Short,
    val transactionDate: LocalDate,
    val currentVersion: Int,
    val canonical: String,
)

/** `docUuidSource` of the canonical contract: where the Doc UUID of a document came from. */
enum class DocUuidSource { SYSTEM, ISSUER, SUPPLIER }
