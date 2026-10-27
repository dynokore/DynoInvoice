package com.dynokore.gst.controlPlane.adapters.commercial

import com.dynokore.gst.controlPlane.adapters.bundle.CanonicalJson
import com.dynokore.gst.controlPlane.adapters.signing.CmsInvitationIssuer
import com.dynokore.gst.controlPlane.adapters.signing.CmsLicenceSigner
import com.dynokore.gst.controlPlane.adapters.testing.SigningFixtures
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedVerification
import com.dynokore.platform.crypto.SignatureAlgorithm
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/**
 * The licence file of INV-Q-8679 A and the first invitation of INV-AC-935, signed by the platform CMS signer and
 * checked
 * by the platform verifier that pack bundles use (BouncyCastleCms), with the public key (certificate) of the port.
 */
class LicenceFileTest {
    private val cms = BouncyCastleCms()
    private val fixtures = SigningFixtures.create()
    private val json = JsonMapper.builder().build()
    private val signer = CmsLicenceSigner(cms, fixtures.keys, fixtures.keyRef)
    private val issuedAt = Instant.parse("2026-10-27T08:00:00Z")

    private fun file(term: String = "2026-10-01/2027-09-30") =
        signer.sign(CommercialRef("LIC-2026-04"), CommercialRef("INST-0091"), term, issuedAt)

    private fun verifies(
        licence: Any?,
        signature: String,
    ) = cms.verify(
        CanonicalJson.bytes(licence),
        Base64.getDecoder().decode(signature),
        SignatureAlgorithm.ECDSA_P256_SHA256,
        listOf(fixtures.certificateDer),
    ) is DetachedVerification.Verified

    private fun parts(bytes: ByteArray): Pair<Map<String, Any?>, String> {
        val root = json.readValue(bytes, Map::class.java) as Map<*, *>
        root.keys shouldBe setOf("licence", "signature")
        @Suppress("UNCHECKED_CAST")
        return (root["licence"] as Map<String, Any?>) to root["signature"] as String
    }

    @Test
    fun `the licence file is UTF-8 JSON with exactly licence and signature, and the verifier accepts it`() {
        val (licence, signature) = parts(file())
        licence.keys shouldBe setOf("licenceRef", "installationRef", "term", "issuedAt")
        licence["issuedAt"] shouldBe "2026-10-27T08:00:00Z"
        verifies(licence, signature) shouldBe true
    }

    @Test
    fun `probe - one changed byte of the term after signing is rejected`() {
        val (licence, signature) = parts(file())
        val tampered = licence + ("term" to "2026-10-01/2027-09-31")
        verifies(tampered, signature) shouldBe false
    }

    @Test
    fun `probe - an extra member in the licence is rejected`() {
        val (licence, signature) = parts(file())
        verifies(licence + ("entitlement" to "unlimited"), signature) shouldBe false
    }

    @Test
    fun `probe - a signature made over the non-canonical text is rejected`() {
        val (licence, _) = parts(file())
        val nonCanonical =
            json.writeValueAsBytes(
                licence.entries.sortedByDescending { it.key }.associate { it.toPair() },
            )
        val signature = Base64.getEncoder().encodeToString(cms.sign(nonCanonical, fixtures.key))
        verifies(licence, signature) shouldBe false
    }

    @Test
    fun `a signature by another key is not trusted`() {
        val (licence, _) = parts(file())
        val other = SigningFixtures.create("KEY-OTHER", "other")
        val foreign = Base64.getEncoder().encodeToString(cms.sign(CanonicalJson.bytes(licence), other.key))
        verifies(licence, foreign) shouldBe false
    }

    @Test
    fun `an unknown signing key reference fails`() {
        val unknown = CmsLicenceSigner(cms, fixtures.keys, "KEY-UNKNOWN")
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            unknown.sign(CommercialRef("LIC-1"), CommercialRef("INST-1"), "2026", issuedAt)
        }
    }

    @Test
    fun `INV-AC-935 the invitation reference is signed over a payload that carries its expiry`() {
        val issuer = CmsInvitationIssuer(cms, fixtures.keys, fixtures.keyRef)
        val expiresAt = issuedAt.plusSeconds(7 * 24 * 3600L)
        val ref = issuer.issue(CommercialRef("CTR-1"), CommercialRef("TEN-1"), issuedAt, expiresAt)
        val (payload, signature) = ref.split(".").map { Base64.getUrlDecoder().decode(it) }
        (
            cms.verify(payload, signature, SignatureAlgorithm.ECDSA_P256_SHA256, listOf(fixtures.certificateDer)) is
                DetachedVerification.Verified
        ) shouldBe true
        val claims = json.readValue(payload, Map::class.java)
        Instant.parse(claims["expiresAt"] as String).isAfter(issuedAt) shouldBe true
        val moved = json.writeValueAsBytes(claims + ("expiresAt" to "2099-01-01T00:00:00Z"))
        (
            cms.verify(moved, signature, SignatureAlgorithm.ECDSA_P256_SHA256, listOf(fixtures.certificateDer)) is
                DetachedVerification.Verified
        ) shouldBe false
    }
}
