package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.events.ConsumedHeldDocumentEvents
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.HELD_DOCUMENT
import com.dynokore.gst.masterdata.application.held.HeldDocumentFact
import com.dynokore.gst.masterdata.application.held.HeldDocumentRule
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.gst.masterdata.application.held.HeldKind
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.TableField
import org.jooq.impl.DSL
import java.util.UUID
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.HELD_DOCUMENT_REASON as REASON

/**
 * TPL-11. The held-documents replica of the bound tenant; the tenancy-jooq listeners add the tenant to the insert and
 * the tenant predicate to every other part. [apply] runs in the caller's transaction and equals `HeldDocumentRule.next`
 * for every order of the facts: (1) each reason of the fact replaces its mark only by a higher sequence, (2) the row is
 * upserted with the highest sequence and the entity and party of the hold with the highest sequence, (3) `held` is
 * recomputed from the marks after the row lock is taken, so concurrent facts of one document converge.
 */
class JooqHeldDocuments(
    private val dsl: DSLContext,
) : HeldDocuments {
    override fun apply(fact: HeldDocumentFact) {
        val isHold = fact.kind == HeldKind.HELD
        for (reason in fact.reasons.ifEmpty { setOf(HeldDocumentRule.NO_REASON) }) {
            dsl
                .insertInto(
                    REASON,
                    REASON.DOC_UUID,
                    REASON.REASON,
                    REASON.DOCSEQUENCE,
                    REASON.HELD,
                ).values(fact.docUuid, reason, fact.docsequence, isHold)
                .onConflict(REASON.TENANT_ID, REASON.DOC_UUID, REASON.REASON)
                .doUpdate()
                .set(REASON.DOCSEQUENCE, DSL.excluded(REASON.DOCSEQUENCE))
                .set(REASON.HELD, DSL.excluded(REASON.HELD))
                .where(REASON.DOCSEQUENCE.lt(DSL.excluded(REASON.DOCSEQUENCE)))
                .execute()
        }
        upsertRow(fact, isHold)
        dsl
            .update(HELD_DOCUMENT)
            .set(
                HELD_DOCUMENT.HELD,
                DSL.exists(
                    dsl.selectOne().from(REASON).where(REASON.DOC_UUID.eq(fact.docUuid), REASON.HELD.isTrue),
                ),
            ).where(HELD_DOCUMENT.DOC_UUID.eq(fact.docUuid))
            .execute()
    }

    /** A hold replaces entity, party and identity sequence only when its sequence is higher than the stored one. */
    private fun upsertRow(
        fact: HeldDocumentFact,
        isHold: Boolean,
    ) {
        val newIdentity =
            DSL
                .excluded(HELD_DOCUMENT.IDENTITY_DOCSEQUENCE)
                .isNotNull
                .and(
                    HELD_DOCUMENT.IDENTITY_DOCSEQUENCE.isNull
                        .or(HELD_DOCUMENT.IDENTITY_DOCSEQUENCE.lt(DSL.excluded(HELD_DOCUMENT.IDENTITY_DOCSEQUENCE))),
                )

        fun <T> pick(column: TableField<*, T>): Field<T> =
            DSL.`when`(newIdentity, DSL.excluded(column)).otherwise(column)
        dsl
            .insertInto(
                HELD_DOCUMENT,
                HELD_DOCUMENT.DOC_UUID,
                HELD_DOCUMENT.DOCSEQUENCE,
                HELD_DOCUMENT.HELD,
                HELD_DOCUMENT.ENTITY,
                HELD_DOCUMENT.PARTY_REF,
                HELD_DOCUMENT.IDENTITY_DOCSEQUENCE,
            ).values(
                fact.docUuid,
                fact.docsequence,
                isHold,
                fact.entity,
                fact.partyRef,
                if (isHold) fact.docsequence else null,
            ).onConflict(HELD_DOCUMENT.TENANT_ID, HELD_DOCUMENT.DOC_UUID)
            .doUpdate()
            .set(
                HELD_DOCUMENT.DOCSEQUENCE,
                DSL.greatest(HELD_DOCUMENT.DOCSEQUENCE, DSL.excluded(HELD_DOCUMENT.DOCSEQUENCE)),
            ).set(HELD_DOCUMENT.ENTITY, pick(HELD_DOCUMENT.ENTITY))
            .set(HELD_DOCUMENT.PARTY_REF, pick(HELD_DOCUMENT.PARTY_REF))
            .set(HELD_DOCUMENT.IDENTITY_DOCSEQUENCE, pick(HELD_DOCUMENT.IDENTITY_DOCSEQUENCE))
            .execute()
    }

    override fun heldFor(
        entity: String,
        party: PartyRef,
    ): List<UUID> =
        dsl
            .select(HELD_DOCUMENT.DOC_UUID)
            .from(HELD_DOCUMENT)
            .where(
                HELD_DOCUMENT.HELD.isTrue,
                HELD_DOCUMENT.ENTITY.eq(entity),
                HELD_DOCUMENT.PARTY_REF.eq(party.value),
            ).orderBy(DSL.cast(HELD_DOCUMENT.DOC_UUID, String::class.java))
            .fetch(HELD_DOCUMENT.DOC_UUID)
            .filterNotNull()

    override fun rul011Blocked(entity: String): Set<PartyRef> =
        dsl
            .selectDistinct(HELD_DOCUMENT.PARTY_REF)
            .from(HELD_DOCUMENT)
            .join(REASON)
            .on(
                REASON.TENANT_ID.eq(HELD_DOCUMENT.TENANT_ID),
                REASON.DOC_UUID.eq(HELD_DOCUMENT.DOC_UUID),
            ).where(
                REASON.REASON.eq(ConsumedHeldDocumentEvents.RUL_011),
                REASON.HELD.isTrue,
                HELD_DOCUMENT.ENTITY.eq(entity),
                HELD_DOCUMENT.PARTY_REF.isNotNull,
            ).fetch(HELD_DOCUMENT.PARTY_REF)
            .filterNotNull()
            .map { PartyRef(it) }
            .toSet()
}
