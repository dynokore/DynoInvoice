package com.dynokore.platform.rulepackkernel

/**
 * Verifies a [PackArtefact]'s checksums and detached CMS signature against
 * [TrustAnchors] (STK-13): `rules-svc`, the sole `PackSource` client, verifies
 * on fetch/import; each pack holder verifies again on its own copy before
 * compiling and holding it (INV-REQ-1195's distribution path) — the same port,
 * called twice, by design.
 *
 * Framework-free (ARC-16): the `-api` half. The Bouncy Castle CMS
 * implementation lives in `-impl` ([BouncyCastlePackVerifier], out of this
 * module's public surface).
 */
fun interface PackVerifier {
    fun verify(
        artefact: PackArtefact,
        trustAnchors: TrustAnchors,
    ): PackVerificationOutcome
}
