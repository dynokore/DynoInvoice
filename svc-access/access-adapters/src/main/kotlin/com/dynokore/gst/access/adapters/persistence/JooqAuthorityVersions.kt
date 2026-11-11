package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.application.operatorsession.AuthorityVersions
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.security.MessageDigest

/**
 * The one access-svc authority sequence (TPL-05), drawn inside the transaction the caller holds. A platform operator
 * has no principal row to lock, so the draw takes a transaction-scoped advisory lock keyed on the aggregate key first
 * (PostgreSQL explicit locking, pg_advisory_xact_lock): draws for one operator are then serialised and their versions
 * follow commit order.
 */
class JooqAuthorityVersions(
    private val dsl: DSLContext,
) : AuthorityVersions {
    override fun next(aggregateKey: String): Long {
        dsl
            .select(
                DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, DSL.inline(lockKey(aggregateKey))),
            ).fetch()
        return dsl.nextval(AUTHORITY_VERSION_SEQ)
    }

    companion object {
        /** The 64-bit advisory lock key of an aggregate key: the first 8 bytes of the SHA-256 of its tagged text. */
        fun lockKey(aggregateKey: String): Long =
            MessageDigest
                .getInstance("SHA-256")
                .digest("authority-version:$aggregateKey".toByteArray(Charsets.UTF_8))
                .take(Long.SIZE_BYTES)
                .fold(0L) { acc, b -> (acc shl Byte.SIZE_BITS) or (b.toLong() and 0xff) }
    }
}
