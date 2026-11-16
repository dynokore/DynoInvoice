package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.GRANT_EXPIRY_ALERT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.GRANT_EXPIRY_NOTICE
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.IDENTITY
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.ExpiringGrant
import com.dynokore.gst.access.application.grant.ExpiryAlertLog
import com.dynokore.gst.access.application.grant.ExpiryNoticeLog
import com.dynokore.gst.access.application.grant.ExpiryScanSource
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * TPL-11. The grants the expiry scan looks at in the bound tenant (the DSLContext adds the tenant to every statement,
 * forced row-level security stays behind it): the ACTIVE grants that have an end date and have not ended, and the
 * ACTIVE external grants that have ended and have no ACC-003 record yet. The grantee kind is read from the identity row,
 * the granting administrator is the `created_by` of the grant.
 *
 * The entity the notices of a grant are filed under (the outbox refuses a domain event under the tenant-wide entity,
 * FR-K.3): the entity of an entity scope, else the smallest reference of the entities the scope covers, else none; a
 * grant with none is skipped by the scan.
 */
class JooqExpiryScan(
    private val dsl: DSLContext,
    private val grants: JooqGrantRepository,
    private val directory: EntityDirectory,
) : ExpiryScanSource {
    override fun due(now: Instant): List<ExpiringGrant> {
        val at = now.atOffset(ZoneOffset.UTC)
        val ended = ROLE_GRANT.VALID_UNTIL.le(at)
        val external = IDENTITY.GRANTEE_KIND.eq(GranteeKind.EXTERNAL.name)
        val unraised =
            DSL.notExists(
                DSL
                    .selectOne()
                    .from(GRANT_EXPIRY_ALERT)
                    .where(GRANT_EXPIRY_ALERT.GRANT_REF.eq(ROLE_GRANT.GRANT_REF)),
            )
        return dsl
            .select(ROLE_GRANT.asterisk(), IDENTITY.GRANTEE_KIND)
            .from(ROLE_GRANT)
            .join(IDENTITY)
            .on(
                IDENTITY.TENANT_ID.eq(ROLE_GRANT.TENANT_ID),
                IDENTITY.IDENTITY_REF.eq(ROLE_GRANT.IDENTITY_REF),
            ).where(
                ROLE_GRANT.STATUS.eq(GrantStatus.ACTIVE.name),
                ROLE_GRANT.VALID_UNTIL.isNotNull,
                ended.not().or(external.and(unraised)),
            ).orderBy(ROLE_GRANT.GRANT_REF)
            .fetch()
            .mapNotNull { row ->
                val grant = grants.toGrant(row) ?: return@mapNotNull null
                ExpiringGrant(
                    grant,
                    IdentityRef(row.get(ROLE_GRANT.CREATED_BY).column("created_by")),
                    filingEntity(grant),
                    row.get(IDENTITY.GRANTEE_KIND) == GranteeKind.EXTERNAL.name,
                )
            }
    }

    private fun filingEntity(grant: Grant): EntityRef? =
        when (val scope = grant.scope) {
            is Scope.Entity -> scope.entity
            is Scope.EntityPeriod -> scope.entity
            else -> directory.entitiesCoveredBy(scope).minByOrNull { it.value }
        }
}

/** TPL-11. The notices recorded per grant and day, in the transaction the caller holds; the unique key refuses a repeat. */
class JooqExpiryNoticeLog(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ExpiryNoticeLog {
    override fun noticedDays(grant: GrantRef): Set<Int> = daysOf(grant, NOTICED)

    override fun supersededDays(grant: GrantRef): Set<Int> = daysOf(grant, SUPERSEDED)

    override fun recordNoticed(
        grant: GrantRef,
        daysBefore: Int,
    ) = record(grant, daysBefore, NOTICED)

    override fun recordSuperseded(
        grant: GrantRef,
        daysBefore: Int,
    ) = record(grant, daysBefore, SUPERSEDED)

    private fun daysOf(
        grant: GrantRef,
        outcome: String,
    ): Set<Int> =
        dsl
            .select(GRANT_EXPIRY_NOTICE.DAYS_BEFORE)
            .from(GRANT_EXPIRY_NOTICE)
            .where(GRANT_EXPIRY_NOTICE.GRANT_REF.eq(grant.value), GRANT_EXPIRY_NOTICE.OUTCOME.eq(outcome))
            .fetch { it.get(GRANT_EXPIRY_NOTICE.DAYS_BEFORE).column("days_before") }
            .toSet()

    private fun record(
        grant: GrantRef,
        daysBefore: Int,
        outcome: String,
    ) {
        val now = clock.instant()
        dsl
            .insertInto(GRANT_EXPIRY_NOTICE)
            .set(GRANT_EXPIRY_NOTICE.ID, ids.next(now))
            .set(GRANT_EXPIRY_NOTICE.GRANT_REF, grant.value)
            .set(GRANT_EXPIRY_NOTICE.DAYS_BEFORE, daysBefore)
            .set(GRANT_EXPIRY_NOTICE.OUTCOME, outcome)
            .set(GRANT_EXPIRY_NOTICE.RECORDED_AT, now.atOffset(ZoneOffset.UTC))
            .execute()
    }

    private companion object {
        const val NOTICED = "NOTICED"
        const val SUPERSEDED = "SUPERSEDED"
    }
}

/** TPL-11. The ACC-003 records, one per grant, in the transaction the caller holds. */
class JooqExpiryAlertLog(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ExpiryAlertLog {
    override fun wasRaised(grant: GrantRef): Boolean =
        dsl.fetchExists(GRANT_EXPIRY_ALERT, GRANT_EXPIRY_ALERT.GRANT_REF.eq(grant.value))

    override fun markRaised(grant: GrantRef) {
        val now = clock.instant()
        dsl
            .insertInto(GRANT_EXPIRY_ALERT)
            .set(GRANT_EXPIRY_ALERT.ID, ids.next(now))
            .set(GRANT_EXPIRY_ALERT.GRANT_REF, grant.value)
            .set(GRANT_EXPIRY_ALERT.RAISED_AT, now.atOffset(ZoneOffset.UTC))
            .execute()
    }
}
