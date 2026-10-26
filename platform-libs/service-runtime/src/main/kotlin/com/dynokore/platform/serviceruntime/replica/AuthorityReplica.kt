package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** Who is acting. Only [MACHINE] keeps working when no decision can be had (INV-REQ-1201 behaviours #4 and #5). */
enum class PrincipalKind { HUMAN, CONSENT_AGENT, MACHINE }

/** The acting principal. Its kind comes from the propagated token, never from `AuthorityChanged` (INV-Q-3259). */
data class Principal(
    val id: String,
    val kind: PrincipalKind,
)

/** One grant record's key: a principal's grant state on one entity (INV-Q-3322 (b)). */
data class AuthorityKey(
    val tenant: TenantId,
    val principalId: String,
    val entityRef: String,
)

/** One consent record's key: the agent consent for one client entity (INV-REQ-853 b1). */
data class ConsentKey(
    val tenant: TenantId,
    val clientEntityRef: String,
)

/** One principal record's key. */
data class PrincipalKey(
    val tenant: TenantId,
    val principalId: String,
)

/**
 * The scope level a grant comes from (INV-REQ-864 b1, INV-Q-3339). TENANT and GROUP grants arrive already
 * expanded to one entity by access-svc. [UNKNOWN] is any wire value this version does not recognise: it
 * matches nothing, never read as a plain entity grant (fail closed).
 */
enum class ScopeLevel {
    TENANT,
    GROUP,
    ENTITY,
    ENTITY_PERIOD,
    CONSENT,
    UNKNOWN,
    ;

    companion object {
        /** Maps a wire value; an unrecognised one is [UNKNOWN]. */
        fun of(wire: String): ScopeLevel = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

/** A span of days, both ends included: a GST accounting period or a granted period range. */
data class DateSpan(
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(!end.isBefore(start)) { "a date span must not end before it starts, was $start..$end" }
    }

    /** True when the whole of [other] lies inside this span. */
    fun encloses(other: DateSpan): Boolean = !other.start.isBefore(start) && !other.end.isAfter(end)
}

/**
 * One action granted on the entity of its record, with the scope it was granted under and its validity
 * window (INV-REQ-864). An [ScopeLevel.ENTITY_PERIOD] grant holds its granted periods as a date span
 * ([periodFrom], [periodTo]: first day of the first period, last day of the last), never a period name,
 * because the filing frequency can change (INV-Q-3339).
 */
data class Grant(
    val action: String,
    val scopeLevel: ScopeLevel,
    val scopeRef: String,
    val validFrom: Instant,
    val validUntil: Instant? = null,
    val periodFrom: LocalDate? = null,
    val periodTo: LocalDate? = null,
) {
    /**
     * Whether this grant covers [action] at the request time [at] for a resource of period [resourcePeriod].
     * The validity window uses the request time; the period range is a separate check on the resource's
     * accounting period (INV-AC-1911). A CONSENT grant also needs a live consent record, checked by the replica.
     */
    fun covers(
        action: String,
        at: Instant,
        resourcePeriod: DateSpan?,
    ): Boolean = this.action == action && isValidAt(at) && coversPeriod(resourcePeriod)

    /** Inside its validity window at [at]. */
    fun isValidAt(at: Instant): Boolean = !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil))

    private fun coversPeriod(resourcePeriod: DateSpan?): Boolean =
        when (scopeLevel) {
            ScopeLevel.TENANT, ScopeLevel.GROUP, ScopeLevel.ENTITY, ScopeLevel.CONSENT -> {
                true
            }

            ScopeLevel.ENTITY_PERIOD -> {
                val from = periodFrom
                val to = periodTo
                resourcePeriod != null && from != null && to != null && !to.isBefore(from) &&
                    DateSpan(from, to).encloses(resourcePeriod)
            }

            ScopeLevel.UNKNOWN -> {
                false
            }
        }
}

/** What the replica holds for one [AuthorityKey]: the record's grants, replaced whole, and its version. */
data class GrantState(
    val grants: List<Grant>,
    val authorityVersion: Long,
)

/** A consent record's state; [UNKNOWN] is any unrecognised wire value and is not live (fail closed). */
enum class ConsentState {
    LIVE,
    WITHDRAWN,
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): ConsentState = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

/**
 * A consent scope item: a VP-10 key (INV-REQ-912, INV-Q-3704). [UNKNOWN] is any wire value this version does
 * not recognise; it never matches, neither on a consent record nor on a request (fail closed).
 */
