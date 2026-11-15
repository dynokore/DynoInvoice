package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.CanonicalInput
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.DocumentVersionFact
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.BuildInputsReader
import org.jooq.DSLContext
import java.util.UUID

/**
 * The stored canonical input of a version with its entity and aggregate reference, read over `payload.canonical_input`
 * and `payload.document_version_fact` for the tenant bound to the request. A missing row, a version whose facts are
 * not stored yet, and another tenant's row all answer null.
 */
class JooqBuildInputsReader(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : BuildInputsReader {
    private val input = CanonicalInput.CANONICAL_INPUT
    private val fact = DocumentVersionFact.DOCUMENT_VERSION_FACT

    override fun read(
        docUuid: UUID,
        version: Int,
    ): BuildInput? {
        val tenant = boundTenant()
        val record =
            dsl
                .select(
                    fact.ENTITY_REF,
                    input.CANONICAL_DOCUMENT,
                    fact.AGGREGATE_ID,
                    input.DIRECTION,
                    input.COUNTERPARTY_ON_NETWORK,
                ).from(input)
                .join(fact)
                .on(
                    fact.TENANT_ID
                        .eq(input.TENANT_ID)
                        .and(fact.DOC_UUID.eq(input.DOC_UUID))
                        .and(fact.VERSION.eq(input.VERSION)),
                ).where(
                    input.TENANT_ID
                        .eq(tenant)
                        .and(input.DOC_UUID.eq(docUuid))
                        .and(input.VERSION.eq(version)),
                ).fetchOne() ?: return null
        return BuildInput(
            entityRef = checkNotNull(record.get(fact.ENTITY_REF)),
            canonicalDocument = checkNotNull(record.get(input.CANONICAL_DOCUMENT)).data(),
            aggregateId = record.get(fact.AGGREGATE_ID),
            direction = record.get(input.DIRECTION),
            counterpartyOnNetwork = record.get(input.COUNTERPARTY_ON_NETWORK),
        )
    }
}
