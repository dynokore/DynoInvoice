package com.dynokore.gst.ingestion.domain.triggers

/** The five trigger classes of the registry (INV-REQ-788); declaration order is the reporting order. */
enum class TriggerClass {
    OUTBOUND_DOCUMENT_ISSUED,
    INBOUND_DOCUMENT_RECORDED,
    AGGREGABLE_BATCH_CLOSED,
    JOURNAL_ONLY_SUPPLY,
    THIRD_PARTY_ISSUANCE,
    ;

    companion object {
        /** Exact, case-sensitive match of the five contract names; anything else is refused with null. */
        fun parse(name: String): TriggerClass? = entries.firstOrNull { it.name == name }
    }
}
