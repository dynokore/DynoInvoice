package com.dynokore.platform.adapterruntime

import java.time.Instant

/**
 * Decides, for a given instant, which [AdapterVersion] is active for new work
 * (INV-REQ-1354: "routing by cut-over rule"). A
 * [VersionedAdapterRouter][com.dynokore.platform.adapterruntime.cutover.VersionedAdapterRouter]
 * asks this on every dispatch; the previously active version is drained, never killed
 * outright, once this rule stops naming it.
 */
fun interface CutoverRule {
    /** The version new work is routed to at instant [at]. */
    fun activeVersion(at: Instant): AdapterVersion
}

/**
 * The common case: everything before [cutoverAt] routes to [before]; everything at or
 * after it routes to [after]. A same-instant tie goes to [after], so a cutover instant
 * is itself already "cut over".
 */
data class TimeBasedCutoverRule(
    val before: AdapterVersion,
    val after: AdapterVersion,
    val cutoverAt: Instant,
) : CutoverRule {
    override fun activeVersion(at: Instant): AdapterVersion = if (at.isBefore(cutoverAt)) before else after
}
