package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.HANDOVER_STATEMENT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.HANDOVER_STATEMENT_DOCUMENT
import com.dynokore.gst.access.application.consent.DocumentList
import com.dynokore.gst.access.application.consent.HandoverStatement
import com.dynokore.gst.access.application.consent.HandoverStatements
import com.dynokore.gst.access.application.consent.UnsubmittedDocument
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. A handover statement is written once and never changed. */
class JooqHandoverStatements(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : HandoverStatements {
    override fun insert(statement: HandoverStatement) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor =
            checkNotNull(tenants.currentActor()) { "no actor is bound: a withdrawal runs in a bound transaction" }
        dsl
            .insertInto(HANDOVER_STATEMENT)
            .set(HANDOVER_STATEMENT.ID, ids.next(clock.instant()))
            .set(HANDOVER_STATEMENT.STATEMENT_REF, statement.ref)
            .set(HANDOVER_STATEMENT.CONSENT_REF, statement.consentRef.value)
            .set(HANDOVER_STATEMENT.CLIENT_ENTITY_REF, statement.client.value)
            .set(HANDOVER_STATEMENT.AGENT_REF, statement.agent.value)
            .set(
                HANDOVER_STATEMENT.DOCUMENT_LIST,
                if (statement.documents is DocumentList.Listed) LISTED else UNAVAILABLE,
            ).set(HANDOVER_STATEMENT.PRODUCED_AT, statement.producedAt.atOffset(ZoneOffset.UTC))
            .set(HANDOVER_STATEMENT.CREATED_AT, now)
            .set(HANDOVER_STATEMENT.CREATED_BY, actor)
            .execute()
        (statement.documents as? DocumentList.Listed)?.documents?.forEachIndexed { ordinal, document ->
            dsl
                .insertInto(HANDOVER_STATEMENT_DOCUMENT)
                .set(HANDOVER_STATEMENT_DOCUMENT.ID, ids.next(clock.instant()))
                .set(HANDOVER_STATEMENT_DOCUMENT.STATEMENT_REF, statement.ref)
                .set(HANDOVER_STATEMENT_DOCUMENT.ORDINAL, ordinal)
                .set(HANDOVER_STATEMENT_DOCUMENT.DOCUMENT_REF, document.documentRef)
                .set(HANDOVER_STATEMENT_DOCUMENT.DUE_DATE, document.dueDate)
                .set(HANDOVER_STATEMENT_DOCUMENT.BLOCKING_REASON, document.blockingReason)
                .execute()
        }
    }

    override fun find(ref: String): HandoverStatement? {
        val row =
            dsl.selectFrom(HANDOVER_STATEMENT).where(HANDOVER_STATEMENT.STATEMENT_REF.eq(ref)).fetchOne() ?: return null
        val documents =
            if (row.documentList == LISTED) {
                DocumentList.Listed(
                    dsl
                        .selectFrom(HANDOVER_STATEMENT_DOCUMENT)
                        .where(HANDOVER_STATEMENT_DOCUMENT.STATEMENT_REF.eq(ref))
                        .orderBy(HANDOVER_STATEMENT_DOCUMENT.ORDINAL)
                        .fetch()
                        .map {
                            UnsubmittedDocument(
                                it.documentRef.column("document_ref"),
                                it.dueDate.column("due_date"),
                                it.blockingReason.column("blocking_reason"),
                            )
                        },
                )
            } else {
                DocumentList.Unavailable
            }
        return HandoverStatement(
            ref = ref,
            consentRef = ConsentRef(row.consentRef.column("consent_ref")),
            client = EntityRef(row.clientEntityRef.column("client_entity_ref")),
            agent = IdentityRef(row.agentRef.column("agent_ref")),
            documents = documents,
            producedAt = row.producedAt.column("produced_at").toInstant(),
        )
    }

    override fun nextRef(): String =
        "HOS-" +
            ids
                .next(clock.instant())
                .toString()
                .replace("-", "")
                .takeLast(REF_DIGITS)

    private companion object {
        const val LISTED = "LISTED"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val REF_DIGITS = 8
    }
}
