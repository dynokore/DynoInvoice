package com.dynokore.gst.rules.domain.scope

/** The scope verdict of a line; the mapping to the contract's type is the adapter's, never the domain's. */
enum class ScopeVerdict {
    SUBMITTABLE,
    NOT_SUBMITTABLE,
    PRE_OBLIGATION,
    IN_SCOPE,
    OUT_OF_SCOPE,
    EXCLUDED_TXN,
}
