package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.correction.NetDerivationRequestPort
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * [NetDerivationRequestPort] over the transactional outbox (Richardson): `NetDerivationRequested` is appended in the
 * open transaction of the decision, so a rollback leaves no event. The payload follows
 * submission/schemas/net-derivation-requested-payload.schema.json: the original, the base version the decision was
 * taken on, and each credit note at the source version its register entry holds. The Doc UUID of the original is the
 * aggregate key and the entity is the one of its register entry; both reads are of the caller's transaction and
 * tenant. A credit note or an original without a register entry is an [IllegalStateException] (nothing is staged).
 */
class OutboxNetDerivationRequestPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val dsl: DSLContext,
) : NetDerivationRequestPort {
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun request(
        originalDocUuid: String,
        expectedBaseVersion: Int,
        creditNoteDocUuids: List<String>,
    ) {
        val tenant = tenants.current()
        val distinct = creditNoteDocUuids.distinct().sorted()
        require(distinct.isNotEmpty()) { "a net derivation request names at least one credit note" }
        val entityRef = entityOf(tenant.value, originalDocUuid)
        val payload = payloadOf(originalDocUuid, expectedBaseVersion, distinct.map { creditNote(tenant.value, it) })
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload)),
            EventContext(
                tenant = tenant,
                entity = EntityRef(entityRef),
                docUuid = DocUuid(UUID.fromString(originalDocUuid)),
                transactionDate = null,
                aggregateKey = originalDocUuid,
                aggregateVersion = null,
                correlation = CorrelationId(UUID.randomUUID().toString()),
                causation =
                    CausationId("net-requested:$originalDocUuid:$expectedBaseVersion:${distinct.joinToString(",")}"),
            ),
        )
    }

    private fun entityOf(
        tenant: UUID,
        docUuid: String,
    ): String =
        checkNotNull(
            dsl
                .select(entry.ENTITY_REF)
                .from(entry)
                .where(entry.TENANT_ID.eq(tenant))
                .and(entry.DOC_UUID.eq(docUuid))
                .fetchOne(entry.ENTITY_REF),
        ) { "net derivation request: the original has no register entry" }

    private fun creditNote(
        tenant: UUID,
        docUuid: String,
    ): Map<String, Any> {
        val version =
            dsl
                .select(entry.SOURCE_VERSION)
                .from(entry)
                .where(entry.TENANT_ID.eq(tenant))
                .and(entry.DOC_UUID.eq(docUuid))
                .fetchOne(entry.SOURCE_VERSION)
        return linkedMapOf("docUuid" to docUuid, "version" to checkNotNull(version) { "credit note has no version" })
    }

    private fun payloadOf(
        originalDocUuid: String,
        expectedBaseVersion: Int,
        creditNotes: List<Map<String, Any>>,
    ): String =
        MAPPER.writeValueAsString(
            linkedMapOf(
                "originalDocUuid" to originalDocUuid,
                "expectedBaseVersion" to expectedBaseVersion,
                "creditNotes" to creditNotes,
            ),
        )

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.submission.NetDerivationRequested.v1"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
