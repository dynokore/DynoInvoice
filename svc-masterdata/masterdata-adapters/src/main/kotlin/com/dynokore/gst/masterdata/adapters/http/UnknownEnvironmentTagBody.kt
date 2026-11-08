package com.dynokore.gst.masterdata.adapters.http

import tools.jackson.core.JacksonException

/**
 * Recognises the binding failure of a `createConnection` body whose `environmentTag` is a string outside the closed
 * enum PRODUCTION or SANDBOX (INV-REQ-834). Decided from the Jackson exception's own path, never from message text:
 * the failing property is exactly `environmentTag` and the root of the failure is a value, not a syntax, error.
 */
internal object UnknownEnvironmentTagBody {
    private const val PROPERTY = "environmentTag"

    fun matches(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (current is JacksonException && current.path.lastOrNull()?.propertyName == PROPERTY) return true
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return false
    }

    private const val MAX_DEPTH = 16
}
