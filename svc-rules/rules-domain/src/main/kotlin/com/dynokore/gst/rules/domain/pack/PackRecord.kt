package com.dynokore.gst.rules.domain.pack

import java.time.LocalDate

/**
 * One pack version as the registry knows it: identity, layer, effective dates, activation state and the entries that
 * carry its sources (INV-REQ-1196: metadata and state, not an artefact others read).
 *
 * The primary constructor is private so the supersession invariant cannot be bypassed by `copy()`.
 */
@ConsistentCopyVisibility
data class PackRecord private constructor(
    val packType: String,
    val version: String,
    val layer: PackLayer,
    val tenant: String?,
    val status: PackStatus,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val supersedes: String?,
    val entries: List<PackEntry>,
) {
    /** The outcome of an attempt to change a version in place (INV-REQ-773, INV-AC-1726). */
    sealed interface Edit {
        data class Edited(val record: PackRecord) : Edit

        /** An armed pack refuses edits; the correction is a new version ([correct]). */
        data class Refused(val reason: String) : Edit
    }

    fun edit(newEntries: List<PackEntry>): Edit =
        if (status.frozen) {
            Edit.Refused("pack $packType $version is $status and never edited in place; author a superseding version")
        } else {
            Edit.Edited(copy(entries = newEntries.toList()))
        }

    /**
     * The correction of this version: a new DRAFT version that names this one in `supersedes` and has its own
     * effective date (INV-REQ-773 behaviour 2). This version is untouched, stays in the registry and stays
     * resolvable for the dates before the correction's `effectiveFrom` (INV-REQ-1089).
     */
    fun correct(
        newVersion: String,
        newEffectiveFrom: LocalDate,
        newEntries: List<PackEntry>,
    ): PackRecord {
        require(newVersion != version) { "a correction is a new version, not $version again" }
        require(newEffectiveFrom.isAfter(effectiveFrom)) {
            "a correction takes its own effective date after $effectiveFrom, not $newEffectiveFrom"
        }
        return PackRecord(packType, newVersion, layer, tenant, PackStatus.DRAFT, newEffectiveFrom, null, version, newEntries.toList())
    }

    /** Arms a version that has been approved (mapping) or acknowledged (regulatory); there is no way back. */
    fun arm(): PackRecord {
        require(status == PackStatus.APPROVED || status == PackStatus.ACKNOWLEDGED) { "cannot arm a pack that is $status" }
        return copy(status = PackStatus.ARMED)
    }

    companion object {
        fun draft(
            packType: String,
            version: String,
            layer: PackLayer,
            tenant: String?,
            effectiveFrom: LocalDate,
            entries: List<PackEntry>,
        ): PackRecord {
            require(packType.isNotBlank() && version.isNotBlank()) { "packType and version must not be blank" }
            require((layer == PackLayer.TENANT_MAPPING) == (tenant != null)) {
                "a tenant mapping pack names its tenant and a regulatory pack is platform-wide"
            }
            return PackRecord(packType, version, layer, tenant, PackStatus.DRAFT, effectiveFrom, null, null, entries.toList())
        }

        /** A version rebuilt from the registry in the given state (adapters only). */
        fun restored(
            packType: String,
            version: String,
            layer: PackLayer,
            tenant: String?,
            status: PackStatus,
            effectiveFrom: LocalDate,
            effectiveTo: LocalDate?,
            supersedes: String?,
            entries: List<PackEntry>,
        ): PackRecord = PackRecord(packType, version, layer, tenant, status, effectiveFrom, effectiveTo, supersedes, entries.toList())
    }
}
