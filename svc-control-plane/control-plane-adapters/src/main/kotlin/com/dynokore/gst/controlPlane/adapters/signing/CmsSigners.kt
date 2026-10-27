package com.dynokore.gst.controlPlane.adapters.signing

import com.dynokore.gst.controlPlane.adapters.bundle.CanonicalJson
import com.dynokore.gst.controlPlane.application.commercial.InvitationIssuer
import com.dynokore.gst.controlPlane.application.commercial.LicenceSigner
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.platform.crypto.DetachedSigner
import java.time.Instant
import java.util.Base64

/**
 * The signed licence file (INV-Q-8679 A): UTF-8 JSON with exactly the members `licence` and `signature`. `licence`
 * holds licenceRef, installationRef, term and issuedAt; `signature` is the base64 of the detached CMS signature that
 * the platform [DetachedSigner] makes over the canonical bytes of `licence` (RFC 8785 for these string members: keys
 * sorted, no whitespace). The key is read by reference through the port of this service (INV-Q-7459); no Cosign.
 */
class CmsLicenceSigner(
    private val signer: DetachedSigner,
    private val keys: PackSigningKeys,
    private val signingKeyRef: String,
) : LicenceSigner {
    override fun sign(
        licenceRef: CommercialRef,
        installationRef: CommercialRef,
        term: String,
        issuedAt: Instant,
    ): ByteArray {
        val licence =
            mapOf(
                "licenceRef" to licenceRef.value,
                "installationRef" to installationRef.value,
                "term" to term,
                "issuedAt" to issuedAt.toString(),
            )
        val key = checkNotNull(keys.resolve(signingKeyRef)) { "the licence signing key is unknown" }
        val signature = Base64.getEncoder().encodeToString(signer.sign(CanonicalJson.bytes(licence), key))
        return CanonicalJson.bytes(mapOf("licence" to licence, "signature" to signature))
    }
}

/**
 * The first invitation reference (INV-AC-935): `<payload>.<signature>`, both base64url, where the payload is the
 * canonical JSON of the contract reference, the tenant reference and the issue and expiry instants, and the
 * signature is the detached CMS signature over the payload bytes. The expiry is inside the signed payload, so it
 * cannot be moved.
 */
class CmsInvitationIssuer(
    private val signer: DetachedSigner,
    private val keys: PackSigningKeys,
    private val signingKeyRef: String,
) : InvitationIssuer {
    override fun issue(
        contractReference: CommercialRef,
        tenantRef: CommercialRef,
        issuedAt: Instant,
        expiresAt: Instant,
    ): String {
        val payload =
            CanonicalJson.bytes(
                mapOf(
                    "contractReference" to contractReference.value,
                    "tenantRef" to tenantRef.value,
                    "issuedAt" to issuedAt.toString(),
                    "expiresAt" to expiresAt.toString(),
                ),
            )
        val key = checkNotNull(keys.resolve(signingKeyRef)) { "the invitation signing key is unknown" }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString(payload) + "." + encoder.encodeToString(signer.sign(payload, key))
    }
}
