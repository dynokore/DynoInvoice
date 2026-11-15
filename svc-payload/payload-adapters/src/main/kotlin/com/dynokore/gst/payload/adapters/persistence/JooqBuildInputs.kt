package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.CanonicalInput
import com.dynokore.gst.payload.application.build.BuildInputs
import org.jooq.DSLContext
import java.util.UUID

/** The stored canonical inputs, read side, over `payload.canonical_input` for the tenant bound to the request. */
class JooqBuildInputs(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : BuildInputs {
    private val table = CanonicalInput.CANONICAL_INPUT

    override fun exists(
        docUuid: UUID,
        version: Int,
    ): Boolean =
        dsl.fetchExists(
            table,
            table.TENANT_ID
                .eq(boundTenant())
                .and(table.DOC_UUID.eq(docUuid))
                .and(table.VERSION.eq(version)),
        )
}
