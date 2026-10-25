package com.dynokore.gst.evidence.domain.chain

/** The four chain tables of evidence storage (INV-REQ-2570); [table] is the real table name (INV-Q-6109). */
enum class RecordKind(
    val table: String,
) {
    DECISION_TRACE("decision_trace"),
    EVIDENCE_PAYLOAD("evidence_payload"),
    ACKNOWLEDGEMENT("acknowledgement"),
    AUDIT_LOG("audit_log"),
}
