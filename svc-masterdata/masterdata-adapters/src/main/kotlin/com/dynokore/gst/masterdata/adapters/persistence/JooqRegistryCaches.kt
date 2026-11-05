package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.DIRECTORY_CACHE
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.REGISTER_CACHE
import com.dynokore.gst.masterdata.application.registry.CachedDirectory
import com.dynokore.gst.masterdata.application.registry.CachedRegister
import com.dynokore.gst.masterdata.application.registry.DirectoryCache
import com.dynokore.gst.masterdata.application.registry.RegisterCache
import com.dynokore.gst.masterdata.domain.registry.RegisterFact
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. The directory cache of the bound tenant: one row per Peppol ID, replaced by a later lookup. */
class JooqDirectoryCache(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : DirectoryCache {
    override fun find(peppolId: String): CachedDirectory? =
        dsl
            .selectFrom(DIRECTORY_CACHE)
            .where(DIRECTORY_CACHE.PEPPOL_ID.eq(peppolId))
            .fetchOne()
            ?.let {
                CachedDirectory(
                    peppolId,
                    it.found.column("found"),
                    it.apReference,
                    it.lookedUpAt.column("looked_up_at").toInstant(),
                    it.recordKey,
                )
            }

    override fun store(entry: CachedDirectory) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val at = entry.lookedUpAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(DIRECTORY_CACHE)
            .set(DIRECTORY_CACHE.ID, ids.next(clock.instant()))
            .set(DIRECTORY_CACHE.PEPPOL_ID, entry.peppolId)
            .set(DIRECTORY_CACHE.FOUND, entry.found)
            .set(DIRECTORY_CACHE.AP_REFERENCE, entry.apReference)
            .set(DIRECTORY_CACHE.LOOKED_UP_AT, at)
            .set(DIRECTORY_CACHE.RECORD_KEY, entry.recordKey)
            .set(DIRECTORY_CACHE.CREATED_AT, now)
            .set(DIRECTORY_CACHE.UPDATED_AT, now)
            .set(DIRECTORY_CACHE.VERSION, 0L)
            .onConflict(DIRECTORY_CACHE.TENANT_ID, DIRECTORY_CACHE.PEPPOL_ID)
            .doUpdate()
            .set(DIRECTORY_CACHE.FOUND, entry.found)
            .set(DIRECTORY_CACHE.AP_REFERENCE, entry.apReference)
            .set(DIRECTORY_CACHE.LOOKED_UP_AT, at)
            .set(DIRECTORY_CACHE.RECORD_KEY, entry.recordKey)
            .set(DIRECTORY_CACHE.UPDATED_AT, now)
            .set(DIRECTORY_CACHE.VERSION, DIRECTORY_CACHE.VERSION.plus(1L))
            .execute()
    }
}

/** TPL-11. The register cache of the bound tenant: one row per UEN, replaced by a later lookup. */
class JooqRegisterCache(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : RegisterCache {
    override fun find(uen: String): CachedRegister? =
        dsl
            .selectFrom(REGISTER_CACHE)
            .where(REGISTER_CACHE.UEN.eq(uen))
            .fetchOne()
            ?.let {
                CachedRegister(
                    uen,
                    RegisterFact(
                        it.found.column("found"),
                        it.status.column("status"),
                        it.gstNumber,
                        it.registeredName,
                        it.asOf,
                    ),
                    it.lookedUpAt.column("looked_up_at").toInstant(),
                    it.recordKey,
                )
            }

    override fun store(entry: CachedRegister) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val at = entry.lookedUpAt.atOffset(ZoneOffset.UTC)
        val fact = entry.fact
        dsl
            .insertInto(REGISTER_CACHE)
            .set(REGISTER_CACHE.ID, ids.next(clock.instant()))
            .set(REGISTER_CACHE.UEN, entry.uen)
            .set(REGISTER_CACHE.FOUND, fact.found)
            .set(REGISTER_CACHE.STATUS, fact.status)
            .set(REGISTER_CACHE.GST_NUMBER, fact.gstNumber)
            .set(REGISTER_CACHE.REGISTERED_NAME, fact.registeredName)
            .set(REGISTER_CACHE.AS_OF, fact.asOf)
            .set(REGISTER_CACHE.LOOKED_UP_AT, at)
            .set(REGISTER_CACHE.RECORD_KEY, entry.recordKey)
            .set(REGISTER_CACHE.CREATED_AT, now)
            .set(REGISTER_CACHE.UPDATED_AT, now)
            .set(REGISTER_CACHE.VERSION, 0L)
            .onConflict(REGISTER_CACHE.TENANT_ID, REGISTER_CACHE.UEN)
            .doUpdate()
            .set(REGISTER_CACHE.FOUND, fact.found)
            .set(REGISTER_CACHE.STATUS, fact.status)
            .set(REGISTER_CACHE.GST_NUMBER, fact.gstNumber)
            .set(REGISTER_CACHE.REGISTERED_NAME, fact.registeredName)
            .set(REGISTER_CACHE.AS_OF, fact.asOf)
            .set(REGISTER_CACHE.LOOKED_UP_AT, at)
            .set(REGISTER_CACHE.RECORD_KEY, entry.recordKey)
            .set(REGISTER_CACHE.UPDATED_AT, now)
            .set(REGISTER_CACHE.VERSION, REGISTER_CACHE.VERSION.plus(1L))
            .execute()
    }
}
