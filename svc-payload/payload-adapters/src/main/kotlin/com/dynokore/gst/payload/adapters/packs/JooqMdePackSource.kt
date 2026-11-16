package com.dynokore.gst.payload.adapters.packs

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.MdePackReplica
import com.dynokore.gst.payload.application.ActiveMdePack
import com.dynokore.gst.payload.application.MdePackSource
import org.jooq.DSLContext
import java.util.UUID

/**
 * The active MDE pack of the calling tenant from `payload.mde_pack_replica` (INV-AC-23580), jOOQ only. The tenant is
 * the one bound to the request ([boundTenant]), in the statement as a literal; forced row security is the second
 * line. Only the row with `active = true` is read; none gives null (fail closed). The binding rows are not parsed
 * here: they stay empty until the slice that reads the contents.
 */
class JooqMdePackSource(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : MdePackSource {
    private val table = MdePackReplica.MDE_PACK_REPLICA

    override fun active(): ActiveMdePack? =
        dsl
            .selectFrom(table)
            .where(table.TENANT_ID.eq(boundTenant()).and(table.ACTIVE.isTrue))
            .fetchOne()
            ?.let {
                ActiveMdePack(
                    version = checkNotNull(it.packVersion),
                    contents = checkNotNull(it.contents).data(),
                    rows = emptyList(),
                )
            }
}