enum class ConsentScopeItem {
    SUBMIT_INVOICE_DATA,
    FILE_GST_RETURN,
    MAPPING_APPROVAL,
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): ConsentScopeItem = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

/** A principal record's state; every value, [UNKNOWN] included, voids the principal's older grant records. */
enum class PrincipalState {
    DELETED,
    REVOKED,
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): PrincipalState = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

/** The one record an `AuthorityChanged` carries (INV-Q-3322, INV-Q-3702). */
sealed interface AuthorityRecord {
    /** The full grant state of (tenant, principal, [entityRef]); an empty [grants] is a removal. */
    data class GrantRecord(
        val entityRef: String,
        val grants: List<Grant>,
    ) : AuthorityRecord

    /** The full state of the agent consent for one client entity (INV-REQ-853 b1, INV-REQ-865). */
    data class ConsentRecord(
        val clientEntityRef: String,
        val state: ConsentState,
        val scopeItems: Set<ConsentScopeItem>,
        val validFrom: Instant,
        val validUntil: Instant? = null,
    ) : AuthorityRecord {
        /** LIVE and inside its validity window at [at]. */
        fun isLiveAt(at: Instant): Boolean =
            state == ConsentState.LIVE && !at.isBefore(validFrom) &&
                (validUntil == null || at.isBefore(validUntil))

        /** Whether this consent lists [item]; no item asked is covered, an UNKNOWN one never (INV-Q-3734). */
        fun lists(item: ConsentScopeItem?): Boolean {
            val known = item != ConsentScopeItem.UNKNOWN
            return item == null || (known && item in scopeItems)
        }
    }

    /** A principal deleted or revoked: voids every grant record of the principal with a lower version. */
    data class PrincipalRecord(
        val state: PrincipalState,
    ) : AuthorityRecord
}

/**
 * The platform event `AuthorityChanged`. [authorityVersion] is access-svc's monotonic authority version,
 * the version of the one [record] carried; each service measures its own lag against it (INV-REQ-1201
 * behaviour #1). A null [record] is an event without grant state (a 1.0-shaped or faulty event).
 */
data class AuthorityChanged(
    val tenant: TenantId,
    val principalId: String,
    val authorityVersion: Long,
    val record: AuthorityRecord?,
)

/**
 * One authorisation question. [resourcePeriod] is the GST accounting period of the document or return
 * acted on, as a date span; null when there is none or it cannot be resolved, and then no
 * ENTITY_PERIOD grant matches (INV-Q-3339). [requiredScopeItem] is the consent scope item the owning
 * service's operation needs (INV-REQ-912 b1-b3, INV-REQ-913): under a CONSENT grant the live consent must
 * list it; null decides on the grant and a live consent alone. service-runtime holds no action-to-item
 * catalogue (INV-Q-3734).
 */
data class AuthorizationRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val action: String,
    val resourcePeriod: DateSpan? = null,
    val requiredScopeItem: ConsentScopeItem? = null,
)

enum class DecisionSource { LOCAL, FALLBACK, DEGRADED }

enum class DecisionReason {
    GRANTED,
    NO_GRANT,
    REVOKED,
    NO_LIVE_CONSENT,
    CONSENT_SCOPE_MISSING,
    AUTHORITY_UNAVAILABLE,
    MACHINE_FLOW_CONTINUES,
}

data class AuthorityDecision(
    val allowed: Boolean,
    val source: DecisionSource,
    val reason: DecisionReason,
)

/** access-svc's answer to a synchronous decision call (INV-REQ-1201 behaviour #3). */
data class AuthorityVerdict(
    val allowed: Boolean,
)

fun interface AuthorityFallback : ReplicaFallback<AuthorizationRequest, AuthorityVerdict>

/**
 * One access-svc operator-session signing key (platform-contracts access
 * `operator-session-keys-changed-payload.schema.json`): [publicKey] is the DER X.509 SubjectPublicKeyInfo in
 * standard base64, [alg] the JWS algorithm tokens signed with it name. Usable from [validFrom] until [validUntil].
 * [use] is the item's wire `use` string, kept raw: `OPERATOR_SESSION` or absent is an operator-session key,
 * `MACHINE_ACCESS` a machine-access key, anything else drops the item (coordinator ruling INV-Q-9028 A).
 */
