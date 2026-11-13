package com.dynokore.gst.access.adapters.persistence

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock

/**
 * The incarnation (epoch) of the authority versions (INV-Q-9243 B (a), INV-REQ-1201): the one row of
 * `access.authority_incarnation`. It rises in two cases only, [start] when the sequence is below the high-water mark
 * and [bump]; it never falls, and a rolling restart keeps it.
 */
interface AuthorityIncarnationStore {
    /** The stored value; fails when the table holds no row (the start-time step has not run). */
    fun current(): Long

    /** The start-time rule: the first value from the clock, or a rise when the sequence is below the mark. */
    fun start(): Long

    /** Raises the value to `max(stored + 1, now in epoch seconds)`; the operation of INV-T-22072 calls it. */
    fun bump(): Long
}

/** jOOQ implementation of [AuthorityIncarnationStore]; the writes go through SECURITY DEFINER functions (TPL-11). */
class JooqAuthorityIncarnationStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : AuthorityIncarnationStore {
    override fun current(): Long =
        checkNotNull(
            dsl
                .select(INCARNATION)
                .from(TABLE)
                .where(SINGLETON.isTrue)
                .fetchOne(INCARNATION),
        ) { "access.authority_incarnation holds no row: the start-time step has not run" }

    override fun start(): Long = call(START_FUNCTION)

    override fun bump(): Long = call(BUMP_FUNCTION)

    private fun call(function: String): Long {
        val now = clock.instant().epochSecond
        val result = DSL.function(DSL.name(ACCESS_SCHEMA, function), SQLDataType.BIGINT, DSL.`val`(now))
        return checkNotNull(dsl.select(result).fetchOne()?.value1()) { "$function returned no value" }
    }

    companion object {
        const val START_FUNCTION = "start_authority_incarnation"
        const val BUMP_FUNCTION = "bump_authority_incarnation"
        private val TABLE = DSL.table(DSL.name(ACCESS_SCHEMA, "authority_incarnation"))
        private val SINGLETON = DSL.field(DSL.name("singleton"), SQLDataType.BOOLEAN)
        private val INCARNATION = DSL.field(DSL.name("incarnation"), SQLDataType.BIGINT)
    }
}
