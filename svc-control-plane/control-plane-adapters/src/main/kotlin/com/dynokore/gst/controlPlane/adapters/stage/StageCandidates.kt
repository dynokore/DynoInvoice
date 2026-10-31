package com.dynokore.gst.controlPlane.adapters.stage

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_VERSION
import com.dynokore.gst.controlPlane.application.pack.PackContentHasher
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.application.pack.StagingEvidenceReader
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import org.jooq.DSLContext

/**
 * The pack versions the stage job runs (coordinator ruling INV-Q-8725): every SIGNED version with no staging record for
 * its current content hash, and every SIGNED version whose latest record is PENDING. A record counts only when it
 * belongs to the same version (exact [PackRef] equality, read per version) and carries the hash of the content as it is
 * now, so a content change after a PASSED record selects the version again.
 */
class StageCandidates(
    private val dsl: DSLContext,
    private val packs: PackRepository,
    private val hasher: PackContentHasher,
    private val evidence: StagingEvidenceReader,
) {
    fun select(): List<PackRef> =
        signedRefs().filter { ref ->
            val pack = packs.find(ref)
            pack != null && pack.status == PackStatus.SIGNED && needsStage(ref, pack)
        }

    private fun needsStage(
        ref: PackRef,
        pack: RegulatoryPack,
    ): Boolean {
        val hash = hasher.hash(pack)
        val records = evidence.forPack(ref).filter { it.packRef == ref }
        return records.none { it.packContentHash == hash } || records.lastOrNull()?.result == StagingResult.PENDING
    }

    private fun signedRefs(): List<PackRef> =
        dsl
            .select(PACK_VERSION.PACK_REF)
            .from(PACK_VERSION)
            .where(PACK_VERSION.STATUS.eq(PackStatus.SIGNED.name))
            .orderBy(PACK_VERSION.SIGNED_AT.asc(), PACK_VERSION.PACK_REF.asc())
            .fetch()
            .map { PackRef(checkNotNull(it.value1())) }
}
