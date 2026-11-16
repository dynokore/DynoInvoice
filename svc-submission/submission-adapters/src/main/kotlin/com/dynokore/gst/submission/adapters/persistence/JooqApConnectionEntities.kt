package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ApConnectionEntity as EntityTable
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ApConnectionVersion as VersionTable

/** The Access Point connection an entity is assigned to, read in the caller's transaction. */
fun interface EntityConnections {
    /** @return the connection of [entityRef] for [tenant], or null when the entity is assigned to none. */
    fun connectionOf(
        tenant: TenantId,
        entityRef: String,
    ): String?
}

/**
 * The replica of `AccessPointRouteChanged` over `submission.ap_connection_entity` and
 * `submission.ap_connection_version` (INV-AC-23662). Versions are per connection: a version is compared only with
 * the stored version of the same connection. Forced row level security applies; values are never logged.
 */
class JooqApConnectionEntities(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : EntityConnections {
    private val e = EntityTable.AP_CONNECTION_ENTITY
    private val v = VersionTable.AP_CONNECTION_VERSION

    override fun connectionOf(
        tenant: TenantId,
        entityRef: String,
    ): String? =
        runner.inTransaction {
            dsl
                .select(e.CONNECTION_REF)
                .from(e)
                .where(e.TENANT_ID.eq(tenant.value))
                .and(e.ENTITY_REF.eq(entityRef))
                .fetchOne(e.CONNECTION_REF)
        }

    /**
     * Applies one delivered event of [connectionRef] with the whole list [entityRefs] and [version], in one
     * transaction.
     * An event whose version is not greater than the stored version of the same connection changes nothing (false).
     * Otherwise the stored version becomes [version], every entity of the list is stored with this connection (an
     * entity stored with another connection moves, whatever that connection's version) and every row still stored with
     * this connection that is not in the list is deleted (true).
     */
    fun apply(
        tenant: TenantId,
        connectionRef: String,
        entityRefs: Collection<String>,
        version: Long,
    ): Boolean =
        runner.inTransaction {
            val advanced =
                dsl
                    .insertInto(v)
                    .set(v.TENANT_ID, tenant.value)
                    .set(v.CONNECTION_REF, connectionRef)
                    .set(v.VERSION, version)
                    .onConflict(v.TENANT_ID, v.CONNECTION_REF)
                    .doUpdate()
                    .set(v.VERSION, version)
                    .where(v.VERSION.lt(version))
                    .execute() == 1
            if (advanced) {
                entityRefs.toSet().forEach { ref ->
                    dsl
                        .insertInto(e)
                        .set(e.TENANT_ID, tenant.value)
                        .set(e.ENTITY_REF, ref)
                        .set(e.CONNECTION_REF, connectionRef)
                        .set(e.CONNECTION_VERSION, version)
                        .onConflict(e.TENANT_ID, e.ENTITY_REF)
                        .doUpdate()
                        .set(e.CONNECTION_REF, connectionRef)
                        .set(e.CONNECTION_VERSION, version)
                        .execute()
                }
                val stale =
                    dsl
                        .deleteFrom(e)
                        .where(e.TENANT_ID.eq(tenant.value))
                        .and(e.CONNECTION_REF.eq(connectionRef))
                if (entityRefs.isEmpty()) stale.execute() else stale.and(e.ENTITY_REF.notIn(entityRefs)).execute()
            }
            advanced
        }
}
