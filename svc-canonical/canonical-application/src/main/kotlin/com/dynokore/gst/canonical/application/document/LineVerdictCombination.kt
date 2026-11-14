package com.dynokore.gst.canonical.application.document

/** The single named rule that turns line verdicts into the document verdict (owner-ruled; INV-REQ-374 OR rule). */
object LineVerdictCombination {
    private const val IN_SCOPE = "IN_SCOPE"
    private const val OUT_OF_SCOPE = "OUT_OF_SCOPE"
    private const val EXCLUDED_TXN = "EXCLUDED_TXN"
    private val known = setOf(IN_SCOPE, OUT_OF_SCOPE, EXCLUDED_TXN)

    /** Fails closed: an empty list or an element outside the three line verdicts throws. */
    fun combine(lineVerdicts: List<String>): String {
        require(lineVerdicts.isNotEmpty()) { "a document without a line has no verdict" }
        lineVerdicts.forEach { require(it in known) { "not a line verdict: $it" } }
        return when {
            IN_SCOPE in lineVerdicts -> IN_SCOPE
            OUT_OF_SCOPE in lineVerdicts -> OUT_OF_SCOPE
            else -> EXCLUDED_TXN
        }
    }
}
