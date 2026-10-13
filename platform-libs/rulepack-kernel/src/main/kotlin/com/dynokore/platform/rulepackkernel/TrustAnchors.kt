package com.dynokore.platform.rulepackkernel

/**
 * The set of [TrustAnchor]s a release train ships (STK-13): the current
 * signing key and at least one pre-published successor key, so a signature
 * made with either verifies without a format or code change — "the algorithm
 * is a configuration of `rulepack-kernel`" (STK-13).
 *
 * Private constructor + `@ConsistentCopyVisibility` (CS-07): the "at least one
 * anchor" invariant must hold for every instance, including one produced by
 * `copy()`.
 */
@ConsistentCopyVisibility
data class TrustAnchors private constructor(
    val anchors: List<TrustAnchor>,
) {
    companion object {
        operator fun invoke(anchors: List<TrustAnchor>): TrustAnchors {
            require(anchors.isNotEmpty()) { "TrustAnchors must carry at least one anchor" }
            return TrustAnchors(anchors.toList())
        }
    }
}
