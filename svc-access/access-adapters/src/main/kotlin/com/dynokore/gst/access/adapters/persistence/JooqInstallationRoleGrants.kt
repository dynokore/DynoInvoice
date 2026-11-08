package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.INSTALLATION_ROLE_GRANT
import org.jooq.DSLContext
import java.time.OffsetDateTime

/** One stored INSTALLATION role grant: the published state of one configured entry (INV-T-9235). */
data class InstallationRoleGrantRow(
    val grantRef: String,
    val subject: String,
    val role: String,
    val active: Boolean,
    val authorityVersion: Long,
    val updatedAt: OffsetDateTime,
)

/**
 * The table `installation_role_grant` (platform data: its row policy admits only a transaction bound to the platform
 * tenant). Every call runs in the caller's transaction.
 */
class JooqInstallationRoleGrants(
    private val dsl: DSLContext,
) {
    /** Every row, ACTIVE and REMOVED, in grant reference order. */
    fun all(): List<InstallationRoleGrantRow> =
        dsl
            .selectFrom(INSTALLATION_ROLE_GRANT)
            .orderBy(INSTALLATION_ROLE_GRANT.GRANT_REF)
            .fetch { row -> toRow(row.grantRef, row.subject, row.role, row.state, row.authorityVersion, row.updatedAt) }

    /** The rows after [after] (exclusive; null: from the start), at most [limit], in grant reference order. */
    fun page(
        after: String?,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        limit: Int,
    ): List<InstallationRoleGrantRow> =
        dsl
            .selectFrom(INSTALLATION_ROLE_GRANT)
            .where(
                listOfNotNull(
                    after?.let { INSTALLATION_ROLE_GRANT.GRANT_REF.gt(it) },
                    from?.let { INSTALLATION_ROLE_GRANT.UPDATED_AT.ge(it) },
                    to?.let { INSTALLATION_ROLE_GRANT.UPDATED_AT.lt(it) },
                ),
            ).orderBy(INSTALLATION_ROLE_GRANT.GRANT_REF)
            .limit(limit)
            .fetch { row -> toRow(row.grantRef, row.subject, row.role, row.state, row.authorityVersion, row.updatedAt) }

    /** Inserts the row, or sets state, version and time of the row with the same reference. */
    fun save(row: InstallationRoleGrantRow) {
        val state = if (row.active) ACTIVE else REMOVED
        dsl
            .insertInto(INSTALLATION_ROLE_GRANT)
            .set(INSTALLATION_ROLE_GRANT.GRANT_REF, row.grantRef)
            .set(INSTALLATION_ROLE_GRANT.SUBJECT, row.subject)
            .set(INSTALLATION_ROLE_GRANT.ROLE, row.role)
            .set(INSTALLATION_ROLE_GRANT.STATE, state)
            .set(INSTALLATION_ROLE_GRANT.AUTHORITY_VERSION, row.authorityVersion)
            .set(INSTALLATION_ROLE_GRANT.UPDATED_AT, row.updatedAt)
            .onConflict(INSTALLATION_ROLE_GRANT.GRANT_REF)
            .doUpdate()
            .set(INSTALLATION_ROLE_GRANT.STATE, state)
            .set(INSTALLATION_ROLE_GRANT.AUTHORITY_VERSION, row.authorityVersion)
            .set(INSTALLATION_ROLE_GRANT.UPDATED_AT, row.updatedAt)
            .execute()
    }

    /** The next value of the authority sequence. */
    fun nextAuthorityVersion(): Long = dsl.nextval(AUTHORITY_VERSION_SEQ)

    private fun toRow(
        ref: String?,
        subject: String?,
        role: String?,
        state: String?,
        version: Long?,
        at: OffsetDateTime?,
    ) = InstallationRoleGrantRow(
        ref.column("grant_ref"),
        subject.column("subject"),
        role.column("role"),
        state.column("state") == ACTIVE,
        version.column("authority_version"),
        at.column("updated_at"),
    )

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val REMOVED = "REMOVED"
    }
}
