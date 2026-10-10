package com.dynokore.platform.auditlog

/** How a `BLOCK` code may be released (INV-REQ-1186). */
enum class ErrorDisposition {
    /** The System's own control: overridable with a reason by an authorised role (INV-REQ-1180). */
    OVERRIDABLE,

    /** The fix belongs at source: overriding would only make IRAS reject the document. Not overridable. */
    MANUAL,

    /** Proceeding would corrupt the record at IRAS. Never overridable, by any role (INV-REQ-1179). */
    TERMINAL,
}

/** The owning service's classification of its error codes (e.g. from the rule pack or error catalogue). */
fun interface ErrorCatalogue {
    /** The disposition of [code], or `null` when the catalogue does not know it. */
    fun dispositionOf(code: String): ErrorDisposition?
}
