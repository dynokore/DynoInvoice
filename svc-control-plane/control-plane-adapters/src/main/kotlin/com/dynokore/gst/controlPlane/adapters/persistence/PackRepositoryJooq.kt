package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_AUTHOR
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_VERSION
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.SIGNING_RECORD
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import org.jooq.DSLContext
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Pack versions in the `controlplane` schema through jOOQ (TPL-11, INV-T-4201). Runs in the open transaction of the
 * command, on the DSLContext of tenancy-jooq: the registry is platform data, so the transaction is bound to the
 * reserved platform tenant by the caller.
 */
class PackRepositoryJooq(
    private val dsl: DSLContext,
    private val clock: Clock,
) : PackRepository {
    override fun find(ref: PackRef): RegulatoryPack? {
        val row = dsl.selectFrom(PACK_VERSION).where(PACK_VERSION.PACK_REF.eq(ref.value)).fetchOne() ?: return null
        val authors =
            dsl
                .select(PACK_AUTHOR.ACTOR)
                .from(PACK_AUTHOR)
                .where(PACK_AUTHOR.PACK_REF.eq(ref.value))
                .fetch(PACK_AUTHOR.ACTOR)
                .map { ActorId(checkNotNull(it)) }
                .toSet()
        return RegulatoryPack.restore(
            type = PackType(checkNotNull(row.packType)),
            version = PackVersion(checkNotNull(row.packVersion)),
            citations = checkNotNull(row.citations).map { checkNotNull(it) },
            authors = authors,
            status = PackStatus.valueOf(checkNotNull(row.status)),
            signer = row.signer?.let(::ActorId),
            signingKeyRef = row.signingKeyRef,
            minEngineVersion = row.minEngineVersion?.let(::MinEngineVersion),
            exposureReducing = checkNotNull(row.exposureReducing),
            signedAt = row.signedAt?.toInstant(),
            publishedAt = row.publishedAt?.toInstant(),
        )
    }

    override fun save(pack: RegulatoryPack) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        insertVersion(pack, now)
        pack.authors.forEach { insertAuthor(pack.ref, it, now) }
        pack.signer?.let { insertSigningRecord(pack, it) }
        updateVersion(pack)
    }

    private fun insertVersion(
        pack: RegulatoryPack,
        now: OffsetDateTime,
    ) {
        dsl
            .insertInto(PACK_VERSION)
            .set(PACK_VERSION.PACK_REF, pack.ref.value)
            .set(PACK_VERSION.PACK_TYPE, pack.type.value)
            .set(PACK_VERSION.PACK_VERSION_, pack.version.value)
            .set(PACK_VERSION.STATUS, PackStatus.DRAFT.name)
            .set(PACK_VERSION.CITATIONS, pack.citations.toTypedArray())
            .set(PACK_VERSION.EXPOSURE_REDUCING, false)
            .set(PACK_VERSION.CREATED_AT, now)
            .onConflict(PACK_VERSION.PACK_REF)
            .doNothing()
            .execute()
    }

    private fun insertAuthor(
        ref: PackRef,
        author: ActorId,
        now: OffsetDateTime,
    ) {
        dsl
            .insertInto(PACK_AUTHOR)
            .set(PACK_AUTHOR.PACK_REF, ref.value)
            .set(PACK_AUTHOR.ACTOR, author.value)
            .set(PACK_AUTHOR.RECORDED_AT, now)
            .onConflict(PACK_AUTHOR.PACK_REF, PACK_AUTHOR.ACTOR)
            .doNothing()
            .execute()
    }

    private fun insertSigningRecord(
        pack: RegulatoryPack,
        signer: ActorId,
    ) {
        dsl
            .insertInto(SIGNING_RECORD)
            .set(SIGNING_RECORD.PACK_REF, pack.ref.value)
            .set(SIGNING_RECORD.SIGNER, signer.value)
            .set(SIGNING_RECORD.SIGNING_KEY_REF, checkNotNull(pack.signingKeyRef))
            .set(SIGNING_RECORD.SIGNED_AT, checkNotNull(pack.signedAt).atOffset(ZoneOffset.UTC))
            .onConflict(SIGNING_RECORD.PACK_REF)
            .doNothing()
            .execute()
    }

    private fun updateVersion(pack: RegulatoryPack) {
        dsl
            .update(PACK_VERSION)
            .set(PACK_VERSION.STATUS, pack.status.name)
            .set(PACK_VERSION.MIN_ENGINE_VERSION, pack.minEngineVersion?.value)
            .set(PACK_VERSION.EXPOSURE_REDUCING, pack.exposureReducing)
            .set(PACK_VERSION.SIGNER, pack.signer?.value)
            .set(PACK_VERSION.SIGNING_KEY_REF, pack.signingKeyRef)
            .set(PACK_VERSION.SIGNED_AT, pack.signedAt?.atOffset(ZoneOffset.UTC))
            .set(PACK_VERSION.PUBLISHED_AT, pack.publishedAt?.atOffset(ZoneOffset.UTC))
            .where(PACK_VERSION.PACK_REF.eq(pack.ref.value))
            .execute()
    }
}
