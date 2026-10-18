package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Converter
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Name
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * [ConfigPurge] through the sys-owned functions [ConfigSchema.PURGE_SUBJECT] and [ConfigSchema.PURGE_TENANT], called as
 * jOOQ function calls on the DSLContext of tenancy-jooq's factory, in the runtime login's transaction of the tenant
 * concerned with the caller's actor bound (INV-ADR-3968 item 6, owner ruling INV-Q-3953; INV-Q-3997). The runtime login
 * has no DELETE: the functions delete, under the tenant policy, only the bound tenant's rows, and refuse an
 * unbound actor, an unbound or platform tenant, and a purge of a tenant other than the bound one. The application checks
 * the operation before it calls them. The DSLContext runs on the TransactionRunner whose TenantAwareDataSource holds the
 * lane gate, so a lane budget of 0 still refuses (INV-REQ-1072 b5, INV-Q-4502 (A)).
 */
class JdbcConfigPurge(
    private val dsl: DSLContext,
    schema: String,
) : ConfigPurge {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the schema enters a name only through SqlIdentifier.require.
    private val purgeSubject: Name = DSL.name(SqlIdentifier.require(schema, "schema"), ConfigSchema.PURGE_SUBJECT)
    private val purgeTenant: Name = DSL.name(SqlIdentifier.require(schema, "schema"), ConfigSchema.PURGE_TENANT)

    override fun purgeSubject(subject: ResolutionSubject): Int {
        val count =
            dsl
                .select(DSL.function(purgeSubject, SQLDataType.BIGINT, DSL.`val`(subject.type), DSL.`val`(subject.ref)))
                .fetchOne { it.value1() }
        return Math.toIntExact(checkNotNull(count) { "the purge returned no count" })
    }

    override fun purgeTenant(tenant: TenantId): PurgedTenant {
        require(!tenant.isPlatform) { "the platform tenant holds no configuration rows" }
        // The function returns one row of three counts: called as a function, its row is one composite value, `(a,b,c)`.
        val counts =
            dsl
                .select(DSL.function(purgeTenant, COUNTS, DSL.`val`(tenant.value)))
                .fetchOne { it.value1() }
        return checkNotNull(counts) { "the purge returned no counts" }
    }

    private companion object {
        /** `(resolved_values, settings, relaxation_acknowledgements)`: three bigints, so the text has no quoting. */
        val COUNTS: DataType<PurgedTenant> =
            SQLDataType.OTHER.asConvertedDataType(
                object : Converter<Any, PurgedTenant> {
                    override fun from(database: Any?): PurgedTenant? {
                        val text = database?.toString() ?: return null
                        val parts =
                            text
                                .removeSurrounding(
                                    "(",
                                    ")",
                                ).split(',')
                                .map { Math.toIntExact(it.trim().toLong()) }
                        check(parts.size == 3) { "the purge returned $text, not three counts" }
                        return PurgedTenant(parts[0], parts[1], parts[2])
                    }

                    override fun to(user: PurgedTenant?): Any? =
                        user?.let {
                            "(${it.resolvedValues},${it.settings},${it.relaxationAcknowledgements})"
                        }

                    override fun fromType(): Class<Any> = Any::class.java

                    override fun toType(): Class<PurgedTenant> = PurgedTenant::class.java
                },
            )
    }
}
