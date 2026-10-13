package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.Param
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/**
 * The tenant predicates for one bound tenant (INV-ADR-3968 item 4a). The injector builds them and the execute-time
 * check compares against them, so both name exactly the same condition.
 */
internal class Predicates(
    val tenant: TenantId,
) {
    /** The bound tenant as a bind value. */
    fun tenantValue(): Param<UUID> = DSL.`val`(tenant.value, SQLDataType.UUID)

    /** A read of [ref]: its own rows, and for a REFERENCE table also the platform rows. */
    fun read(ref: DataRef): Condition = if (ref.tableClass == TableClass.REFERENCE && !tenant.isPlatform) {
        ref.tenantField().`in`(tenantValue(), DSL.`val`(TenantId.PLATFORM.value, SQLDataType.UUID))
    } else {
        ref.tenantField().eq(tenantValue())
    }

    /** A write of [ref]: only the bound tenant's rows. */
    fun write(ref: DataRef): Condition = ref.tenantField().eq(tenantValue())

    /** The read predicate of [ref] that also keeps the rows a FULL join null-extends on its side. */
    fun tolerant(ref: DataRef): Condition = DSL.or(read(ref), ref.tenantField().isNull)
}

/** A data table whose predicate the enclosing place (WHERE, or an outer join's ON) must carry. */
internal class Pending(
    val ref: DataRef,
    val tolerant: Boolean,
) {
    fun condition(predicates: Predicates): Condition = if (tolerant) predicates.tolerant(ref) else predicates.read(ref)
}
