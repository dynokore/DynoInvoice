package com.dynokore.platform.rulepackkernel

import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.Selector
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.security.Security
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
 * `control-plane-svc`'s side (STK-13: "bundle format and signing", this
 * module's other declared user, INV-MOD-2373) is symmetric but out of this
 * task's declared scope — signing is not this module's job, only verifying.
 */
class BouncyCastlePackVerifier(
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : PackVerifier {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    override fun verify(
        artefact: PackArtefact,
        trustAnchors: TrustAnchors,
    ): PackVerificationOutcome {
        val checksumsNode = parseJson(artefact.checksums)
            ?: return rejected(PackRejectionReason.MANIFEST_MALFORMED, "checksums member is not valid JSON")
        val declaredContentsDigest = checksumsNode.get(CONTENTS_CHECKSUM_FIELD)
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
        val signedData = try {
            CMSSignedData(CMSProcessableByteArray(signedContent), artefact.signature)
        } catch (failure: CMSException) {
            return rejected(
                PackRejectionReason.SIGNATURE_INVALID,
                "not a well-formed CMS SignedData: ${failure.message}",
            )
        }

        val signerInfos = signedData.signerInfos.signers
        if (signerInfos.isEmpty()) {
            return rejected(PackRejectionReason.SIGNATURE_INVALID, "CMS SignedData carries no SignerInfo")
        }
        val signerInfo = signerInfos.first()

        @Suppress("UNCHECKED_CAST") // CS-50: bc-java's SignerId implements the raw (unparameterised) Selector type
        val signerIdSelector = signerInfo.getSID() as Selector<X509CertificateHolder>
        val signerCertificate = signedData.certificates
            .getMatches(signerIdSelector)
            .filterIsInstance<X509CertificateHolder>()
            .firstOrNull()
            ?: return rejected(
                PackRejectionReason.SIGNATURE_INVALID,
                "no certificate in the CMS message matches its own SignerInfo",
            )

        val mathematicallyValid = try {
            val verifier =
                JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(signerCertificate)
            signerInfo.verify(verifier)
        } catch (failure: CMSException) {
            return rejected(PackRejectionReason.SIGNATURE_INVALID, "signature verification failed: ${failure.message}")
        }
        if (!mathematicallyValid) {
            return rejected(
                PackRejectionReason.SIGNATURE_INVALID,
                "CMS signature does not verify against its own signer certificate",
            )
        }

        val signerCertificateDer = try {
            signerCertificate.encoded
        } catch (failure: java.io.IOException) {
            return rejected(
                PackRejectionReason.SIGNATURE_INVALID,
                "signer certificate could not be re-encoded: ${failure.message}",
            )
        }
        val matchedAnchor = trustAnchors.anchors.firstOrNull { it.certificateDer.contentEquals(signerCertificateDer) }
            ?: return rejected(
                PackRejectionReason.UNTRUSTED_SIGNER,
                "signature is mathematically valid but the signer certificate is not one of the given trust anchors",
            )

        val manifestNode = parseJson(artefact.manifest)
            ?: return rejected(PackRejectionReason.MANIFEST_MALFORMED, "manifest member is not valid JSON")
        val manifest = parseManifest(manifestNode, checksumsNode)
            ?: return rejected(
                PackRejectionReason.MANIFEST_MALFORMED,
                "manifest is missing or has malformed required fields",
            )

        return PackVerificationOutcome.Verified(manifest, matchedAnchor.label)
    }

    private fun parseJson(bytes: ByteArray): JsonNode? = try {
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
    ): PackManifest? = try {
        val packId = manifestNode.get("packId").requiredText() ?: return null
        val packType = manifestNode.get("packType").requiredText() ?: return null
        val version = manifestNode.get("version").requiredText() ?: return null
        val minEngineVersion = manifestNode.get("minEngineVersion").requiredText() ?: return null
        val effectiveFrom = manifestNode.get("effectiveFrom").requiredText()?.let(::parseDateOrNull) ?: return null
        val effectiveToNode = manifestNode.get("effectiveTo")
        val effectiveTo = if (effectiveToNode == null || effectiveToNode.isNull) {
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

    private fun parseDateOrNull(text: String): LocalDate? = try {
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

        internal fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
