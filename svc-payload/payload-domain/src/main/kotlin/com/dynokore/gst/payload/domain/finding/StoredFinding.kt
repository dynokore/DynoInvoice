package com.dynokore.gst.payload.domain.finding

/**
 * One reason of a validation as the contract `ReasonItem` carries it (INV-REQ-500). Only [code] and [message] are
 * required by the contract, so [field], [severity] and [overridable] may be absent.
 */
data class ValidationReason(
    val code: String,
    val field: String?,
    val message: String,
    val severity: String?,
    val overridable: Boolean?,
)

/**
 * A BLOCK finding of one document version, as stored (INV-REQ-1180). [field] is '' where the reason names none.
 * [overridable] is false unless the reason said true, so an unknown state is not releasable (fail closed).
 */
data class StoredFinding(
    val code: String,
    val field: String,
    val message: String,
    val overridable: Boolean,
) {
    init {
        require(code.length in CODE_LENGTH) { "finding code must be 1 to 20 characters, got ${code.length}" }
    }

    companion object {
        private val CODE_LENGTH = 1..20

        /**
         * The findings to store for the [reasons] of one validation. Severity is compared by exact string equality:
         * `BLOCK` is kept, `INFO` and `WARN` are dropped, any other value or an absent severity throws
         * [IllegalArgumentException]. Kept reasons with the same code and field merge into one finding: [overridable]
         * is the logical AND, the message is that of the first; order of first appearance is kept.
         */
        fun fromReasons(reasons: List<ValidationReason>): List<StoredFinding> {
            val merged = LinkedHashMap<Pair<String, String>, StoredFinding>()
            for (reason in reasons) {
                if (reason.severity == "INFO" || reason.severity == "WARN") continue
                require(
                    reason.severity == "BLOCK",
                ) { "unknown or absent severity: ${reason.severity?.let { "'$it'" }}" }
                val finding =
                    StoredFinding(
                        reason.code,
                        reason.field.orEmpty(),
                        reason.message,
                        reason.overridable == true,
                    )
                merged.merge(finding.code to finding.field, finding) { first, next ->
                    first.copy(overridable = first.overridable && next.overridable)
                }
            }
            return merged.values.toList()
        }
    }
}
