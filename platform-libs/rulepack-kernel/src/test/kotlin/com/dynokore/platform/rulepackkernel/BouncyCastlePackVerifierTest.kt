package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.crypto.SignatureAlgorithm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * STK-13 done-when: pack artefact format (manifest, checksums, detached CMS
 * signature, contents) verified with Bouncy Castle against train trust
 * anchors, including a successor key.
 */
class BouncyCastlePackVerifierTest {
    private val verifier = BouncyCastlePackVerifier()

    @Test
    fun `a correctly signed artefact verifies against its own trust anchor`() {
        val keyPair = PackFixtures.signingKeyPair()
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-primary")
        val artefact = PackFixtures.artefact(keyPair = keyPair, certificate = certificate)
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))

        val outcome = verifier.verify(artefact, trustAnchors)

        val verified = assertInstanceOf(PackVerificationOutcome.Verified::class.java, outcome)
        assertEquals("primary", verified.trustedAnchorLabel)
        assertEquals(PackId("code-catalogue-sg"), verified.manifest.packId)
        assertEquals(PackType("code-catalogue"), verified.manifest.packType)
    }

    @Test
    fun `an artefact signed with the pre-published successor key still verifies (STK-13 rotation)`() {
        val successorKeyPair = PackFixtures.signingKeyPair()
        val successorCertificate = PackFixtures.selfSignedCertificate(successorKeyPair, "2026-train-successor")
        val artefact = PackFixtures.artefact(keyPair = successorKeyPair, certificate = successorCertificate)

        val primaryCertificate = PackFixtures.selfSignedCertificate(PackFixtures.signingKeyPair(), "2026-train-primary")
        val trustAnchors =
            TrustAnchors(
                listOf(
                    TrustAnchor("primary", primaryCertificate.encoded),
                    TrustAnchor("successor", successorCertificate.encoded),
                ),
            )

        val outcome = verifier.verify(artefact, trustAnchors)

        val verified = assertInstanceOf(PackVerificationOutcome.Verified::class.java, outcome)
        assertEquals("successor", verified.trustedAnchorLabel)
    }

    @Test
    fun `a signer certificate absent from the trust anchors is refused, even though the maths checks out`() {
        val keyPair = PackFixtures.signingKeyPair()
        val untrustedCertificate = PackFixtures.selfSignedCertificate(keyPair, "not-a-train-key")
        val artefact = PackFixtures.artefact(keyPair = keyPair, certificate = untrustedCertificate)
        val someOtherAnchor = PackFixtures.selfSignedCertificate(PackFixtures.signingKeyPair(), "2026-train-primary")
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", someOtherAnchor.encoded)))

        val outcome = verifier.verify(artefact, trustAnchors)

        val rejected = assertInstanceOf(PackVerificationOutcome.Rejected::class.java, outcome)
        assertEquals(PackRejectionReason.UNTRUSTED_SIGNER, rejected.reason)
    }

    @Test
    fun `contents that do not hash to the declared checksum is refused`() {
        val keyPair = PackFixtures.signingKeyPair()
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-primary")
        val contents = """{"rates":[{"code":"SR","rate":9}]}""".toByteArray()
        val manifest = PackFixtures.manifestJson()
        val wrongChecksums = """{"contents":"${"0".repeat(64)}"}""".toByteArray()
        val signature = PackFixtures.detachedCmsSignature(manifest + wrongChecksums, keyPair, certificate)
        val artefact =
            PackArtefact(manifest = manifest, checksums = wrongChecksums, signature = signature, contents = contents)
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))

        val outcome = verifier.verify(artefact, trustAnchors)

        val rejected = assertInstanceOf(PackVerificationOutcome.Rejected::class.java, outcome)
        assertEquals(PackRejectionReason.CHECKSUM_MISMATCH, rejected.reason)
    }

    @Test
    fun `a signature over the wrong bytes (tampered manifest) is refused`() {
        val keyPair = PackFixtures.signingKeyPair()
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-primary")
        val contents = """{"rates":[{"code":"SR","rate":9}]}""".toByteArray()
        val signedManifest = PackFixtures.manifestJson(version = "2026.10.1")
        val checksums = PackFixtures.checksumsJson(contents)
        val signature = PackFixtures.detachedCmsSignature(signedManifest + checksums, keyPair, certificate)

        val tamperedManifest = PackFixtures.manifestJson(version = "2026.10.2") // signed content no longer matches
        val artefact =
            PackArtefact(manifest = tamperedManifest, checksums = checksums, signature = signature, contents = contents)
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))

        val outcome = verifier.verify(artefact, trustAnchors)

        val rejected = assertInstanceOf(PackVerificationOutcome.Rejected::class.java, outcome)
        assertEquals(PackRejectionReason.SIGNATURE_INVALID, rejected.reason)
    }

    @Test
    fun `a manifest that is not valid JSON is refused`() {
        val keyPair = PackFixtures.signingKeyPair()
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-primary")
        val contents = """{"rates":[{"code":"SR","rate":9}]}""".toByteArray()
        val malformedManifest = "not json".toByteArray()
        val checksums = PackFixtures.checksumsJson(contents)
        val signature = PackFixtures.detachedCmsSignature(malformedManifest + checksums, keyPair, certificate)
        val artefact =
            PackArtefact(
                manifest = malformedManifest,
                checksums = checksums,
                signature = signature,
                contents = contents,
            )
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))

        val outcome = verifier.verify(artefact, trustAnchors)

        val rejected = assertInstanceOf(PackVerificationOutcome.Rejected::class.java, outcome)
        assertEquals(PackRejectionReason.MANIFEST_MALFORMED, rejected.reason)
    }

    @Test
    fun `a pack signed with a key off the P-256 curve is refused even when its certificate is trusted (STK-13)`() {
        val keyPair = PackFixtures.signingKeyPair(curve = "secp384r1")
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-p384")
        val artefact = PackFixtures.artefact(keyPair = keyPair, certificate = certificate)
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("p384", certificate.encoded)))

        val outcome = verifier.verify(artefact, trustAnchors)

        val rejected = assertInstanceOf(PackVerificationOutcome.Rejected::class.java, outcome)
        assertEquals(PackRejectionReason.SIGNATURE_INVALID, rejected.reason)
    }

    @Test
    fun `the configured signature algorithm is the one enforced`() {
        val keyPair = PackFixtures.signingKeyPair()
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-primary")
        val artefact = PackFixtures.artefact(keyPair = keyPair, certificate = certificate)
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))
        val configured =
            BouncyCastlePackVerifier(signatureAlgorithm = SignatureAlgorithm.ECDSA_P256_SHA256)

        assertInstanceOf(PackVerificationOutcome.Verified::class.java, configured.verify(artefact, trustAnchors))
    }
}
