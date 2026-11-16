package com.dynokore.gst.payload.adapters.packs

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.MdePackReplica
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/** What [JooqMdePackReplica.stage] did with a staged version. */
enum class StageResult { STORED, UNCHANGED, CONFLICT }

/** The active MDE pack version of a tenant with its contents (JSON text). */
data class ReplicaPack(
    val version: String,
    val contentsJson: String,
)

/**
 * The replica of the MDE pack versions over `payload.mde_pack_replica` (INV-AC-23578), jOOQ only. It opens no
 * transaction: every write commits or rolls back with the inbox consumer's transaction. The tenant is the one bound to
 * the request ([boundTenant]), in every statement as a literal; forced row security is the second line.
 */
class JooqMdePackReplica(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) {
    private val table = MdePackReplica.MDE_PACK_REPLICA

    /**
     * Inserts the version once. A second delivery with equal contents (jsonb equality) is [StageResult.UNCHANGED]; the
     * same version with other contents is [StageResult.CONFLICT] and nothing is written: a staged version is never
     * overwritten.
     */
    fun stage(
        version: String,
        contentsJson: String,
    ): StageResult {
        val contents = DSL.cast(contentsJson, SQLDataType.JSONB)
        val inserted =
            dsl
                .insertInto(table)
                .set(table.TENANT_ID, boundTenant())
                .set(table.PACK_VERSION, version)
                .set(table.CONTENTS, contents)
                .onConflictDoNothing()
                .execute() == 1
        if (inserted) return StageResult.STORED
        val same =
            dsl.fetchExists(
                table,
                table.TENANT_ID
                    .eq(boundTenant())
                    .and(table.PACK_VERSION.eq(version))
                    .and(table.CONTENTS.eq(contents)),
            )
        return if (same) StageResult.UNCHANGED else StageResult.CONFLICT
    }

    /**
     * Marks exactly [version] active and every other version of the tenant inactive. A version that is not staged is
     * never invented: [IllegalStateException], so the inbox retries it. Returns true when a row changed.
     */
    fun activate(version: String): Boolean {
        check(
            dsl.fetchExists(table, table.TENANT_ID.eq(boundTenant()).and(table.PACK_VERSION.eq(version))),
        ) { "the pack version is not staged yet" }
        return dsl
            .update(table)
            .set(table.ACTIVE, table.PACK_VERSION.eq(version))
            .where(table.TENANT_ID.eq(boundTenant()))
            .and(table.ACTIVE.ne(table.PACK_VERSION.eq(version)))
            .execute() > 0
    }

    /** The active version with its contents, or null when none is active for the tenant. */
    fun active(): ReplicaPack? =
        dsl
            .selectFrom(table)
            .where(table.TENANT_ID.eq(boundTenant()).and(table.ACTIVE.isTrue))
            .fetchOne()
            ?.let { ReplicaPack(checkNotNull(it.packVersion), checkNotNull(it.contents).data()) }

    /** The contents of a staged version of the tenant, or null. */
    fun contentsOf(version: String): String? =
        dsl
            .selectFrom(table)
            .where(table.TENANT_ID.eq(boundTenant()).and(table.PACK_VERSION.eq(version)))
            .fetchOne()
            ?.contents
            ?.data()
}
