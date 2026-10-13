package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedSignatureVerifier
import com.dynokore.platform.crypto.DetachedVerification
import com.dynokore.platform.crypto.HashAlgorithm
import com.dynokore.platform.crypto.Hashing
import com.dynokore.platform.crypto.SignatureAlgorithm
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [PackVerifier] over Bouncy Castle CMS (STK-13: detached `SignedData`, ECDSA
 * P-256 with SHA-256; trust anchors ship in each release train with at least
 * one pre-published successor key).
 *
 * Verification, in order — any step failing short-circuits the rest:
 * 1. [PackArtefact.checksums] parses as JSON and [PackArtefact.contents]
 *    hashes to the digest it declares (`CHECKSUM_MISMATCH` otherwise).
 * 2. The CMS detached signature over [PackArtefact.manifest] +
 *    [PackArtefact.checksums] (concatenated, in that order) verifies
 *    mathematically against the signer certificate the CMS message itself
 *    carries (`SIGNATURE_INVALID` otherwise).
 * 3. That exact signer certificate — byte for byte — is one of the given
 *    [TrustAnchors] (`UNTRUSTED_SIGNER` otherwise): a signature that merely
 *    verifies against *some* certificate the message happens to carry proves
 *    nothing; trust is pinned to the train's own anchors, current or
 *    successor (STK-13), never to whatever the artefact says about itself.
 * 4. [PackArtefact.manifest] parses as JSON into a [PackManifest]
 *    (`MANIFEST_MALFORMED` otherwise).
 *
 * The CMS steps 2 and 3 are the platform crypto module's detached verifier (CS-53; INV-T-3358),
 * under [signatureAlgorithm], this module's configuration of the pack signature algorithm (STK-13),
 * ECDSA P-256 with SHA-256 by default: a signer using any other signature algorithm, digest or
 * curve is `SIGNATURE_INVALID`.
 *
 * `control-plane-svc`'s side (STK-13: "bundle format and signing", this
 * module's other declared user, INV-MOD-2373) is symmetric but out of this
 * task's declared scope — signing is not this module's job, only verifying.
 */
class BouncyCastlePackVerifier(
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val signatureVerifier: DetachedSignatureVerifier = BouncyCastleCms(),
    private val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithm.ECDSA_P256_SHA256,
) : PackVerifier {
    override fun verify(
        artefact: PackArtefact,
        trustAnchors: TrustAnchors,
    ): PackVerificationOutcome {
        val checksumsNode =
            parseJson(artefact.checksums)
                ?: return rejected(PackRejectionReason.MANIFEST_MALFORMED, "checksums member is not valid JSON")
        val declaredContentsDigest =
            checksumsNode
                .get(CONTENTS_CHECKSUM_FIELD)
                ?.asString("")
                ?.takeIf { it.isNotBlank() }
                ?: return rejected(
                    PackRejectionReason.MANIFEST_MALFORMED,
                    "checksums member has no '$CONTENTS_CHECKSUM_FIELD' entry",
                )
        val actualContentsDigest = sha256Hex(artefact.contents)
        if (!declaredContentsDigest.equals(actualContentsDigest, ignoreCase = true)) {
            return rejected(
                PackRejectionReason.CHECKSUM_MISMATCH,
                "contents hashes to $actualContentsDigest, checksums declares $declaredContentsDigest",
            )
        }

        val signedContent = artefact.manifest + artefact.checksums
        val anchors = trustAnchors.anchors
        val matchedAnchor =
            when (
                val verification =
                    signatureVerifier.verify(
                        content = signedContent,
                        signature = artefact.signature,
                        algorithm = signatureAlgorithm,
                        trustedCertificatesDer = anchors.map { it.certificateDer },
                    )
            ) {
                is DetachedVerification.SignatureInvalid -> {
                    return rejected(PackRejectionReason.SIGNATURE_INVALID, verification.detail)
                }

                DetachedVerification.UntrustedSigner -> {
                    return rejected(
                        PackRejectionReason.UNTRUSTED_SIGNER,
                        "signature is mathematically valid but the signer certificate is not one of the given trust anchors",
                    )
                }

                is DetachedVerification.Verified -> {
                    anchors[verification.trustedIndex]
                }
            }

        val manifestNode =
            parseJson(artefact.manifest)
                ?: return rejected(PackRejectionReason.MANIFEST_MALFORMED, "manifest member is not valid JSON")
        val manifest =
            parseManifest(manifestNode, checksumsNode)
                ?: return rejected(
                    PackRejectionReason.MANIFEST_MALFORMED,
                    "manifest is missing or has malformed required fields",
                )

        return PackVerificationOutcome.Verified(manifest, matchedAnchor.label)
    }

    private fun parseJson(bytes: ByteArray): JsonNode? =
        try {
            objectMapper.readTree(bytes)
        } catch (_: RuntimeException) {
            null
        }

    /**
     * Every failure mode here — a missing field, a malformed date, an invalid
     * checksum shape — becomes `null`, never a thrown exception.
     */
    private fun parseManifest(
        manifestNode: JsonNode,
        checksumsNode: JsonNode,
    ): PackManifest? =
        try {
            val packId = manifestNode.get("packId").requiredText() ?: return null
            val packType = manifestNode.get("packType").requiredText() ?: return null
            val version = manifestNode.get("version").requiredText() ?: return null
            val minEngineVersion = manifestNode.get("minEngineVersion").requiredText() ?: return null
            val effectiveFrom = manifestNode.get("effectiveFrom").requiredText()?.let(::parseDateOrNull) ?: return null
            val effectiveToNode = manifestNode.get("effectiveTo")
            val effectiveTo =
                if (effectiveToNode == null || effectiveToNode.isNull) {
                    null
                } else {
                    parseDateOrNull(effectiveToNode.asString("")) ?: return null
                }
            val contentChecksums =
                checksumsNode.properties().associate { (name, value) -> name to Checksum(value.asString("")) }

            PackManifest(
                packId = PackId(packId),
                packType = PackType(packType),
                version = PackVersionLabel(version),
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                minEngineVersion = minEngineVersion,
                contentChecksums = contentChecksums,
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: RuntimeException) {
            // A Jackson JsonNodeException from a wrongly-typed field also means "malformed" (Jackson 3: unchecked).
            null
        }

    /** A required text field, absent/blank/wrongly-typed alike collapsing to `null`. */
    private fun JsonNode?.requiredText(): String? = this?.asString("").orEmpty().ifBlank { null }

    private fun parseDateOrNull(text: String): LocalDate? =
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun rejected(
        reason: PackRejectionReason,
        detail: String,
    ): PackVerificationOutcome.Rejected = PackVerificationOutcome.Rejected(reason, detail)

    companion object {
        private const val CONTENTS_CHECKSUM_FIELD = "contents"

        internal fun sha256Hex(bytes: ByteArray): String = Hashing.hex(HashAlgorithm.SHA_256, bytes)
    }
}
