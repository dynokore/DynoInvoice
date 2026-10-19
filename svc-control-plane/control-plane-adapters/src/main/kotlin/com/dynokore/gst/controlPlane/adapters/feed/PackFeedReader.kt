package com.dynokore.gst.controlPlane.adapters.feed

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_RELEASE
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.platform.rulepackkernel.PackArtefact
import org.jooq.DSLContext
import java.time.Instant

/** One row of the release history. */
data class ReleaseRecord(
    val packRef: PackRef,
    val packContentHash: String,
    val supersedes: PackRef?,
    val releasedAt: Instant,
)

/**
 * The read side of the feed and of the offline bundle (INV-REQ-1079): the index document rules-svc polls, the signed
 * bundle of one version and the release history. Both channels read the same stored members, so the same pack version
 * installs identically from both (INV-AC-2074). The index is a schema document, not an HTTP operation.
 */
class PackFeedReader(
    private val dsl: DSLContext,
    private val index: PackFeedIndex,
) {
    /** The index document served now, or null before the first publication. */
    fun index(): String? = index.current()

    /** The signed bundle of [ref], the four members exactly as stored, or null when [ref] is not released. */
    fun bundle(ref: PackRef): PackArtefact? =
        dsl
            .selectFrom(PACK_RELEASE)
            .where(PACK_RELEASE.PACK_REF.eq(ref.value))
            .fetchOne()
            ?.let {
                PackArtefact(
                    manifest = checkNotNull(it.manifest),
                    checksums = checkNotNull(it.checksums),
                    signature = checkNotNull(it.signature),
                    contents = checkNotNull(it.contents),
                )
            }

    /** Every release in release order; a superseded version stays listed (INV-AC-2594). */
    fun history(): List<ReleaseRecord> =
        dsl
            .selectFrom(PACK_RELEASE)
            .orderBy(PACK_RELEASE.RELEASED_AT.asc(), PACK_RELEASE.PACK_REF.asc())
            .fetch()
            .map {
                ReleaseRecord(
                    PackRef(checkNotNull(it.packRef)),
                    checkNotNull(it.packContentHash),
                    it.supersedes?.let(::PackRef),
                    checkNotNull(it.releasedAt).toInstant(),
                )
            }
}
