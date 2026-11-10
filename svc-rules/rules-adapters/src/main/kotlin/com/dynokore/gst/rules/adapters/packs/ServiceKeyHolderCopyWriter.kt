package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.HolderCopyPort
import com.dynokore.gst.rules.application.HolderCopyRequest
import com.dynokore.gst.rules.application.HolderCopyResult
import com.dynokore.platform.crypto.DetachedSigner
import com.dynokore.platform.crypto.HashAlgorithm
import com.dynokore.platform.crypto.Hashing
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackRegistry
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.PackVerifier
import com.dynokore.platform.rulepackkernel.VerifiedPack
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/**
 * Signs the holder copy of an activated tenant mapping pack with the service key of rules-svc and registers it
 * (INV-REQ-1195; INV-Q-14650 A, INV-Q-16531 A): manifest and checksums are built here, the detached CMS signature
 * (RFC 5652) covers the manifest followed by the checksums, and the artefact is verified against the anchors before it
 * is written (a certificate that does not belong to the key is refused). Every failure to sign answers
 * [HolderCopyResult.Unavailable] and writes nothing (fail closed); the log names the secret, never a key byte.
 *
 * [canonical] gives the text a `jsonb` column returns for a JSON text: the stored members are read back in that form,
 * so the signature and the checksum are made over it and the artefact still verifies after the round trip.
 */
class ServiceKeyHolderCopyWriter(
    private val keys: HolderCopySigningKeys,
    private val signer: DetachedSigner,
    private val verifier: PackVerifier,
    private val registry: PackRegistry,
    private val canonical: (String) -> String,
    private val log: Logger = LoggerFactory.getLogger(ServiceKeyHolderCopyWriter::class.java),
) : HolderCopyPort {
    private val json = JsonMapper()

    override fun write(request: HolderCopyRequest): HolderCopyResult {
        val material = materialOrNull() ?: return HolderCopyResult.Unavailable
        val signed = sign(request, material)
        signed?.let { registry.register(VerifiedPack.of(it.second, it.first, request.tenant)) }
        return if (signed == null) HolderCopyResult.Unavailable else HolderCopyResult.Written
    }

    private fun materialOrNull(): HolderCopyKeyMaterial? =
        try {
            keys.load()
        } catch (e: HolderCopyKeyUnavailable) {
            log.warn("{}", e.message)
            null
        }

    /** The signed artefact and its verification against the anchors, or null when the key and certificate disagree. */
    private fun sign(
        request: HolderCopyRequest,
        material: HolderCopyKeyMaterial,
    ): Pair<PackArtefact, PackVerificationOutcome.Verified>? {
        val contentsBytes = canonical(String(request.contents, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val checksums =
            canonical(json.writeValueAsString(mapOf("contents" to Hashing.hex(HashAlgorithm.SHA_256, contentsBytes))))
                .toByteArray(Charsets.UTF_8)
        val manifest = canonical(json.writeValueAsString(manifestOf(request))).toByteArray(Charsets.UTF_8)
        val signature = signer.sign(manifest + checksums, material.signingKey)
        val artefact = PackArtefact(manifest, checksums, signature, contentsBytes)
        val outcome = verifier.verify(artefact, material.anchors)
        if (outcome is PackVerificationOutcome.Verified && outcome.trustedAnchorLabel == material.signingLabel) {
            return artefact to outcome
        }
        log.warn(
            "holder copy signing is unavailable: secret '{}' does not belong to secret '{}'",
            HolderCopySigningKeys.CERTIFICATE_SECRET,
            HolderCopySigningKeys.KEY_SECRET,
        )
        return null
    }

    private fun manifestOf(request: HolderCopyRequest): Map<String, String?> =
        linkedMapOf(
            "packId" to request.packId,
            "packType" to request.packType,
            "version" to request.versionLabel,
            "effectiveFrom" to request.effectiveFrom.toString(),
            "effectiveTo" to request.effectiveTo?.toString(),
            "minEngineVersion" to request.minEngineVersion,
        )
}
