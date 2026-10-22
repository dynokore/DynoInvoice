package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.SERVICE_PRINCIPAL
import com.dynokore.gst.access.application.principal.PrincipalRepository
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.persistence.StaleAggregate
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** TPL-11. The DSLContext adds `tenant_id = <bound tenant>` to every statement; forced RLS stays behind that. */
class JooqPrincipalRepository(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : PrincipalRepository {
    override fun find(ref: String): ServicePrincipal? =
        dsl
            .selectFrom(SERVICE_PRINCIPAL)
            .where(SERVICE_PRINCIPAL.PRINCIPAL_REF.eq(ref))
            .fetchOne()
            ?.let(::toPrincipal)

    override fun findActiveByRepeatKey(
        type: PrincipalType,
        scope: String,
        callingSystem: String?,
    ): ServicePrincipal? =
        dsl
            .selectFrom(SERVICE_PRINCIPAL)
            .where(
                SERVICE_PRINCIPAL.PRINCIPAL_TYPE.eq(type.name),
                SERVICE_PRINCIPAL.SCOPE.eq(scope),
                callingSystem?.let { SERVICE_PRINCIPAL.CALLING_SYSTEM.eq(it) }
                    ?: SERVICE_PRINCIPAL.CALLING_SYSTEM.isNull,
                SERVICE_PRINCIPAL.STATUS.eq(PrincipalStatus.ACTIVE.name),
            ).fetchOne()
            ?.let(::toPrincipal)

    override fun insert(principal: ServicePrincipal) {
        val now = now()
        val actor = actor()
        dsl
            .insertInto(SERVICE_PRINCIPAL)
            .set(SERVICE_PRINCIPAL.ID, ids.next(clock.instant()))
            .set(SERVICE_PRINCIPAL.PRINCIPAL_REF, principal.ref)
            .set(SERVICE_PRINCIPAL.PRINCIPAL_TYPE, principal.type.name)
            .set(SERVICE_PRINCIPAL.SCOPE, principal.scope)
            .set(SERVICE_PRINCIPAL.CALLING_SYSTEM, principal.callingSystem)
            .set(SERVICE_PRINCIPAL.STATUS, principal.status.name)
            .set(SERVICE_PRINCIPAL.CREATED_AT, now)
            .set(SERVICE_PRINCIPAL.CREATED_BY, actor)
            .set(SERVICE_PRINCIPAL.UPDATED_AT, now)
            .set(SERVICE_PRINCIPAL.UPDATED_BY, actor)
            .set(SERVICE_PRINCIPAL.VERSION, principal.version)
            .execute()
    }

    override fun update(
        before: ServicePrincipal,
        after: ServicePrincipal,
    ) {
        require(before.ref == after.ref) { "update changes one principal" }
        val updated =
            dsl
                .update(SERVICE_PRINCIPAL)
                .set(SERVICE_PRINCIPAL.STATUS, after.status.name)
                .set(SERVICE_PRINCIPAL.UPDATED_AT, now())
                .set(SERVICE_PRINCIPAL.UPDATED_BY, actor())
                .set(SERVICE_PRINCIPAL.VERSION, after.version)
                .where(SERVICE_PRINCIPAL.PRINCIPAL_REF.eq(before.ref), SERVICE_PRINCIPAL.VERSION.eq(before.version))
                .execute()
        if (updated != 1) throw StaleAggregate("ServicePrincipal", after.version)
    }

    override fun lock(ref: String): ServicePrincipal? =
        dsl
            .selectFrom(SERVICE_PRINCIPAL)
            .where(SERVICE_PRINCIPAL.PRINCIPAL_REF.eq(ref))
            .forUpdate()
            .fetchOne()
            ?.let(::toPrincipal)

    override fun nextRef(): String =
        "PRN-" +
            ids
                .next(clock.instant())
                .toString()
                .replace("-", "")
                .takeLast(REF_DIGITS)

    override fun nextAuthorityVersion(): Long = dsl.nextval(AUTHORITY_VERSION_SEQ)

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun actor(): String =
        checkNotNull(tenants.currentActor()) { "no actor is bound: a principal change runs in a bound transaction" }

    private fun toPrincipal(row: Record): ServicePrincipal =
        ServicePrincipal(
            ref = row.get(SERVICE_PRINCIPAL.PRINCIPAL_REF).column("principal_ref"),
            tenant = TenantRef(row.get(SERVICE_PRINCIPAL.TENANT_ID).column("tenant_id").toString()),
            type = PrincipalType.valueOf(row.get(SERVICE_PRINCIPAL.PRINCIPAL_TYPE).column("principal_type")),
            scope = row.get(SERVICE_PRINCIPAL.SCOPE).column("scope"),
            callingSystem = row.get(SERVICE_PRINCIPAL.CALLING_SYSTEM),
            status = PrincipalStatus.valueOf(row.get(SERVICE_PRINCIPAL.STATUS).column("status")),
            version = row.get(SERVICE_PRINCIPAL.VERSION).column("version"),
        )

    private companion object {
        const val REF_DIGITS = 16
    }
}
