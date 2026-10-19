package com.dynokore.gst.controlPlane.adapters.bundle

import com.dynokore.gst.controlPlane.application.pack.PackContentHasher
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.platform.crypto.DetachedSigner
import com.dynokore.platform.crypto.SigningKey
import com.dynokore.platform.rulepackkernel.PackArtefact
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

/** Canonical JSON: map keys sorted at every level, no whitespace, so equal values are equal bytes. */
object CanonicalJson {
    private val json: JsonMapper = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    fun text(value: Any?): String = json.writeValueAsString(value)

    fun bytes(value: Any?): ByteArray = json.writeValueAsBytes(value)
}

/** The three unsigned members of a bundle, as the exact bytes the signature and the content hash cover. */
class BundleMembers(
    val manifest: ByteArray,
    val checksums: ByteArray,
    val contents: ByteArray,
) {
    /** SHA-256 over manifest, checksums and contents in that order: the `packContentHash` (INV-Q-4878). */
    fun contentHash(): ContentHash = ContentHash(sha256Hex(manifest + checksums + contents))

    override fun toString(): String = "BundleMembers(${manifest.size}B, ${checksums.size}B, ${contents.size}B)"
}

internal fun sha256Hex(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/**
 * Builds the bundle of a signed pack version as the four members of rulepack-kernel's `PackArtefact` (STK-13,
 * INV-REQ-1077; INV-Q-7459): the manifest (`packId`, `packType`, `version`, `effectiveFrom`, `minEngineVersion`,
 * `sourceCitation`), the checksums (`{"contents": <SHA-256 hex of the contents bytes>}`), the contents, and a detached
 * CMS signature over the manifest bytes followed by the checksums bytes. The JSON is canonical (keys sorted, no
 * whitespace), so the same version always yields the same bytes and the same hash.
 */
class PackBundles(
    private val signer: DetachedSigner,
) {
    /** The unsigned members of [pack]; the version must carry what a signed version carries. */
    fun members(pack: RegulatoryPack): BundleMembers {
        val contents = checkNotNull(pack.contents) { "${pack.ref} has no contents" }.json.toByteArray(Charsets.UTF_8)
        val manifest =
            mapOf(
                "packId" to pack.ref.value,
                "packType" to pack.type.value,
                "version" to pack.version.value,
                "effectiveFrom" to checkNotNull(pack.effectiveFrom) { "${pack.ref} has no effectiveFrom" }.toString(),
                "minEngineVersion" to
                    checkNotNull(pack.minEngineVersion) { "${pack.ref} has no minEngineVersion" }.value,
                "sourceCitation" to pack.citations,
            )
        return BundleMembers(
            CanonicalJson.bytes(manifest),
            CanonicalJson.bytes(mapOf("contents" to sha256Hex(contents))),
            contents,
        )
    }

    /** The signed bundle of [members] under [key]. */
    fun sign(
        members: BundleMembers,
        key: SigningKey,
    ): PackArtefact =
        PackArtefact(
            manifest = members.manifest,
            checksums = members.checksums,
            signature = signer.sign(members.manifest + members.checksums, key),
            contents = members.contents,
        )

    /** The hash the publication gate computes at the moment of the call over the exact content (INV-Q-4878). */
    fun hasher(): PackContentHasher = PackContentHasher { members(it).contentHash() }
}
