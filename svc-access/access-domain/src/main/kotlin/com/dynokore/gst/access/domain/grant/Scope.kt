package com.dynokore.gst.access.domain.grant

import java.time.Instant
import java.time.LocalDate

/**
 * A bounded range of days, both ends included: the periods an external audit engagement may read (INV-REQ-864 behaviour
 * 1).
 */
data class PeriodRange(
    val from: LocalDate,
    val to: LocalDate,
) {
    init {
        require(!to.isBefore(from)) { "a period range must not end before it starts, was $from..$to" }
    }

    fun encloses(other: PeriodRange): Boolean = !other.from.isBefore(from) && !other.to.isAfter(to)
}

/**
 * The tenant and group an entity belongs to, from the structure (a scope never resolves through a cached tenant
 * context).
 */
data class EntityMembership(
    val tenant: TenantRef,
    val group: GroupRef?,
)

/**
 * What a question is about: one entity, and the accounting period of the resource when there is one. A grant scoped to
 * an
 * entity and a period covers a target only when the target carries a period inside the granted range (INV-AC-1911).
 */
data class Target(
    val entity: EntityRef,
    val period: PeriodRange? = null,
)

/**
 * The scope of a grant: the five levels of INV-REQ-864 behaviour 1. A grant always has one; there is no "no scope"
 * value, so
 * a grant without a scope cannot be built (INV-REQ-859 behaviour 4).
 */
sealed interface Scope {
    /** Whether this scope covers [target], whose entity belongs to [membership]. */
    fun covers(
        target: Target,
        membership: EntityMembership,
    ): Boolean

    /**
     * Whether [entity], of [membership], is among the entities this scope reaches at all, whatever the period. The
     * per-entity grant records of AuthorityChanged are written for exactly these entities (INV-API-3325).
     */
    fun reaches(
        entity: EntityRef,
        membership: EntityMembership,
    ): Boolean

    /** Wire name of the level, as in `AuthorityChanged.scopeLevel`. */
    val level: ScopeLevel

    /** All entities of the tenant. */
    data class Tenant(
        val tenant: TenantRef,
    ) : Scope {
        override val level get() = ScopeLevel.TENANT

        override fun covers(
            target: Target,
            membership: EntityMembership,
        ) = reaches(target.entity, membership)

        override fun reaches(
            entity: EntityRef,
            membership: EntityMembership,
        ) = membership.tenant == tenant
    }

    /** All entities of a GST group. */
    data class Group(
        val group: GroupRef,
        val tenant: TenantRef,
    ) : Scope {
        override val level get() = ScopeLevel.GROUP

        override fun covers(
            target: Target,
            membership: EntityMembership,
        ) = reaches(target.entity, membership)

        override fun reaches(
            entity: EntityRef,
            membership: EntityMembership,
        ) = membership.tenant == tenant && membership.group == group
    }

    /** One registered entity, every period. */
    data class Entity(
        val entity: EntityRef,
    ) : Scope {
        override val level get() = ScopeLevel.ENTITY

        override fun covers(
            target: Target,
            membership: EntityMembership,
        ) = reaches(target.entity, membership)

        override fun reaches(
            entity: EntityRef,
            membership: EntityMembership,
        ) = this.entity == entity
    }

    /**
     * One entity and a bounded period range; a target with no period, or a period outside the range, is not covered.
     */
    data class EntityPeriod(
        val entity: EntityRef,
        val periods: PeriodRange,
    ) : Scope {
        override val level get() = ScopeLevel.ENTITY_PERIOD

        override fun covers(
            target: Target,
            membership: EntityMembership,
        ) = reaches(target.entity, membership) && target.period?.let { periods.encloses(it) } == true

        override fun reaches(
            entity: EntityRef,
            membership: EntityMembership,
        ) = this.entity == entity
    }

    /**
     * The client entities under an active agent consent (INV-REQ-853, INV-REQ-865); the consent itself is checked live.
     */
    data class ConsentDerived(
        val tenant: TenantRef,
        val consent: ConsentCheck,
    ) : Scope {
        override val level get() = ScopeLevel.CONSENT

        override fun covers(
            target: Target,
            membership: EntityMembership,
        ) = reaches(target.entity, membership) && consent.isLive(target.entity)

        override fun reaches(
            entity: EntityRef,
            membership: EntityMembership,
        ) = membership.tenant == tenant
    }
}

/** The live consent lookup behind a consent-derived scope: a withdrawn consent covers nothing at the next check. */
fun interface ConsentCheck {
    fun isLive(clientEntity: EntityRef): Boolean
}

enum class ScopeLevel { TENANT, GROUP, ENTITY, ENTITY_PERIOD, CONSENT }

/** A grant's validity window (INV-REQ-864 behaviour 2): from inclusive, until exclusive; no end is open-ended. */
data class ValidityWindow(
    val from: Instant,
    val until: Instant?,
) {
    init {
        require(
            until == null || until.isAfter(from),
        ) { "a validity window must end after it starts, was $from..$until" }
    }

    fun isValidAt(at: Instant): Boolean = !at.isBefore(from) && (until == null || at.isBefore(until))
}
