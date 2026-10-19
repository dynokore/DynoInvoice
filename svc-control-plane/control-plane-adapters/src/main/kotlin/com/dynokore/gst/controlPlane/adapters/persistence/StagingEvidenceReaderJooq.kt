package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_STAGING_RECORD
import com.dynokore.gst.controlPlane.application.pack.StagingEvidenceReader
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.StagingEvidence
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import org.jooq.DSLContext

/** Reads the staging evidence records of a pack version. The runtime login holds SELECT on the table only. */
class StagingEvidenceReaderJooq(
    private val dsl: DSLContext,
) : StagingEvidenceReader {
    override fun forPack(ref: PackRef): List<StagingEvidence> =
        dsl
            .selectFrom(PACK_STAGING_RECORD)
            .where(PACK_STAGING_RECORD.PACK_REF.eq(ref.value))
            .orderBy(PACK_STAGING_RECORD.RECORDED_AT.asc(), PACK_STAGING_RECORD.ID.asc())
            .fetch()
            .map {
                StagingEvidence(
                    packRef = PackRef(checkNotNull(it.packRef)),
                    result = StagingResult.valueOf(checkNotNull(it.result)),
                    packContentHash = ContentHash(checkNotNull(it.packContentHash)),
                )
            }
}
