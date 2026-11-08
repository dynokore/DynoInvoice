package com.dynokore.gst.tenancy.application.questionnaire

import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The three entity facts of the onboarding questionnaire; null is not carried (write) or not recorded (read). */
data class EntityFactValues(
    val recordsPurchases: Boolean?,
    val hasForeignCurrencyDocuments: Boolean?,
    val claimsInputTaxOnPostingBasis: Boolean?,
)

/** The entity facts of the bound tenant, one row per entity; runs in the caller's transaction. */
interface EntityFactStore {
    /** Whether the reference is an entity of the bound tenant. */
    fun entityExists(entityRef: String): Boolean

    /** Replaces each fact [facts] carries and leaves the others as stored; records the actor and the time. */
    fun merge(
        entityRef: String,
        facts: EntityFactValues,
        actor: String,
    )

    /** The stored facts of the entity, or null when none were recorded. */
    fun find(entityRef: String): EntityFactValues?
}

/**
 * The entity-scope extension of answerQuestionnaire (owner answer INV-Q-9696 B; INV-REQ-987): records the facts of an
 * entity of the bound tenant with the verified caller. A reference that is no entity of the tenant records nothing.
 */
class RecordEntityFacts(
    private val tx: TransactionRunner,
    private val store: EntityFactStore,
    private val caller: CallerSource,
) {
    /** True when the facts were recorded, false when [entityRef] is no entity of the bound tenant. */
    fun handle(
        entityRef: String,
        facts: EntityFactValues,
    ): Boolean =
        tx.inTransaction {
            if (!store.entityExists(entityRef)) return@inTransaction false
            store.merge(entityRef, facts, caller.actor())
            true
        }
}

/** The binding of the election port over the stored facts: a fact never recorded is NOT_RECORDED (INV-Q-9696 B). */
class StoredEntityFacts(
    private val store: EntityFactStore,
) : EntityFactSource {
    override fun recordsPurchases(entityRef: String) = answer(store.find(entityRef)?.recordsPurchases)

    override fun hasForeignCurrencyDocuments(entityRef: String) =
        answer(store.find(entityRef)?.hasForeignCurrencyDocuments)

    override fun claimsInputTaxOnPostingBasis(entityRef: String) =
        answer(store.find(entityRef)?.claimsInputTaxOnPostingBasis)

    private fun answer(value: Boolean?): FactAnswer =
        when (value) {
            null -> FactAnswer.NOT_RECORDED
            true -> FactAnswer.TRUE
            false -> FactAnswer.FALSE
        }
}
