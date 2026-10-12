package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId

/**
 * [ConfigPurge] through the sys-owned functions [ConfigSchema.PURGE_SUBJECT] and [ConfigSchema.PURGE_TENANT], on the
 * runtime login's transaction of the tenant concerned with the caller's actor bound (INV-ADR-3968 item 6, owner
 * ruling INV-Q-3953; INV-Q-3997). The runtime login has no DELETE: the functions delete, under the tenant policy, only
 * the bound tenant's rows, and refuse an unset `app.actor`, an unbound or platform tenant, and a purge of a tenant
 * other than the bound one. The application checks the operation before it calls them.
 */
class JdbcConfigPurge(
    private val connections: CurrentConnection,
    schema: String,
) : ConfigPurge {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the schema enters SQL text only through SqlIdentifier.require.
    private val purgeSubject = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.PURGE_SUBJECT}"
    private val purgeTenant = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.PURGE_TENANT}"

    override fun purgeSubject(subject: ResolutionSubject): Int = connections.require().prepareStatement(
        "select $purgeSubject(?, ?)",
    ).use {
        it.setString(1, subject.type)
        it.setString(2, subject.ref)
        it.executeQuery().use { rows ->
            check(rows.next()) { "the purge returned no count" }
            Math.toIntExact(rows.getLong(1))
        }
    }

    override fun purgeTenant(tenant: TenantId): PurgedTenant {
        require(!tenant.isPlatform) { "the platform tenant holds no configuration rows" }
        return connections.require().prepareStatement(
            "select resolved_values, settings, relaxation_acknowledgements from $purgeTenant(?)",
        ).use {
            it.setObject(1, tenant.value)
            it.executeQuery().use { rows ->
                check(rows.next()) { "the purge returned no counts" }
                PurgedTenant(
                    resolvedValues = Math.toIntExact(rows.getLong(1)),
                    settings = Math.toIntExact(rows.getLong(2)),
                    relaxationAcknowledgements = Math.toIntExact(rows.getLong(3)),
                )
            }
        }
    }
}