data class OperatorSessionKey(
    val kid: String,
    val alg: String,
    val publicKey: String,
    val validFrom: Instant,
    val validUntil: Instant,
    val use: String? = null,
) {
    fun isValidAt(at: Instant): Boolean = !at.isBefore(validFrom) && at.isBefore(validUntil)
}

/**
 * The platform event `OperatorSessionKeysChanged` on `gst.access.operator-session-keys-changed.v1` (coordinator
 * rulings INV-Q-4162 and INV-Q-4171): the current key and, during rotation, the next one.
 */
data class OperatorSessionKeysChanged(
    val keys: List<OperatorSessionKey>,
)

/** Which key set an item belongs to (coordinator ruling INV-Q-9028 A). */
enum class KeyUse { OPERATOR_SESSION, MACHINE_ACCESS }

/**
 * The two key sets the authority replica holds from `OperatorSessionKeysChanged` (coordinator rulings INV-Q-4162,
 * INV-Q-4171, INV-Q-9028 A). Each event carries the whole of both sets and replaces them. An item's `use` is
 * matched by exact string equality: `MACHINE_ACCESS` is a machine key, `OPERATOR_SESSION` or an absent `use` an
 * operator-session key, any other string drops the item, logging its kid and counting it. A kid published under
 * both uses drops both of its items and counts a refused publication. A key is in exactly one set.
 */
class AuthorityKeySets(
    private val clock: Clock,
) {
    private class Held(
        val operator: List<OperatorSessionKey>,
        val machine: List<OperatorSessionKey>,
    )

    @Volatile private var held = Held(emptyList(), emptyList())
    private val dropped = AtomicLong()
    private val refused = AtomicLong()

    /** Items dropped for an unrecognised `use` or a kid published under both uses. */
    val droppedItems: Long get() = dropped.get()

    /** Kids refused because the same event gave them under both uses. */
    val refusedPublications: Long get() = refused.get()

    /** The operator-session set. */
    val operator = KeySet(KeyUse.OPERATOR_SESSION)

    /** The machine-access set; empty until a machine key is published, so a verifier on it refuses (fail closed). */
    val machine = KeySet(KeyUse.MACHINE_ACCESS)

    fun accept(event: OperatorSessionKeysChanged) {
        val operatorItems = mutableListOf<OperatorSessionKey>()
        val machineItems = mutableListOf<OperatorSessionKey>()
        for (key in event.keys) {
            when (key.use) {
                null, OPERATOR_SESSION_USE -> {
                    operatorItems += key
                }

                MACHINE_ACCESS_USE -> {
                    machineItems += key
                }

                else -> {
                    dropped.incrementAndGet()
                    LOG.log(
                        System.Logger.Level.WARNING,
                        "operator-session key item dropped, unrecognised use, kid={0}",
                        key.kid,
                    )
                }
            }
        }
        val both =
            operatorItems.mapTo(mutableSetOf()) { it.kid } intersect machineItems.mapTo(mutableSetOf()) { it.kid }
        for (kid in both) {
            refused.incrementAndGet()
            LOG.log(System.Logger.Level.WARNING, "key publication refused, kid under both uses, kid={0}", kid)
        }
        dropped.addAndGet(
            (operatorItems.count { it.kid in both } + machineItems.count { it.kid in both }).toLong(),
        )
        held =
            Held(
                operatorItems.filter { it.kid !in both },
                machineItems.filter { it.kid !in both },
            )
    }

    /** One of the two sets, answering [valid]. [accept] feeds both sets, since one event carries both. */
    inner class KeySet internal constructor(
        private val use: KeyUse,
    ) {
        fun accept(event: OperatorSessionKeysChanged) = this@AuthorityKeySets.accept(event)

        /** The held key [kid] when valid now; null otherwise, so the token is refused (fail closed, INV-Q-4162). */
        fun valid(kid: String): OperatorSessionKey? {
            val now = clock.instant()
            val keys = if (use == KeyUse.OPERATOR_SESSION) held.operator else held.machine
            return keys.firstOrNull { it.kid == kid && it.isValidAt(now) }
        }
    }

    private companion object {
        const val OPERATOR_SESSION_USE = "OPERATOR_SESSION"
        const val MACHINE_ACCESS_USE = "MACHINE_ACCESS"
        val LOG: System.Logger = System.getLogger(AuthorityKeySets::class.java.name)
    }
}

