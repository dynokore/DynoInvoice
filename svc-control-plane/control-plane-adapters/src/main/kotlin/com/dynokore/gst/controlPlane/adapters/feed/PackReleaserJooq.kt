package com.dynokore.gst.controlPlane.adapters.feed

import com.dynokore.gst.controlPlane.adapters.bundle.PackBundles
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_RELEASE
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_VERSION
import com.dynokore.gst.controlPlane.application.pack.PackReleaser
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/**
 * Releases a published version to the feed and as a bundle, in the command transaction (INV-REQ-1077, INV-REQ-1079):
 * builds and signs the bundle under the key the signature named, appends the release (bundle members, content hash,
 * superseded version) and writes the feed index of every released version. The release row is the release history:
 * a superseded version stays in it, in the index and as a bundle (INV-AC-2594).
 */
class PackReleaserJooq(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val bundles: PackBundles,
    private val keys: PackSigningKeys,
    private val index: PackFeedIndex,
) : PackReleaser {
    override fun release(
        pack: RegulatoryPack,
        contentHash: ContentHash,
    ) {
        val members = bundles.members(pack)
        check(members.contentHash() == contentHash) { "the content changed between the gate and the release of ${pack.ref}" }
        val key = checkNotNull(keys.resolve(checkNotNull(pack.signingKeyRef))) { "no signing key for ${pack.ref}" }
        val artefact = bundles.sign(members, key)
        dsl
            .insertInto(PACK_RELEASE)
            .set(PACK_RELEASE.PACK_REF, pack.ref.value)
            .set(PACK_RELEASE.PACK_CONTENT_HASH, contentHash.hex)
            .set(PACK_RELEASE.SUPERSEDES, pack.supersedes?.value)
            .set(PACK_RELEASE.MANIFEST, artefact.manifest)
            .set(PACK_RELEASE.CHECKSUMS, artefact.checksums)
            .set(PACK_RELEASE.SIGNATURE, artefact.signature)
            .set(PACK_RELEASE.CONTENTS, artefact.contents)
            .set(PACK_RELEASE.RELEASED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()
        index.write(entries(pack))
    }

    /** Every released version in release order, plus [pack], whose own row is saved after the release. */
    private fun entries(pack: RegulatoryPack): List<FeedEntry> =
        dsl
            .select(
                PACK_VERSION.PACK_REF,
                PACK_VERSION.PACK_TYPE,
                PACK_VERSION.PACK_VERSION_,
                PACK_VERSION.EFFECTIVE_FROM,
                PACK_VERSION.MIN_ENGINE_VERSION,
                PACK_RELEASE.PACK_CONTENT_HASH,
                PACK_RELEASE.SUPERSEDES,
            ).from(PACK_RELEASE)
            .join(PACK_VERSION)
            .on(PACK_VERSION.PACK_REF.eq(PACK_RELEASE.PACK_REF))
            .orderBy(PACK_RELEASE.RELEASED_AT.asc(), PACK_RELEASE.PACK_REF.asc())
            .fetch()
            .map {
                FeedEntry(
                    packRef = checkNotNull(it[PACK_VERSION.PACK_REF]),
                    packType = checkNotNull(it[PACK_VERSION.PACK_TYPE]),
                    version = checkNotNull(it[PACK_VERSION.PACK_VERSION_]),
                    effectiveFrom = checkNotNull(it[PACK_VERSION.EFFECTIVE_FROM]).toString(),
                    minEngineVersion = checkNotNull(it[PACK_VERSION.MIN_ENGINE_VERSION]),
                    packContentHash = checkNotNull(it[PACK_RELEASE.PACK_CONTENT_HASH]),
                    supersedes = it[PACK_RELEASE.SUPERSEDES],
                )
            }.also { check(it.any { e -> e.packRef == pack.ref.value }) { "the release of ${pack.ref} is not in its own index" } }
}
