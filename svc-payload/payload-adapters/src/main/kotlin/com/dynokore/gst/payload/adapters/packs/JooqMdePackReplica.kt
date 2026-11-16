package com.dynokore.gst.payload.adapters.packs

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.MdePackReplica
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.PackIdentity
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/** What [JooqMdePackReplica.stage] did with a staged version. */
enum class StageResult { STORED, UNCHANGED, CONFLICT }

/** What [JooqMdePackReplica.recordIdentity] did with the (identifier, type) pair of a staged pack. */
enum class IdentityResult { RECORDED, SAME, CONFLICT }

/** The active MDE pack version of a tenant with its contents (JSON text). */
data class ReplicaPack(
    val packIdentifier: String,
    val version: String,
    val contentsJson: String,
)

/**
 * The replica of the MDE pack versions over `payload.mde_pack_replica` and the identity table `payload.pack_identity`
 * (INV-AC-23578), jOOQ only. It opens no transaction: every write commits or rolls back with the inbox consumer's
 * transaction. The tenant is the one bound to the request ([boundTenant]), in every statement as a literal; forced row
 * security is the second line. A version string never selects a row on its own: every statement names the pack
 * identifier as well.
 */
class JooqMdePackReplica(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) {
    private val table = MdePackReplica.MDE_PACK_REPLICA
    private val identity = PackIdentity.PACK_IDENTITY

    /**
     * Records the type of [packIdentifier]: absent inserts, the same type is [IdentityResult.SAME], another type is
     * [IdentityResult.CONFLICT] and nothing changes (a recorded type is never overwritten).
     */
    fun recordIdentity(
        packIdentifier: String,
        packType: String,
    ): IdentityResult {
        val inserted =
            dsl
                .insertInto(identity)
                .set(identity.TENANT_ID, boundTenant())
                .set(identity.PACK_IDENTIFIER, packIdentifier)
                .set(identity.PACK_TYPE, packType)
                .onConflictDoNothing()
                .execute() == 1
        if (inserted) return IdentityResult.RECORDED
        return if (typeOf(packIdentifier) == packType) IdentityResult.SAME else IdentityResult.CONFLICT
    }

    /** The recorded type of the pack, or null when the identifier was never staged for the tenant. */
    fun typeOf(packIdentifier: String): String? =
        dsl
            .select(identity.PACK_TYPE)
            .from(identity)
            .where(identity.TENANT_ID.eq(boundTenant()).and(identity.PACK_IDENTIFIER.eq(packIdentifier)))
            .fetchOne(identity.PACK_TYPE)

    /**
     * Inserts the version once. A second delivery with equal contents (jsonb equality) is [StageResult.UNCHANGED]; the
     * same version with other contents is [StageResult.CONFLICT] and nothing is written: a staged version is never
     * overwritten.
     */
    fun stage(
        packIdentifier: String,
        version: String,
        contentsJson: String,
    ): StageResult {
        val contents = DSL.cast(contentsJson, SQLDataType.JSONB)
        val inserted =
            dsl
                .insertInto(table)
                .set(table.TENANT_ID, boundTenant())
                .set(table.PACK_IDENTIFIER, packIdentifier)
                .set(table.PACK_VERSION, version)
                .set(table.CONTENTS, contents)
                .onConflictDoNothing()
                .execute() == 1
        if (inserted) return StageResult.STORED
        val same =
            dsl.fetchExists(
                table,
                key(packIdentifier, version).and(table.CONTENTS.eq(contents)),
            )
        return if (same) StageResult.UNCHANGED else StageResult.CONFLICT
    }

    /**
     * Marks exactly (identifier, [version]) active and every other row of the tenant inactive. A pair that is not
     * staged is never invented: [IllegalStateException], so the inbox retries it. Returns true when a row changed.
     */
    fun activate(
        packIdentifier: String,
        version: String,
    ): Boolean {
        check(dsl.fetchExists(table, key(packIdentifier, version))) { "the pack version is not staged yet" }
        val target = table.PACK_IDENTIFIER.eq(packIdentifier).and(table.PACK_VERSION.eq(version))
        return dsl
            .update(table)
            .set(table.ACTIVE, target)
            .where(table.TENANT_ID.eq(boundTenant()))
            .and(table.ACTIVE.ne(target))
            .execute() > 0
    }

    /** The active row with its contents, or null when none is active for the tenant. */
    fun active(): ReplicaPack? =
        dsl
            .selectFrom(table)
            .where(table.TENANT_ID.eq(boundTenant()).and(table.ACTIVE.isTrue))
            .fetchOne()
            ?.let {
                ReplicaPack(
                    checkNotNull(it.packIdentifier),
                    checkNotNull(it.packVersion),
                    checkNotNull(it.contents).data(),
                )
            }

    /** The contents of a staged version of the tenant, or null. */
    fun contentsOf(
        packIdentifier: String,
        version: String,
    ): String? =
        dsl
            .selectFrom(table)
            .where(key(packIdentifier, version))
            .fetchOne()
            ?.contents
            ?.data()

    private fun key(
        packIdentifier: String,
        version: String,
    ) = table.TENANT_ID
        .eq(boundTenant())
        .and(table.PACK_IDENTIFIER.eq(packIdentifier))
        .and(table.PACK_VERSION.eq(version))
}
