package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.HELD_DOCUMENT
import com.dynokore.gst.masterdata.application.held.HeldDocumentFact
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.gst.masterdata.application.held.HeldKind
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * TPL-11. The held-documents replica of the bound tenant; the tenancy-jooq listeners add the tenant to the insert and
 * the tenant predicate to every other part. [apply] is one INSERT ... ON CONFLICT DO UPDATE guarded by the sequence, so
 * it equals `HeldDocumentRule.next` for every order of the facts: a stale or repeated fact leaves the row, a hold
 * without party on no row inserts nothing, a release keeps entity and party (a tombstone), a hold sets both.
 */
class JooqHeldDocuments(
    private val dsl: DSLContext,
) : HeldDocuments {
    override fun apply(fact: HeldDocumentFact) {
        val held = fact.kind == HeldKind.HELD
        val values =
            dsl
                .select(
                    DSL.value(fact.docUuid, HELD_DOCUMENT.DOC_UUID),
                    DSL.value(fact.docsequence, HELD_DOCUMENT.DOCSEQUENCE),
                    DSL.value(held, HELD_DOCUMENT.HELD),
                    DSL.value(fact.entity, HELD_DOCUMENT.ENTITY),
                    DSL.value(fact.partyRef, HELD_DOCUMENT.PARTY_REF),
                ).where(writes(fact))
        val update =
            dsl
                .insertInto(
                    HELD_DOCUMENT,
                    HELD_DOCUMENT.DOC_UUID,
                    HELD_DOCUMENT.DOCSEQUENCE,
                    HELD_DOCUMENT.HELD,
                    HELD_DOCUMENT.ENTITY,
                    HELD_DOCUMENT.PARTY_REF,
                ).select(values)
                .onConflict(HELD_DOCUMENT.TENANT_ID, HELD_DOCUMENT.DOC_UUID)
                .doUpdate()
                .set(HELD_DOCUMENT.DOCSEQUENCE, DSL.excluded(HELD_DOCUMENT.DOCSEQUENCE))
                .set(HELD_DOCUMENT.HELD, DSL.excluded(HELD_DOCUMENT.HELD))
        val guard = HELD_DOCUMENT.DOCSEQUENCE.lt(DSL.excluded(HELD_DOCUMENT.DOCSEQUENCE))
        if (held) {
            update
                .set(HELD_DOCUMENT.ENTITY, DSL.excluded(HELD_DOCUMENT.ENTITY))
                .set(HELD_DOCUMENT.PARTY_REF, DSL.excluded(HELD_DOCUMENT.PARTY_REF))
                .where(guard)
                .execute()
        } else {
            update.where(guard).execute()
        }
    }

    /** A release or a hold with a party always writes; a hold without a party writes only over an existing row. */
    private fun writes(fact: HeldDocumentFact): Condition =
        if (fact.kind == HeldKind.RELEASED || fact.partyRef != null) {
            DSL.trueCondition()
        } else {
            DSL.exists(
                dsl
                    .selectOne()
                    .from(HELD_DOCUMENT)
                    .where(HELD_DOCUMENT.DOC_UUID.eq(fact.docUuid)),
            )
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
}