/** A principal record as held: the voiding version, and the state (null: voided by an event without state). */
data class PrincipalRevocation(
    val state: PrincipalState?,
    val revocationVersion: Long,
)

/**
 * The authority replica (INV-REQ-1201), fed by `AuthorityChanged` (INV-Q-3322 (b)). It holds three kinds of
 * record, each replaced whole and versioned on its own key:
 *  - grant records per (tenant, principal, entityRef); an empty grant list is kept as a tombstone;
 *  - consent records per (tenant, client entityRef); a CONSENT grant decides only with a live one, so a
 *    withdrawal takes effect at the next check with no grant event (INV-REQ-865);
 *  - principal records per (tenant, principal), whose version voids every grant record of that principal
 *    with a lower version, so a reinstated principal gets no old grant back.
 * A change at or below the version held for its key is ignored. An event without a record is never kept:
 * the replica drops every grant record it holds for that principal (each key keeps its version as a
 * tombstone, so versions never go backwards) and voids lower versions as a principal record would, so a
 * lost revoke cannot leave a grant live; no entry means deny (INV-REQ-1214).
 *
 * Decisions:
 *  - lag at most [lagBound] (default one second): decided locally;
 *  - lag above it, or no version received yet: one synchronous call to `access-svc`;
 *  - that call fails too: a human or consent-derived agent action is denied, a
 *    machine flow continues. A submission is never denied for want of a decision
 *    (machine tokens are verified offline at the gateway; INV-NOTE-3 §18.6).
 *
 * A definite local or fallback answer is final for every principal: the machine
 * exception covers only the absence of any answer.
 *
 * It also keeps access-svc's operator-session signing keys from `OperatorSessionKeysChanged`, consumed beside
 * `AuthorityChanged` with no ordering between the two (coordinator rulings INV-Q-4162, INV-Q-4171): each event
 * carries the whole key set and replaces the one held. Holding no valid key refuses every operator-session token.
 */
