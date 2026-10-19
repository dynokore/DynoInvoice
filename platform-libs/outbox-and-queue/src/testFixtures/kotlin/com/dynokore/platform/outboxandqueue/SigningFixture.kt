package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.util.Locale
import java.util.UUID

/**
 * The signed tenant binding of the fixture services (INV-T-4156 slice 6, INV-REQ-854 b7, owner ruling INV-Q-4121): the
 * installation key the fixture's runners sign with and every fixture schema's `bound_tenant()` / `bound_actor()`
 * verify,
 * and the statements a test uses where it binds a plain connection itself.
 */
object SigningFixture {
    private val key = SignedBinding.key()

    /** The fixture's key source: the runners of the fixture and of the tests sign with it. */
    fun keys(): BindingKeySource = SignedBinding.keys(key)

    /** The `app.binding_sig` value of [tenant] and [actor], as the signing runner would set it. */
    fun signed(
        tenant: UUID,
        actor: String = OutboxPostgresFixture.WORKLOAD,
    ): String = SignedBinding.value(key, TenantId(tenant), actor)

    /**
     * Binds [connection] as a signing runner does, in one statement: tenant, actor and `app.binding_sig`, for the
     * current
     * transaction ([transactionLocal]) or the session. [signature] defaults to the valid one; a test passes a forged
     * value.
     */
    fun bind(
        connection: Connection,
        tenant: UUID,
        actor: String = OutboxPostgresFixture.WORKLOAD,
        transactionLocal: Boolean = true,
        signature: String = signed(tenant, actor),
    ) {
        val sql =
            if (transactionLocal) {
                "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                    "set_config('app.binding_sig', ?, true)"
            } else {
                "select set_config('app.tenant_id', ?, false), set_config('app.actor', ?, false), " +
                    "set_config('app.binding_sig', ?, false)"
            }
        connection.prepareStatement(sql).use {
            it.setString(1, tenant.toString())
            it.setString(2, actor)
            it.setString(3, signature)
            it.execute()
        }
    }

    /**
     * Provisioning SQL, run as the superuser: pgcrypto, then the binding key table and the two functions in [schema]
     * from tenancy-kernel's generator, created by [sysRole] (which holds USAGE and CREATE on the schema for this script
     * only), and
     * the fixture key. [runtimeRole] gets EXECUTE.
     */
    fun provisioning(
        schema: String,
        sysRole: String,
        runtimeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val hex = key.joinToString("") { "%02x".format(Locale.ROOT, it) }
        return listOf(
            "RESET ROLE;",
            "create extension if not exists pgcrypto schema public;",
            "grant usage, create on schema $s to $sys;",
            "SET ROLE $sys;",
            BindingKeyMigration.ddl(s, sys, runtimeRole),
            "insert into $s.binding_key (slot, secret) values ('current', decode('$hex', 'hex'));",
            "RESET ROLE;",
            "revoke create on schema $s from $sys;",
        ).joinToString("\n")
    }
}
