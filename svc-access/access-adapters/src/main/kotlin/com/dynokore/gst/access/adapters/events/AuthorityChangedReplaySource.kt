package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.column
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.CONSENT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.SERVICE_PRINCIPAL
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.application.grant.RoleGrantState
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.ReplayPage
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * The producer half of the cold-start full sync of AuthorityChanged (INV-REQ-1201, INV-Q-9047 A, INV-T-9061): re-emits
 * the current records of the bound tenant, each with the `authority_version` stored for it (no sequence value is
 * drawn), through the payload builders of the live publishers.
 *
 * - one role-grant event per grant row of status ACTIVE (state ACTIVE) or REVOKED (state REMOVED, always re-emitted);
 *   a PENDING grant is never emitted;
 * - one consentRecord event per client entity: the LIVE row, else the WITHDRAWN row with the highest version;
 * - one principalRecord event per service principal of status REVOKED, valid from the row's `updated_at`.
 *
 * Each state has the envelope of its live event (tenant-wide entity, no docsequence). A row that must be emitted and
 * has no stored version fails the page with an error naming the key, so nothing of that page is written. The scopes
 * TENANT and the full sync emit every record; ENTITY emits the grants and consents that name that entity; PERIOD
 * emits the records last changed in the half-open range.
 */
class AuthorityChangedReplaySource(
    private val dsl: DSLContext,
    mapper: ObjectMapper,
    private val tenantsOf: () -> List<TenantId>,
) : ReplayStateSource {
    private val payloads = AuthorityChangedPayloads(mapper)
    private val consentReader = ConsentReplayReader(dsl, payloads)

    override val eventType: String = PlatformEventCatalogue.AUTHORITY_CHANGED.eventType

    // the platform tenant's rows are replayed by the platform source (INV-T-9235), not here
    override fun tenants(): List<TenantId> = tenantsOf().filterNot { it.isPlatform }

    override fun page(
        scope: ReplayScope?,
        cursor: String?,
        limit: Int,
    ): ReplayPage {
        require(limit >= 1) { "a page holds at least one state" }
        val start = ReplayCursor.parse(cursor)
        val found = mutableListOf<Keyed>()
        for (kind in Kind.entries.filter { it >= start.kind }) {
            val after = start.key.takeIf { kind == start.kind }
            found += read(kind, scope, after, limit + 1 - found.size)
            if (found.size > limit) break
        }
        val items = found.take(limit)
        val next = if (found.size > limit) ReplayCursor.format(items.last().kind.name, items.last().key) else null
        return ReplayPage(items.map { it.state }, next)
    }

    private fun read(
        kind: Kind,
        scope: ReplayScope?,
        after: String?,
        count: Int,
    ): List<Keyed> =
        when (kind) {
            Kind.GRANT -> grants(scope, after, count)
            Kind.CONSENT -> consents(scope, after, count)
            Kind.PRINCIPAL -> principals(scope, after, count)
        }

    private fun grants(
        scope: ReplayScope?,
        after: String?,
        count: Int,
    ): List<Keyed> =
        dsl
            .selectFrom(ROLE_GRANT)
            .where(
                ROLE_GRANT.STATUS.`in`(ACTIVE, REVOKED),
                after?.let { ROLE_GRANT.GRANT_REF.gt(it) } ?: DSL.noCondition(),
                inPeriod(scope, ROLE_GRANT.UPDATED_AT),
                forEntity(scope, ROLE_GRANT.SCOPE_LEVEL.`in`(ENTITY_LEVELS), ROLE_GRANT.SCOPE_REF),
            ).orderBy(ROLE_GRANT.GRANT_REF)
            .limit(count)
            .fetch()
            .map { row ->
                val ref = row.grantRef.column("grant_ref")
                // fail secure: a grant that cannot be replayed must not vanish silently from the page
                val wire = row.role.column("role")
                val role =
                    checkNotNull(
                        Role.ofWire(wire),
                    ) { "the grant $ref has the role '$wire' with no wire form to replay" }
                val version = stored(row.authorityVersion, "role_grant $ref")
                val change =
                    RoleGrantChange(
                        tenant = TenantRef(row.tenantId.column("tenant_id").toString()),
                        identity = IdentityRef(row.identityRef.column("identity_ref")),
                        grantRef = GrantRef(ref),
                        role = role,
                        state = if (row.status == REVOKED) RoleGrantState.REMOVED else RoleGrantState.ACTIVE,
                        scopeLevel = ScopeLevel.valueOf(row.scopeLevel.column("scope_level")),
                        scopeRef = row.scopeRef.column("scope_ref"),
                        validFrom = row.validFrom.column("valid_from").toInstant(),
                        validUntil = row.validUntil?.toInstant(),
                        periodFrom = row.periodFrom,
                        periodTo = row.periodTo,
                        authorityVersion = version,
                    )
                Keyed(Kind.GRANT, ref, state(payloads.roleGrant(change).toString(), change.identity.value))
            }

    private fun consents(
        scope: ReplayScope?,
        after: String?,
        count: Int,
    ): List<Keyed> =
        consentReader.read(scope, after, count).map {
            Keyed(Kind.CONSENT, it.client, state(it.json, it.aggregateKey))
        }

    private fun principals(
        scope: ReplayScope?,
        after: String?,
        count: Int,
    ): List<Keyed> =
        // a revoked principal is always kept: it may be the actor of a request about any entity
        dsl
            .selectFrom(SERVICE_PRINCIPAL)
            .where(
                SERVICE_PRINCIPAL.STATUS.eq(REVOKED),
                after?.let { SERVICE_PRINCIPAL.PRINCIPAL_REF.gt(it) } ?: DSL.noCondition(),
                inPeriod(scope, SERVICE_PRINCIPAL.UPDATED_AT),
            ).orderBy(SERVICE_PRINCIPAL.PRINCIPAL_REF)
            .limit(count)
            .fetch()
            .map { row ->
                val ref = row.principalRef.column("principal_ref")
                val version = stored(row.authorityVersion, "service_principal $ref")
                val from = row.updatedAt.column("updated_at").toInstant()
                Keyed(Kind.PRINCIPAL, ref, state(payloads.principalRevoked(ref, version, from).toString(), ref))
            }

    private fun stored(
        version: Long?,
        key: String,
    ): Long = checkNotNull(version) { "the row $key must be replayed and has no stored authority version" }

    private fun state(
        json: String,
        aggregateKey: String,
    ) = ReplayedState(eventType, json, EventEntityRef.TENANT_WIDE, aggregateKey, docSequence = null)

    private fun inPeriod(
        scope: ReplayScope?,
        updatedAt: org.jooq.Field<java.time.OffsetDateTime?>,
    ): Condition =
        (scope as? ReplayScope.Period)?.let {
            updatedAt
                .ge(it.from.atOffset(ZoneOffset.UTC))
                .and(updatedAt.lt(it.to.atOffset(ZoneOffset.UTC)))
        } ?: DSL.noCondition()

    private fun forEntity(
        scope: ReplayScope?,
        kindCondition: Condition,
        entityColumn: org.jooq.Field<String?>,
    ): Condition =
        // the grants of the entity's own levels are kept when they name it; the grants of every other level
        // (TENANT, GROUP, CONSENT) cover the entity and are always kept
        (scope as? ReplayScope.Entity)?.let {
            val named = kindCondition.and(entityColumn.eq(it.entity.value))
            named.or(kindCondition.not())
        }
            ?: DSL.noCondition()

    internal enum class Kind { GRANT, CONSENT, PRINCIPAL }

    private class Keyed(
        val kind: Kind,
        val key: String,
        val state: ReplayedState,
    )

    private companion object {
        val ENTITY_LEVELS = listOf(ScopeLevel.ENTITY.name, ScopeLevel.ENTITY_PERIOD.name)
        const val ACTIVE = "ACTIVE"
        const val REVOKED = "REVOKED"
    }
}

/** The cursor of a page: the kind of the last record emitted and its key, `GRANT:<ref>` for example. */
internal object ReplayCursor {
    class Start(
        val kind: AuthorityChangedReplaySource.Kind,
        val key: String?,
    )

    fun format(
        kind: String,
        key: String,
    ) = "$kind:$key"

    fun parse(cursor: String?): Start {
        if (cursor == null) return Start(AuthorityChangedReplaySource.Kind.GRANT, null)
        val name = cursor.substringBefore(':')
        val kind =
            requireNotNull(AuthorityChangedReplaySource.Kind.entries.firstOrNull { it.name == name }) {
                "unknown replay cursor"
            }
        return Start(kind, cursor.substringAfter(':'))
    }
}
