package com.dynokore.gst.rules.domain.pack

/**
 * The reliability class of a rule pack entry's source (INV-REQ-727; the members and meaning of INV-AC-1727).
 *
 * `PRIMARY` is taken from the IRAS guidance or the published PINT-SG specification, `SECONDARY` from vendor or
 * third-party documentation and `INFERRED` is derived by reasoning from conflicting or silent sources.
 */
enum class SourceClass {
    PRIMARY,
    SECONDARY,
    INFERRED,
}
