package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How a consumer is rebuilt (INV-REQ-1205 b4): a replay by scope, or a full sync of the producer's full scope. */
enum class ReplayMode { SCOPE, FULL_SYNC }

/**
 * The scope of a replay by scope (owner ruling INV-Q-3671): a tagged union on [kind].
 */
sealed interface ReplayScope {
    val kind: String

    /** Every state of one tenant. */
    data class Tenant(
        val tenant: TenantId,
    ) : ReplayScope {
        override val kind: String get() = "TENANT"

        init {
            require(!tenant.isPlatform) { "a replay scope names a real tenant" }
        }
    }

    /** Every state of one registered entity of one tenant. */
    data class Entity(
        val tenant: TenantId,
        val entity: EntityRef,
    ) : ReplayScope {
        override val kind: String get() = "ENTITY"

        init {
            require(!tenant.isPlatform) { "a replay scope names a real tenant" }
        }
    }

    /**
     * Every state last changed in the half-open range [[from], [to]), across all tenants unless narrowed by
     * [tenant].
     */
    data class Period(
        val from: Instant,
        val to: Instant,
        val tenant: TenantId? = null,
    ) : ReplayScope {
        override val kind: String get() = "PERIOD"

        init {
            require(to.isAfter(from)) { "a period's to is later than its from" }
            require(tenant == null || !tenant.isPlatform) { "a replay scope names a real tenant" }
        }
    }

    /** The one tenant this scope is limited to, or `null` for every tenant. */
    val onlyTenant: TenantId?
        get() =
            when (this) {
                is Tenant -> tenant
                is Entity -> tenant
                is Period -> tenant
            }
}

/**
 * The payload of a `ReplayRequested` (INV-API-3654): [producer] is the addressee, the pair's catalogue producer value
 * (a service name or 'every service', INV-Q-3625); [scope] is present exactly when [mode] is [ReplayMode.SCOPE].
 */
data class ReplayRequest(
    val replayId: UUID,
    val producer: String,
    val mode: ReplayMode,
    val scope: ReplayScope?,
) {
    init {
        require(producer.isNotBlank()) { "the addressee producer must not be blank" }
        require((mode == ReplayMode.SCOPE) == (scope != null)) { "a scope is given with SCOPE and only with SCOPE" }
    }
}

/**
 * One item of a producer's current authoritative state, as its replay re-emits it (INV-REQ-1205 b4, INV-Q-3603): the
 * library gives it a fresh event id and carries [docSequence], the `docsequence` of the state it holds (the sequence of
 * the last event the producer emitted for [aggregateKey]), so a consumer that already holds that state or a newer one
 * sees it as STALE. The tenant is the one bound for the page that returned it.
 */
data class ReplayedState(
    val type: String,
    val dataJson: String,
    val entity: EntityRef,
    val aggregateKey: String,
    val docSequence: Long?,
    val docUuid: DocUuid? = null,
    val transactionDate: LocalDate? = null,
) {
    init {
        require(aggregateKey.isNotBlank()) { "aggregate key must not be blank" }
        require(docUuid == null || aggregateKey == docUuid.toString()) {
            "a document-scoped state is keyed by its Doc UUID"
        }
        require(docSequence == null || docSequence >= 1) { "docsequence starts at 1" }
    }
}

/** One page of a [ReplayStateSource]; [next] is the cursor of the following page, `null` after the last. */
data class ReplayPage(
    val items: List<ReplayedState>,
    val next: String?,
)

/**
 * The producer side of the replay (INV-T-3385): one per event type a producing service emits, implemented by that
 * service in its own task over its current authoritative state, never its outbox or the event log (both purged at the
 * same period). The library reads the requests, picks the sources of the types the requesting consumer's replay topic
 * carries, and writes their pages to that topic.
 */
interface ReplayStateSource {
    /** The event type this source re-emits (`com.dynokore.gst.<service>.<Name>.v<N>`). */
    val eventType: String

    /**
     * The tenants holding state of [eventType], for a replay across tenants (a full sync, or a PERIOD scope without a
     * tenant). Called outside any tenant transaction: the service answers from its own installation-level view (as a
     * job's `JobEntitySource` does).
     */
    fun tenants(): List<TenantId>

    /**
     * Up to [limit] states of the bound tenant within [scope] (`null`: the full scope) after [cursor] (`null`: from the
     * start), in a stable order. Called inside a transaction bound to that tenant; the page's states are written to the
     * outbox in the same transaction.
     */
    fun page(
        scope: ReplayScope?,
        cursor: String?,
        limit: Int,
    ): ReplayPage
}

/** What one request did at a producer. */
data class ReplayAnswer(
    val replayId: UUID,
    val consumer: String,
    val replayTopic: String,
    val eventsReplayed: Long,
)

/**
 * A replay a consumer has requested and not yet completed (its ReplayCompleted not yet applied): at most one per
 * (consumer, producer) pair (owner ruling INV-Q-3665). [topics] are the topics whose position the request moved to the
 * head; their 'rebuild required' incidents close when it completes.
 */
data class PendingReplay(
    val replayId: UUID,
    val consumer: String,
    val producer: String,
    val mode: ReplayMode,
    val scope: ReplayScope?,
    val topics: List<String>,
    val requestedAt: Instant,
)

/** A position a replay request moved: [before] is the consumer's next offset before the move, [after] past the head. */
data class MovedPosition(
    val topic: String,
    val before: Long,
    val after: Long,
)

/**
 * The read-model clear of a full sync (coordinator ruling INV-Q-3786): the statements that delete what the consumer
 * built from [producer]'s topics. They run in the full-sync transaction on the consumer's worker-role connection
 * bound to the platform tenant, across every tenant. Each is a plain `DELETE` without parameters on a table whose
 * worker access the service's migration grants with `OutboxSchema.readModelClearAccess` (DELETE, and column SELECT
 * on the filter columns only). [producer] is a service name or 'every service'.
 */
data class ReadModelClear(
    val producer: String,
    val statements: List<String>,
) {
    init {
        require(producer.isNotBlank()) { "producer must not be blank" }
        require(statements.all { it.trimStart().startsWith("delete ", ignoreCase = true) }) {
            "a read-model clear is DELETE statements only"
        }
    }
}
