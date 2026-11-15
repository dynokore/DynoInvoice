package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.events.CanonicalInputWriter
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.CanonicalInput
import org.jooq.DSLContext
import org.jooq.JSONB
import java.util.UUID

/**
 * The write side of `payload.canonical_input` for the tenant bound to the request. It opens no transaction: the insert
 * commits with the caller. It first takes the version lock, which serialises it with the type decision store, then the
 * first stored document of a version wins.
 */
class JooqCanonicalInputStore(
    private val dsl: DSLContext,
    private val jobs: JooqBuildJobs,
    private val boundTenant: () -> UUID,
) : CanonicalInputWriter {
    private val table = CanonicalInput.CANONICAL_INPUT

    override fun store(
        docUuid: UUID,
        version: Int,
        canonicalDocument: String,
        direction: String?,
        counterpartyOnNetwork: Boolean?,
    ): Boolean {
        jobs.lockVersion(docUuid, version)
        return dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant())
            .set(table.DOC_UUID, docUuid)
            .set(table.VERSION, version)
            .set(table.CANONICAL_DOCUMENT, JSONB.valueOf(canonicalDocument))
            .set(table.DIRECTION, direction)
            .set(table.COUNTERPARTY_ON_NETWORK, counterpartyOnNetwork)
            .onConflictDoNothing()
            .execute() == 1
    }
}
