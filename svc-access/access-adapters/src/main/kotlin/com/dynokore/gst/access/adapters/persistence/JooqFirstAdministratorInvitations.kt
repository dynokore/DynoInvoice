package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.FIRST_ADMINISTRATOR_INVITATION
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.application.invitation.FirstAdministratorInvitations
import com.dynokore.gst.access.application.invitation.InvitationRecord
import com.dynokore.gst.access.application.invitation.NewInvitation
import com.dynokore.gst.access.application.invitation.StoredInvitation
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-11. The invitation rows of the bound tenant and the check of the 409 rule over `access.role_grant`. The
 * DSLContext is service-runtime's, wired with the tenancy-jooq listeners; forced row-level security stays behind that.
 */
class JooqFirstAdministratorInvitations(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : FirstAdministratorInvitations {
    override fun lockTenant() {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("access.first-administrator:${tenants.current()}".toByteArray(StandardCharsets.UTF_8))
        val key = ByteBuffer.wrap(digest).long
        dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, DSL.inline(key))).fetch()
    }

    override fun findByKey(key: UUID): StoredInvitation? =
        dsl
            .selectFrom(FIRST_ADMINISTRATOR_INVITATION)
            .where(FIRST_ADMINISTRATOR_INVITATION.PROVISIONING_KEY.eq(key))
            .fetchOne()
            ?.let {
                StoredInvitation(
                    invitationRef = it.invitationRef.column("invitation_ref"),
                    grantRef = it.grantRef.column("grant_ref"),
                    codeKeyVersion = it.codeKeyVersion.column("code_key_version"),
                    requestFingerprint = it.requestFingerprint.column("request_fingerprint"),
                )
            }

    override fun insert(invitation: NewInvitation) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor =
            checkNotNull(tenants.currentActor()) { "no actor is bound: a provisioning runs in a bound transaction" }
        dsl
            .insertInto(FIRST_ADMINISTRATOR_INVITATION)
            .set(FIRST_ADMINISTRATOR_INVITATION.ID, ids.next(clock.instant()))
            .set(FIRST_ADMINISTRATOR_INVITATION.INVITATION_REF, invitation.invitationRef)
            .set(FIRST_ADMINISTRATOR_INVITATION.GRANT_REF, invitation.grantRef)
            .set(FIRST_ADMINISTRATOR_INVITATION.INVITED_EMAIL, invitation.invitedEmail)
            .set(FIRST_ADMINISTRATOR_INVITATION.EXPIRES_AT, invitation.expiresAt.atOffset(ZoneOffset.UTC))
            .set(FIRST_ADMINISTRATOR_INVITATION.CODE_DIGEST, invitation.codeDigest)
            .set(FIRST_ADMINISTRATOR_INVITATION.CODE_KEY_VERSION, invitation.codeKeyVersion)
            .set(FIRST_ADMINISTRATOR_INVITATION.PROVISIONING_KEY, invitation.provisioningKey)
            .set(FIRST_ADMINISTRATOR_INVITATION.REQUEST_FINGERPRINT, invitation.requestFingerprint)
            .set(FIRST_ADMINISTRATOR_INVITATION.CREATED_AT, now)
            .set(FIRST_ADMINISTRATOR_INVITATION.CREATED_BY, actor)
            .set(FIRST_ADMINISTRATOR_INVITATION.UPDATED_AT, now)
            .set(FIRST_ADMINISTRATOR_INVITATION.UPDATED_BY, actor)
            .set(FIRST_ADMINISTRATOR_INVITATION.VERSION, 0L)
            .execute()
    }

    override fun findByRef(invitationRef: String): InvitationRecord? =
        dsl
            .selectFrom(FIRST_ADMINISTRATOR_INVITATION)
            .where(FIRST_ADMINISTRATOR_INVITATION.INVITATION_REF.eq(invitationRef))
            .fetchOne()
            ?.let {
                InvitationRecord(
                    grantRef = it.grantRef.column("grant_ref"),
                    expiresAt = it.expiresAt.column("expires_at").toInstant(),
                    codeDigest = it.codeDigest,
                )
            }

    override fun clearCodeDigest(invitationRef: String) {
        val actor =
            checkNotNull(tenants.currentActor()) { "no actor is bound: an acceptance runs in a bound transaction" }
        val updated =
            dsl
                .update(FIRST_ADMINISTRATOR_INVITATION)
                .setNull(FIRST_ADMINISTRATOR_INVITATION.CODE_DIGEST)
                .set(FIRST_ADMINISTRATOR_INVITATION.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(FIRST_ADMINISTRATOR_INVITATION.UPDATED_BY, actor)
                .set(FIRST_ADMINISTRATOR_INVITATION.VERSION, FIRST_ADMINISTRATOR_INVITATION.VERSION.plus(1L))
                .where(FIRST_ADMINISTRATOR_INVITATION.INVITATION_REF.eq(invitationRef))
                .execute()
        check(updated == 1) { "the invitation row to clear is missing" }
    }

    override fun holdsLiveTenantAdminGrant(): Boolean =
        dsl.fetchExists(
            DSL
                .selectOne()
                .from(ROLE_GRANT)
                .where(ROLE_GRANT.ROLE.eq(Role.TENANT_ADMIN.wire), ROLE_GRANT.STATUS.`in`("ACTIVE", "PENDING")),
        )
}
