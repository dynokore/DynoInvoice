package com.dynokore.platform.adapterruntime

/**
 * The five trigger classes every in-scope entity must cover (INV-REQ-788). The names equal the enum of the path
 * parameter TriggerClass in the ingestion contract; the contract is the source of truth.
 */
enum class TriggerClass {
    /** Outbound document issued (ERP AR). */
    OUTBOUND_DOCUMENT_ISSUED,

    /** Inbound document recorded (ERP AP). */
    INBOUND_DOCUMENT_RECORDED,

    /** Aggregable batch closed (POS, expense). */
    AGGREGABLE_BATCH_CLOSED,

    /** Journal-only supply (general ledger). */
    JOURNAL_ONLY_SUPPLY,

    /** Third-party issuance (shared service centre, CES portal). */
    THIRD_PARTY_ISSUANCE,
}
