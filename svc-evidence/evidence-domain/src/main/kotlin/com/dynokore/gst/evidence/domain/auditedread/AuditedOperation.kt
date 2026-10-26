package com.dynokore.gst.evidence.domain.auditedread

/**
 * The audited reads of evidence-svc (INV-Q-7207 A, INV-REQ-958): exactly four, each named by its real operationId.
 * `getEvidenceRetrieval` (polling), the writes and the other reads are not audited reads here.
 */
enum class AuditedOperation(
    val operationId: String,
) {
    GET_ACKNOWLEDGEMENT_CHAIN("getAcknowledgementChain"),
    REVERSE_LOOKUP("reverseLookup"),
    CREATE_AUDIT_PACK_EXPORT("createAuditPackExport"),
    GET_AUDIT_PACK_EXPORT("getAuditPackExport"),
}

/** The search parameters of `reverseLookup` in the order of the scope text; never `limit` or `cursor`. */
enum class ReverseLookupParameter(
    val parameterName: String,
) {
    DOC_UUID("docUuid"),
    DOCUMENT_NUMBER("documentNumber"),
    COUNTERPARTY("counterparty"),
    DATE_FROM("dateFrom"),
    DATE_TO("dateTo"),
    PERIOD_REF("periodRef"),
    CATEGORY_CODE("categoryCode"),
}
