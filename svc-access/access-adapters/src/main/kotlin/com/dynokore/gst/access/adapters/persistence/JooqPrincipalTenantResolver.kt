package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.application.principal.PrincipalTenantResolver
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * TPL-11. Calls `access.resolve_principal_tenant(principal_ref)` (owned by the NOLOGIN `access_sys`, executable by
 * `access_runtime` only) in a read transaction, under the platform binding the caller holds (INV-Q-8982 A).
 */
class JooqPrincipalTenantResolver(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
) : PrincipalTenantResolver {
    override fun tenantOf(principalRef: String): TenantId? =
        tx.inTransaction {
            val resolve =
                DSL.function(DSL.name("access", FUNCTION), SQLDataType.UUID, DSL.`val`(principalRef, SQLDataType.CLOB))
            dsl
                .select(resolve)
                .fetchOne()
                ?.get(resolve)
                ?.let { TenantId(it) }
        }

    companion object {
        const val FUNCTION = "resolve_principal_tenant"
    }
}
