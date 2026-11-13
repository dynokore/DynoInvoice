package com.dynokore.gst.access.adapters.persistence

import org.jooq.DSLContext
import org.jooq.impl.DSL

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
 * jOOQ implementation of [AuthorityHeadStore]. The upsert takes the row lock of the one row, so concurrent
 * transactions queue on it until commit and cannot deadlock (one lock only); `greatest` keeps the record monotonic
 * whatever the commit order.
 */
class JooqAuthorityHeadStore(
    private val dsl: DSLContext,
) : AuthorityHeadStore {
    override fun advance(version: Long) {
        require(version >= 1) { "an authority version is 1 or more, not $version" }
        dsl
            .insertInto(TABLE)
            .columns(SINGLETON, VERSION)
            .values(true, version)
            .onConflict(SINGLETON)
            .doUpdate()
            .set(VERSION, DSL.greatest(VERSION, DSL.excluded(VERSION)))
            .execute()
    }

    override fun current(): Long? =
        dsl
            .select(VERSION)
            .from(TABLE)
            .where(SINGLETON.isTrue)
            .fetchOne(VERSION)

    private companion object {
        val TABLE = DSL.table(DSL.name(ACCESS_SCHEMA, "authority_head"))
        val SINGLETON = DSL.field(DSL.name("singleton"), org.jooq.impl.SQLDataType.BOOLEAN)
        val VERSION = DSL.field(DSL.name("authority_version"), org.jooq.impl.SQLDataType.BIGINT)
    }
}
