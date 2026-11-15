package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PROFILE_ELECTION
import com.dynokore.gst.canonical.application.document.PurchaseElections
import com.dynokore.gst.canonical.domain.dating.DateBasis
import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID

/** One effective-dated profile entry: `purchaseDateBasis` or `gstScheme.<KEY>`. */
data class ProfileElectionEntry(
    val attribute: String,
    val value: String,
    val effectiveDate: LocalDate,
)

/** The profile entries of one entity as carried by one EntityProfileChanged event of [version]. */
data class ProfileElectionSet(
    val tenantId: UUID,
    val entityRef: String,
    val version: Long,
    val entries: List<ProfileElectionEntry>,
)

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; forced row-level security binds
 * every statement to the tenant of the session. Each row carries the event version that wrote it and is replaced only
 * by a higher one (per-row guard, idempotent consumer). Nothing is ever deleted: the producer never drops an entry,
 * so an entry absent from a newer set stays (event-carried state transfer, INV-Q-21422 A).
 */
class JooqProfileElections(
    private val dsl: DSLContext,
) : PurchaseElections {
    /** True when any row was inserted or replaced; false when every entry was ignored. */
    fun apply(set: ProfileElectionSet): Boolean {
        var changed = false
        set.entries.forEach { entry ->
            val rows =
                dsl
                    .insertInto(PROFILE_ELECTION)
                    .set(PROFILE_ELECTION.TENANT_ID, set.tenantId)
                    .set(PROFILE_ELECTION.ENTITY_REF, set.entityRef)
                    .set(PROFILE_ELECTION.ATTRIBUTE, entry.attribute)
                    .set(PROFILE_ELECTION.EFFECTIVE_DATE, entry.effectiveDate)
                    .set(PROFILE_ELECTION.VALUE, entry.value)
                    .set(PROFILE_ELECTION.VERSION, set.version)
                    .onConflict(
                        PROFILE_ELECTION.TENANT_ID,
                        PROFILE_ELECTION.ENTITY_REF,
                        PROFILE_ELECTION.ATTRIBUTE,
                        PROFILE_ELECTION.EFFECTIVE_DATE,
                    ).doUpdate()
                    .set(PROFILE_ELECTION.VALUE, entry.value)
                    .set(PROFILE_ELECTION.VERSION, set.version)
                    .where(PROFILE_ELECTION.VERSION.lt(set.version))
                    .execute()
            if (rows > 0) changed = true
        }
        return changed
    }

    override fun electionOf(
        tenantId: UUID,
        entityRef: String,
        on: LocalDate,
    ): DateBasis? =
        dsl
            .select(PROFILE_ELECTION.VALUE)
            .from(PROFILE_ELECTION)
            .where(PROFILE_ELECTION.TENANT_ID.eq(tenantId))
            .and(PROFILE_ELECTION.ENTITY_REF.eq(entityRef))
            .and(PROFILE_ELECTION.ATTRIBUTE.eq(BASIS))
            .and(PROFILE_ELECTION.EFFECTIVE_DATE.le(on))
            .orderBy(PROFILE_ELECTION.EFFECTIVE_DATE.desc())
            .limit(1)
            .fetchOne { DateBasis.valueOf(checkNotNull(it.get(PROFILE_ELECTION.VALUE))) }

    /** The scheme keys (without the `gstScheme.` prefix) whose entry in force on [on] is `ON`, ascending. */
    fun electedSchemesOf(
        tenantId: UUID,
        entityRef: String,
        on: LocalDate,
    ): List<String> =
        dsl
            .select(PROFILE_ELECTION.ATTRIBUTE, PROFILE_ELECTION.VALUE)
            .from(PROFILE_ELECTION)
            .where(PROFILE_ELECTION.TENANT_ID.eq(tenantId))
            .and(PROFILE_ELECTION.ENTITY_REF.eq(entityRef))
            .and(PROFILE_ELECTION.ATTRIBUTE.like("$SCHEME_PREFIX%"))
            .and(PROFILE_ELECTION.EFFECTIVE_DATE.le(on))
            .orderBy(PROFILE_ELECTION.ATTRIBUTE.asc(), PROFILE_ELECTION.EFFECTIVE_DATE.desc())
            .fetch()
            .map { checkNotNull(it.get(PROFILE_ELECTION.ATTRIBUTE)) to checkNotNull(it.get(PROFILE_ELECTION.VALUE)) }
            .distinctBy { it.first }
            .filter { it.second == ON }
            .map { it.first.removePrefix(SCHEME_PREFIX) }
            .sorted()

    private companion object {
        const val BASIS = "purchaseDateBasis"
        const val SCHEME_PREFIX = "gstScheme."
        const val ON = "ON"
    }
}
