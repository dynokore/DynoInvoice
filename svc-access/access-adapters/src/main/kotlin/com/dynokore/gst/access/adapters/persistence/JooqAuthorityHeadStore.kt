package com.dynokore.gst.access.adapters.persistence

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** The head record of the authority versions (INV-Q-17072 A): the one row of `access.authority_head`. */
interface AuthorityHeadStore {
    /**
     * Raises the record to [version] when that is higher; never lowers it. Runs in the transaction of the caller, so
     * the record moves with the event it describes.
     */
    fun advance(version: Long)

    /** The highest authority version committed, or null while no AuthorityChanged event has been committed. */
    fun current(): Long?
}

/**
 * jOOQ implementation of [AuthorityHeadStore]. [advance] calls the SECURITY DEFINER function [FUNCTION] (TPL-11,
 * the form of [JooqIdpSyncClaim]): tenancy-jooq refuses a write of this platform-scoped table under a tenant
 * binding, so the write runs as `access_sys` through the function, which takes the row lock of the one row (so
 * concurrent transactions queue on it until commit and cannot deadlock) and keeps the record monotonic whatever the
 * commit order.
 */
class JooqAuthorityHeadStore(
    private val dsl: DSLContext,
) : AuthorityHeadStore {
    override fun advance(version: Long) {
        require(version >= 1) { "an authority version is 1 or more, not $version" }
        val advance = DSL.function(DSL.name(ACCESS_SCHEMA, FUNCTION), SQLDataType.BIGINT, DSL.`val`(version))
        dsl.select(advance).fetchOne()
        // the high-water mark of the incarnation rule (INV-T-9261) moves with the head, in the same transaction
        val mark = DSL.function(DSL.name(ACCESS_SCHEMA, MARK_FUNCTION), SQLDataType.BIGINT, DSL.`val`(version))
        dsl.select(mark).fetchOne()
    }

    override fun current(): Long? =
        dsl
            .select(VERSION)
            .from(TABLE)
            .where(SINGLETON.isTrue)
            .fetchOne(VERSION)

    companion object {
        const val FUNCTION = "advance_authority_head"
        const val MARK_FUNCTION = "record_authority_high_water"
        private val TABLE = DSL.table(DSL.name(ACCESS_SCHEMA, "authority_head"))
        private val SINGLETON = DSL.field(DSL.name("singleton"), SQLDataType.BOOLEAN)
        private val VERSION = DSL.field(DSL.name("authority_version"), SQLDataType.BIGINT)
    }
}
