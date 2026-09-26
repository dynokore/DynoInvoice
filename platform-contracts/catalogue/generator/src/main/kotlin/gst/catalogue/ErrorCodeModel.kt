package gst.catalogue

/**
 * The two fixed vocabularies of INV-REQ-1178 (FR-O.1 behaviour #2): every error's
 * severity is one of these four, and (when it carries one at all) its disposition is
 * one of the five below. An `INFO` code may carry no disposition (ING-005).
 */
enum class Severity {
    BLOCK,
    HOLD,
    WARN,
    INFO,
}

enum class Disposition {
    AUTO_RETRY,
    AUTO_RELEASE,
    MANUAL,
    OVERRIDABLE,
    TERMINAL,
}

/**
 * One parsed row of `error-codes.yaml` (STK-61). [conditionCore] and [revisionNote]
 * split the source's free-text `condition` field: many rows append a trailing
 * "Amended/Added/Extended ..." clause documenting the change, which is not part of
 * the condition itself (INV-REQ-1183 behaviour #3 — a revision note, not a new
 * condition). The raw fields are kept alongside for entries whose severity or
 * disposition text does not reduce cleanly to one enum value (PAY-011, SUB-010, …) —
 * the generator classifies these but never discards the source text.
 */
data class ParsedErrorCode(
    val code: String,
    val area: String,
    val number: Int,
    val conditionRaw: String,
    val conditionCore: String,
    val revisionNote: String?,
    val severity: Severity,
    val severityRaw: String,
    val disposition: Disposition?,
    val dispositionRaw: String,
    val owner: String,
)
