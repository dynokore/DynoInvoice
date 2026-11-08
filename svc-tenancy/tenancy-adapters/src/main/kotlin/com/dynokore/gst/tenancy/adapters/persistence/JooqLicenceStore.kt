package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INSTALLED_LICENCE
import com.dynokore.gst.tenancy.application.installation.InstalledLicence
import com.dynokore.gst.tenancy.application.installation.LicenceStatus
import com.dynokore.gst.tenancy.application.installation.LicenceStore
import org.jooq.DSLContext
import java.time.ZoneOffset

/** The installed licences (jOOQ only, CS-18), read and written in the platform-bound transaction of the caller. */
class JooqLicenceStore(
    private val dsl: DSLContext,
    private val scope: PlatformScope,
) : LicenceStore {
    override fun putIfAbsent(licence: InstalledLicence): InstalledLicence =
        scope.run {
            dsl
                .insertInto(INSTALLED_LICENCE)
                .set(INSTALLED_LICENCE.LICENCE_ID, licence.licenceId)
                .set(INSTALLED_LICENCE.STATUS, licence.status.name)
                .set(INSTALLED_LICENCE.EXPIRES_AT, licence.expiresAt)
                .set(INSTALLED_LICENCE.INSTALLED_AT, licence.installedAt.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute()
            checkNotNull(
                dsl
                    .selectFrom(INSTALLED_LICENCE)
                    .where(INSTALLED_LICENCE.LICENCE_ID.eq(licence.licenceId))
                    .fetchOne()
                    ?.let(::licence),
            )
        }

    override fun latest(): InstalledLicence? =
        scope.run {
            dsl
                .selectFrom(INSTALLED_LICENCE)
                .orderBy(INSTALLED_LICENCE.SEQ.desc())
                .limit(1)
                .fetchOne()
                ?.let(::licence)
        }

    override fun count(): Int = scope.run { dsl.fetchCount(INSTALLED_LICENCE) }

    private fun licence(row: com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.records.InstalledLicenceRecord) =
        InstalledLicence(
            checkNotNull(row.licenceId),
            LicenceStatus.valueOf(checkNotNull(row.status)),
            checkNotNull(row.expiresAt),
            checkNotNull(row.installedAt).toInstant(),
        )
}
