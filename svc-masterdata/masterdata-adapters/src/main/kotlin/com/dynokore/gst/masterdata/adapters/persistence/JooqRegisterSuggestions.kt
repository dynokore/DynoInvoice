package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.records.RegisterSuggestionRecord
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.REGISTER_SUGGESTION
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestion
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestions
import com.dynokore.gst.masterdata.application.registry.SuggestionCursor
import com.dynokore.gst.masterdata.application.registry.SuggestionDraft
import com.dynokore.gst.masterdata.application.registry.SuggestionState
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.registry.RegisteredField
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** TPL-11. The register suggestions of the bound tenant; the tenancy-jooq listeners add the tenant predicate. */
class JooqRegisterSuggestions(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : RegisterSuggestions {
    override fun suggest(draft: SuggestionDraft): RegisterSuggestion {
        val pending =
            dsl
                .selectFrom(REGISTER_SUGGESTION)
                .where(
                    REGISTER_SUGGESTION.PARTY_REF.eq(draft.party.value),
                    REGISTER_SUGGESTION.FIELD.eq(draft.field.name),
                    REGISTER_SUGGESTION.STATE.eq(PENDING),
                ).fetch()
        pending.firstOrNull { it.registerValue == draft.registerValue }?.let { return it.toSuggestion() }
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        pending.forEach {
            dsl
                .update(REGISTER_SUGGESTION)
                .set(REGISTER_SUGGESTION.STATE, SuggestionState.SUPERSEDED.name)
                .set(REGISTER_SUGGESTION.UPDATED_AT, now)
                .set(REGISTER_SUGGESTION.VERSION, REGISTER_SUGGESTION.VERSION.plus(1L))
                .where(REGISTER_SUGGESTION.ID.eq(it.id))
                .execute()
        }
        val id = ids.next(clock.instant())
        val ref = "RSG-$id"
        dsl
            .insertInto(REGISTER_SUGGESTION)
            .set(REGISTER_SUGGESTION.ID, id)
            .set(REGISTER_SUGGESTION.SUGGESTION_REF, ref)
            .set(REGISTER_SUGGESTION.ENTITY_REF, draft.entity)
            .set(REGISTER_SUGGESTION.PARTY_REF, draft.party.value)
            .set(REGISTER_SUGGESTION.FIELD, draft.field.name)
            .set(REGISTER_SUGGESTION.REGISTER_VALUE, draft.registerValue)
            .set(REGISTER_SUGGESTION.CURRENT_VALUE, draft.currentValue)
            .set(REGISTER_SUGGESTION.STATE, PENDING)
            .set(REGISTER_SUGGESTION.SUGGESTED_AT, draft.at.atOffset(ZoneOffset.UTC))
            .set(REGISTER_SUGGESTION.EVIDENCE_REF, draft.evidenceRef)
            .set(REGISTER_SUGGESTION.CREATED_AT, now)
            .set(REGISTER_SUGGESTION.UPDATED_AT, now)
            .set(REGISTER_SUGGESTION.VERSION, 0L)
            .execute()
        return checkNotNull(find(ref)) { "the suggestion just stored is held" }
    }

    override fun recordApplied(draft: SuggestionDraft): RegisterSuggestion {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val id = ids.next(clock.instant())
        val ref = "RSG-$id"
        dsl
            .insertInto(REGISTER_SUGGESTION)
            .set(REGISTER_SUGGESTION.ID, id)
            .set(REGISTER_SUGGESTION.SUGGESTION_REF, ref)
            .set(REGISTER_SUGGESTION.ENTITY_REF, draft.entity)
            .set(REGISTER_SUGGESTION.PARTY_REF, draft.party.value)
            .set(REGISTER_SUGGESTION.FIELD, draft.field.name)
            .set(REGISTER_SUGGESTION.REGISTER_VALUE, draft.registerValue)
            .set(REGISTER_SUGGESTION.CURRENT_VALUE, draft.currentValue)
            .set(REGISTER_SUGGESTION.STATE, SuggestionState.CONFIRMED.name)
            .set(REGISTER_SUGGESTION.SUGGESTED_AT, draft.at.atOffset(ZoneOffset.UTC))
            .set(REGISTER_SUGGESTION.CONFIRMED_AT, draft.at.atOffset(ZoneOffset.UTC))
            .set(REGISTER_SUGGESTION.EVIDENCE_REF, draft.evidenceRef)
            .set(REGISTER_SUGGESTION.CREATED_AT, now)
            .set(REGISTER_SUGGESTION.UPDATED_AT, now)
            .set(REGISTER_SUGGESTION.VERSION, 0L)
            .execute()
        return checkNotNull(find(ref)) { "the applied value just stored is held" }
    }

    override fun find(suggestionRef: String): RegisterSuggestion? =
        dsl
            .selectFrom(REGISTER_SUGGESTION)
            .where(REGISTER_SUGGESTION.SUGGESTION_REF.eq(suggestionRef))
            .fetchOne()
            ?.toSuggestion()

    override fun list(
        party: PartyRef?,
        state: SuggestionState?,
        after: SuggestionCursor?,
        limit: Int,
    ): List<RegisterSuggestion> {
        val conditions =
            listOfNotNull(
                party?.let { REGISTER_SUGGESTION.PARTY_REF.eq(it.value) },
                state?.let { REGISTER_SUGGESTION.STATE.eq(it.name) },
                after?.let {
                    val at = it.suggestedAt.atOffset(ZoneOffset.UTC)
                    REGISTER_SUGGESTION.SUGGESTED_AT.gt(at).or(
                        REGISTER_SUGGESTION.SUGGESTED_AT
                            .eq(
                                at,
                            ).and(REGISTER_SUGGESTION.SUGGESTION_REF.gt(it.suggestionRef)),
                    )
                },
            )
        return dsl
            .selectFrom(REGISTER_SUGGESTION)
            .where(conditions.ifEmpty { listOf(DSL.trueCondition()) })
            .orderBy(REGISTER_SUGGESTION.SUGGESTED_AT, REGISTER_SUGGESTION.SUGGESTION_REF)
            .limit(limit)
            .fetch()
            .map { it.toSuggestion() }
    }

    override fun markConfirmed(
        suggestionRef: String,
        at: Instant,
    ): Boolean {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        return dsl
            .update(REGISTER_SUGGESTION)
            .set(REGISTER_SUGGESTION.STATE, SuggestionState.CONFIRMED.name)
            .set(REGISTER_SUGGESTION.CONFIRMED_AT, at.atOffset(ZoneOffset.UTC))
            .set(REGISTER_SUGGESTION.UPDATED_AT, now)
            .set(REGISTER_SUGGESTION.VERSION, REGISTER_SUGGESTION.VERSION.plus(1L))
            .where(REGISTER_SUGGESTION.SUGGESTION_REF.eq(suggestionRef), REGISTER_SUGGESTION.STATE.eq(PENDING))
            .execute() == 1
    }

    private fun RegisterSuggestionRecord.toSuggestion() =
        RegisterSuggestion(
            suggestionRef.column("suggestion_ref"),
            entityRef.column("entity_ref"),
            PartyRef(partyRef.column("party_ref")),
            RegisteredField.valueOf(field.column("field")),
            registerValue.column("register_value"),
            currentValue,
            SuggestionState.valueOf(state.column("state")),
            suggestedAt.column("suggested_at").toInstant(),
            evidenceRef,
        )

    private companion object {
        const val PENDING = "PENDING"
    }
}
