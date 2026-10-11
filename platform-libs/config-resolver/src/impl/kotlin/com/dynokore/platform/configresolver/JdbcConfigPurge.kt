package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantId

/**
 * [ConfigPurge] on the purge role's own transaction of the tenant concerned (a [CurrentConnection] over
 * the purge role's data source): RLS stays forced, so a purge reaches only the bound tenant's rows, and
 * the explicit `tenant_id` predicate is defence in depth (TPL-11). The application role has no DELETE.
 */
class JdbcConfigPurge(
    private val connections: CurrentConnection,
    schema: String,
) : ConfigPurge {
    private val resolved = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.RESOLVED_VALUE}"
    private val settings = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.SETTING}"
    private val acknowledgements = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT}"

    override fun purgeSubject(subject: ResolutionSubject): Int = connections.require().prepareStatement(
        "delete from $resolved where tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid " +
            "and subject_type = ? and subject_ref = ?",
    ).use {
        it.setString(1, subject.type)
        it.setString(2, subject.ref)
        it.executeUpdate()
    }

    override fun purgeTenant(tenant: TenantId): PurgedTenant {
        require(!tenant.isPlatform) { "the platform tenant holds no configuration rows" }
        val connection = connections.require()
        fun delete(table: String): Int = connection.prepareStatement("delete from $table where tenant_id = ?").use {
            it.setObject(1, tenant.value)
            it.executeUpdate()
        }
        return PurgedTenant(
            resolvedValues = delete(resolved),
            settings = delete(settings),
            relaxationAcknowledgements = delete(acknowledgements),
        )
    }
}