class AuthorityReplica(
    private val clock: Clock,
    val lagBound: Duration = DEFAULT_LAG_BOUND,
    private val fallback: ReplicaFallback<AuthorizationRequest, AuthorityVerdict>? = null,
) : ReplicaFeedSink<AuthorityChanged> {
    /** One feed, so one lag for all three record kinds. */
    val tracker = ReplicaLagTracker(clock)
    val grants =
        EventFedReplica<AuthorityKey, GrantState>(
            "authority-grant",
            clock,
            lagBound,
            tracker = tracker,
            partitionOf = { it.tenant to it.principalId },
        )
    val consents =
        EventFedReplica<ConsentKey, AuthorityRecord.ConsentRecord>(
            "authority-consent",
            clock,
            lagBound,
            tracker = tracker,
        )
    val principals =
        EventFedReplica<PrincipalKey, PrincipalRevocation>("authority-principal", clock, lagBound, tracker = tracker)

    /** Both key sets, fed by one `OperatorSessionKeysChanged` (INV-Q-4162, INV-Q-4171, INV-Q-9028 A). */
    val keySets = AuthorityKeySets(clock)

    /** access-svc's operator-session signing keys (INV-Q-4162, INV-Q-4171). */
    val operatorSessionKeys = keySets.operator

    /** The machine-access signing keys; a key is in exactly one of the two sets (INV-Q-9028 A). */
    val machineAccessKeys = keySets.machine

    fun decide(
        request: AuthorizationRequest,
        deadline: Deadline,
    ): AuthorityDecision {
        if (tracker.lag().isWithin(lagBound)) return decideLocally(request)
        return when (val answer = fallback?.fetch(request, deadline) ?: FallbackResult.Unavailable) {
            is FallbackResult.Found -> {
                AuthorityDecision(
                    answer.value.allowed,
                    DecisionSource.FALLBACK,
                    if (answer.value.allowed) DecisionReason.GRANTED else DecisionReason.NO_GRANT,
                )
            }

            FallbackResult.NotFound -> {
                AuthorityDecision(false, DecisionSource.FALLBACK, DecisionReason.NO_GRANT)
            }

            FallbackResult.Unavailable -> {
                degraded(request.principal)
            }
        }
    }

    private fun decideLocally(request: AuthorizationRequest): AuthorityDecision {
        val held = grants.peek(AuthorityKey(request.tenant, request.principal.id, request.entityRef))
        val voidBelow = principals.peek(PrincipalKey(request.tenant, request.principal.id))?.revocationVersion
        val now = clock.instant()
        val covering = held?.grants.orEmpty().filter { it.covers(request.action, now, request.resourcePeriod) }
        return when {
            held == null -> {
                local(DecisionReason.NO_GRANT)
            }

            voidBelow != null && held.authorityVersion < voidBelow -> {
                local(DecisionReason.REVOKED)
            }

            covering.any { it.scopeLevel != ScopeLevel.CONSENT } -> {
                local(DecisionReason.GRANTED)
            }

            covering.isEmpty() -> {
                local(DecisionReason.NO_GRANT)
            }

            consents
                .peek(ConsentKey(request.tenant, request.entityRef))
                ?.let { it.isLiveAt(now) && it.lists(request.requiredScopeItem) } == true -> {
                local(DecisionReason.GRANTED)
            }

            consents.peek(ConsentKey(request.tenant, request.entityRef))?.isLiveAt(now) == true -> {
                local(DecisionReason.CONSENT_SCOPE_MISSING)
            }

            else -> {
                local(DecisionReason.NO_LIVE_CONSENT)
            }
        }
    }

    /**
     * The entities on which [principalId] holds a grant in [tenant] now: the end-user principal's entity set
     * (INV-Q-3716, INV-REQ-1202 b4). A grant record voided by a principal record is not held; an entity counts
     * when one of its grants has a recognised scope level, is inside its validity window and, for a CONSENT
     * grant, has a live consent (INV-REQ-865). The period of an ENTITY_PERIOD grant is checked per decision.
     */
    fun heldEntities(
        tenant: TenantId,
        principalId: String,
    ): Set<String> {
        val voidBelow = principals.peek(PrincipalKey(tenant, principalId))?.revocationVersion ?: Long.MIN_VALUE
        val now = clock.instant()
        return grants
            .heldIn(tenant to principalId)
            .filter { (key, state) ->
                val consentLive by lazy { consents.peek(ConsentKey(tenant, key.entityRef))?.isLiveAt(now) == true }
                state.authorityVersion >= voidBelow &&
                    state.grants.any {
                        it.isValidAt(now) && it.scopeLevel != ScopeLevel.UNKNOWN &&
                            (it.scopeLevel != ScopeLevel.CONSENT || consentLive)
                    }
            }.keys
            .mapTo(mutableSetOf()) { it.entityRef }
    }

    private fun local(reason: DecisionReason): AuthorityDecision {
        val allowed = reason == DecisionReason.GRANTED
        return AuthorityDecision(allowed, DecisionSource.LOCAL, reason)
    }

    private fun degraded(principal: Principal): AuthorityDecision =
        if (principal.kind == PrincipalKind.MACHINE) {
            AuthorityDecision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
        } else {
            AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
        }

    override fun accept(event: AuthorityChanged) {
        val version = event.authorityVersion
        when (val record = event.record) {
            is AuthorityRecord.GrantRecord -> {
                val key = AuthorityKey(event.tenant, event.principalId, record.entityRef)
                if (record.grants.isEmpty()) {
                    grants.apply(ReplicaChange.Remove(key, version))
                } else {
                    grants.apply(ReplicaChange.Upsert(key, GrantState(record.grants, version), version))
                }
            }

            is AuthorityRecord.ConsentRecord -> {
                consents.apply(ReplicaChange.Upsert(ConsentKey(event.tenant, record.clientEntityRef), record, version))
            }

            is AuthorityRecord.PrincipalRecord -> {
                revoke(event, record.state)
            }

            null -> {
                revoke(event, null)
                grants.tombstoneIn(event.tenant to event.principalId)
            }
        }
    }

    private fun revoke(
        event: AuthorityChanged,
        state: PrincipalState?,
    ) {
        val key = PrincipalKey(event.tenant, event.principalId)
        val version = event.authorityVersion
        principals.apply(ReplicaChange.Upsert(key, PrincipalRevocation(state, version), version))
    }

    override fun observeHead(version: Long) = tracker.observeHead(version)

    override fun onSnapshotComplete(version: Long) = tracker.onSnapshotComplete(version)

    override fun onDisconnected() = tracker.onDisconnected()

    fun lag(): Lag = tracker.lag()

    companion object {
        /** INV-REQ-1201: lag at most one second is decided locally. */
        val DEFAULT_LAG_BOUND: Duration = Duration.ofSeconds(1)
    }
}
