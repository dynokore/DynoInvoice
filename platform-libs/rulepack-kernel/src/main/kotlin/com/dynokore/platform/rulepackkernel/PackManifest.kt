package com.dynokore.platform.rulepackkernel

import java.time.LocalDate

/**
 * A pack artefact's manifest, once parsed and verified — never trusted before
 * [PackVerifier.verify] returns [PackVerificationOutcome.Verified] (STK-13).
 *
 * Framework-free (ARC-16): plain Kotlin types only. Parsing the manifest's raw
 * JSON bytes into this shape is the `-impl` half's job (`BouncyCastlePackVerifier`);
 * this type itself imports no JSON library.
 *
 * [effectiveFrom]/[effectiveTo] are business dates (CS-10: `LocalDate`), read
 * against a document's own transaction date, never the clock (ARC-13,
 * INV-BR-215) — see [PackRegistry.resolve].
 *
 * The primary constructor is private (CS-07: "a value type whose constructor
 * enforces an invariant has a private constructor and `@ConsistentCopyVisibility`")
 * so the cross-field `effectiveTo`-after-`effectiveFrom` invariant cannot be
 * bypassed by `copy()`; construct through the companion's `invoke` instead —
 * `PackManifest(...)` reads identically at every call site either way.
 */
@ConsistentCopyVisibility
data class PackManifest private constructor(
    val packId: PackId,
    val packType: PackType,
    val version: PackVersionLabel,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val minEngineVersion: String,
    val contentChecksums: Map<String, Checksum>,
) {
    companion object {
        operator fun invoke(
            packId: PackId,
            packType: PackType,
            version: PackVersionLabel,
            effectiveFrom: LocalDate,
            effectiveTo: LocalDate? = null,
            minEngineVersion: String,
            contentChecksums: Map<String, Checksum> = emptyMap(),
        ): PackManifest {
            require(minEngineVersion.isNotBlank()) { "minEngineVersion must not be blank" }
            require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) {
                "effectiveTo ($effectiveTo) must be after effectiveFrom ($effectiveFrom) when present"
            }
            return PackManifest(
                packId = packId,
                packType = packType,
                version = version,
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                minEngineVersion = minEngineVersion,
                contentChecksums = contentChecksums.toMap(), // CS-07: copied on construction, never a caller's live map
            )
        }
    }
}
