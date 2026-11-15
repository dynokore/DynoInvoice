package com.dynokore.gst.access.adapters.persistence

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock

/** The value a bump replaced and the value it stored, returned by one database statement (INV-T-22072). */
data class Bumped(
    val previous: Long,
    val incarnation: Long,
)

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

    /**
     * Raises the value to `max(stored + 1, now in epoch seconds)` and returns the replaced and the stored value from
     * one statement under a row lock; the operation of INV-T-22072 calls it. Fails when the table holds no row.
     */
    fun bump(): Bumped
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

    override fun bump(): Bumped {
        val now = clock.instant().epochSecond
        val result =
            DSL.function(DSL.name(ACCESS_SCHEMA, BUMP_FUNCTION), SQLDataType.BIGINT.array(), DSL.`val`(now))
        val pair = checkNotNull(dsl.select(result).fetchOne()?.value1()) { "$BUMP_FUNCTION returned no value" }
        check(pair.size == 2 && pair.none { it == null }) { "$BUMP_FUNCTION returned $pair, expected two values" }
        return Bumped(pair[0], pair[1])
    }

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
