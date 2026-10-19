package com.dynokore.gst.tenancy.domain

/** The short code of a registered entity or business unit (INV-REQ-850 item 7): 1 to 10 characters from A-Z and 0-9. */
@JvmInline
value class ShortCode private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH = 10
        private val SHAPE = Regex("^[A-Z0-9]{1,$MAX_LENGTH}$")

        /** A refusal is a business outcome, returned and never thrown (CS-12). */
        fun parse(text: String): CodeOutcome<ShortCode> =
            if (SHAPE.matches(
                    text,
                )
            ) {
                CodeOutcome.Accepted(ShortCode(text))
            } else {
                CodeOutcome.Refused(CodeRefusal.MALFORMED)
            }
    }
}

enum class CodeRefusal {
    /** Empty, longer than 10 characters, lower case or outside A-Z and 0-9. */
    MALFORMED,

    /** Another entity or business unit of the tenant already holds the code. */
    DUPLICATE,

    /** The code appears in an aggregate number, so it can no longer change (INV-REQ-436). */
    USED_IN_AGGREGATE_NUMBER,

    /** A business unit's code is entered at registration; it has no default. */
    NOT_ENTERED,
}

sealed interface CodeOutcome<out T> {
    data class Accepted<T>(
        val value: T,
    ) : CodeOutcome<T>

    data class Refused(
        val reason: CodeRefusal,
    ) : CodeOutcome<Nothing>
}
