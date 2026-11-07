package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** Who is acting. Only [MACHINE] keeps working when no decision can be had (INV-REQ-1201 behaviours #4 and #5). */
enum class PrincipalKind { HUMAN, CONSENT_AGENT, MACHINE }

/**
 * The acting principal. Its kind comes from the propagated token, never from `AuthorityChanged` (INV-Q-3259).
 * A [PrincipalKind.MACHINE] principal exists only as the result of a verified machine access token (coordinator
 * rulings INV-Q-9028 A, INV-Q-9040 A, INV-Q-9032 A): the public constructor refuses that kind and the factory is
 * internal to service-runtime. A machine principal carries the token's [tenant] (null for a scope without one) and
 * the [entityCoverage] its `principal_scope` claim states, its [scope] and its [tokenId] (`jti`, null when absent;
 * both are audit data and not part of equality) and its [machineType], the `principal_type` claim unchanged and
 * opaque (role vocabulary stays out of this library, ARC-16): non-null exactly for kind [PrincipalKind.MACHINE], part
 * of equality.
 */
class Principal internal constructor(
    val id: String,
    val kind: PrincipalKind,
    val tenant: TenantId?,
    val entityCoverage: MachineEntityCoverage?,
    val scope: String? = null,
    val tokenId: String? = null,
    val machineType: String? = null,
) {
    constructor(id: String, kind: PrincipalKind) : this(id, kind, null, null, null, null, null) {
        require(kind != PrincipalKind.MACHINE) { "a machine principal is made only by a verified machine access token" }
    }

    init {
        require((kind == PrincipalKind.MACHINE) == (machineType != null)) {
            "machineType is set exactly for a machine principal"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Principal &&
            id == other.id && kind == other.kind && tenant == other.tenant && entityCoverage == other.entityCoverage &&
            machineType == other.machineType

    override fun hashCode(): Int = listOf(id, kind, tenant, entityCoverage, machineType).hashCode()

    override fun toString(): String = "Principal(id=$id, kind=$kind)"

    /**
     * The one coverage rule of a machine token (INV-Q-10955, INV-Q-13632 A), shared by the entity-ref filter and the
     * degraded decision: `entity:<ref>` covers that ref only; `tenant:<ref>` covers [value] only when `<ref>` is the
     * token's own tenant and [entityOfTenant] says [value] is an entity of it; `installation` and the rest cover
     * nothing.
     */
    internal fun coversEntity(
        value: String,
        entityOfTenant: (TenantId, String) -> Boolean,
    ): Boolean =
        when (val coverage = entityCoverage) {
            is MachineEntityCoverage.Entity -> {
                coverage.ref == value
            }

            is MachineEntityCoverage.Tenant -> {
                tenant != null && coverage.ref == tenant.toString() && entityOfTenant(tenant, value)
            }

            else -> {
                false
            }
        }

    internal companion object {
        fun machine(
            id: String,
            tenant: TenantId?,
            coverage: MachineEntityCoverage,
            machineType: String,
            scope: String? = null,
            tokenId: String? = null,
        ) = Principal(id, PrincipalKind.MACHINE, tenant, coverage, scope, tokenId, machineType)
    }
}

/**
 * The entities a machine token covers, read from its `principal_scope` claim by exact match (INV-AC-9148):
 * `entity:<ref>` one entity, `tenant:<ref>` the entities of the token's tenant, `installation` no entity-scoped
 * operation; every other value, an absent claim included, is [None] and covers nothing.
 */
sealed interface MachineEntityCoverage {
    data class Entity(
        val ref: String,
    ) : MachineEntityCoverage

    data class Tenant(
        val ref: String,
    ) : MachineEntityCoverage

    data object Installation : MachineEntityCoverage

    data object None : MachineEntityCoverage

    companion object {
        private val REF = Regex("^[^\\s,\\p{Cntrl}]{1,200}$")

        /** The coverage of [claim]; anything not exactly one of the three forms is [None]. */
        fun of(claim: Any?): MachineEntityCoverage {
            val text = claim as? String ?: return None
            return when {
                text == "installation" -> Installation

                text.startsWith(
                    "entity:",
                ) && REF.matches(text.removePrefix("entity:")) -> Entity(text.removePrefix("entity:"))

                text.startsWith(
                    "tenant:",
                ) && REF.matches(text.removePrefix("tenant:")) -> Tenant(text.removePrefix("tenant:"))

                else -> None
            }
        }
    }
}

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
    /**
     * A per-action grant record, as written before the publisher moved to [RoleGrantRecord] (INV-T-9234) and still
     * inside the event-log retention. It advances the lag head with its `authorityVersion` and changes no store: the
     * replica holds role grants only (INV-Q-8972 A), and a legacy event is not a record-less event, so it revokes
     * nothing.
     */
    data class LegacyGrant(
        val entityRef: String,
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

    /**
     * The state of one role grant of a principal, named by [grantRef] (INV-Q-8972 A): ACTIVE upserts it, REMOVED and
     * UNKNOWN remove it.
     */
    data class RoleGrantRecord(
        val grantRef: String,
        val state: RoleGrantState,
        val grant: RoleGrant,
    ) : AuthorityRecord

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
 * catalogue (INV-Q-3734). [operation] is the method and path template of the route being served, set by
 * service-runtime (the machine-token filter leaves it in [OperationKey.ATTRIBUTE]); a service cannot build one, and a
 * request without it is an unmarked operation in degraded mode (INV-Q-9065 A).
 *
 * [level] is the policy level the action is decided at (INV-Q-8972 A; default [ScopeLevel.ENTITY], the meaning every
 * request had before it existed). At INSTALLATION the grants read are those at scope INSTALLATION held under
 * `TenantId.PLATFORM`, whatever [tenant] says; at TENANT and GROUP the TENANT-scope grants of [tenant]; at the entity
 * levels the ENTITY row of the role-answer table. [entityRef] is ignored at INSTALLATION, TENANT and GROUP.
 * [action] is the permission key the generated operation-to-roles map is asked about.
 */
data class AuthorizationRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val action: String,
    val resourcePeriod: DateSpan? = null,
    val requiredScopeItem: ConsentScopeItem? = null,
    val operation: OperationKey? = null,
    val level: ScopeLevel = ScopeLevel.ENTITY,
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
 * The authority replica (INV-REQ-1201), fed by `AuthorityChanged` (INV-Q-3322 (b), INV-Q-8972 A). It holds three kinds
 * of record, each replaced whole and versioned on its own key:
 *  - role grants per (tenant, principal, grantRef): the role, the scope level, the scope reference, the validity and
 *    the period span; a removed grant is kept as a tombstone. No per-action record is held: an action is decided
 *    from the roles the [OperationRoleMap] lists for it (INV-Q-8972 A);
 *  - consent records per (tenant, client entityRef); a CONSENT grant decides only with a live one, so a
 *    withdrawal takes effect at the next check with no grant event (INV-REQ-865);
 *  - principal records per (tenant, principal), whose version voids every role grant of that principal
 *    with a lower version, so a reinstated principal gets no old grant back.
 * A change at or below the version held for its key is ignored. An event without a record is never kept:
 * the replica drops every role grant it holds for that principal (each key keeps its version as a
 * tombstone, so versions never go backwards) and voids lower versions as a principal record would, so a
 * lost revoke cannot leave a grant live; no entry means deny (INV-REQ-1214). A legacy per-action record
 * ([AuthorityRecord.LegacyGrant]) only advances the lag head.
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
@Suppress("TooManyFunctions") // one decision per scope: decide, decideTenant; INV-Q-11688 A (CS-50)
class AuthorityReplica(
    private val clock: Clock,
    private val operationRoles: OperationRoleMap,
    val lagBound: Duration = DEFAULT_LAG_BOUND,
    private val fallback: ReplicaFallback<AuthorizationRequest, AuthorityVerdict>? = null,
    private val degradedAllow: DegradedAllowTable = DegradedAllowTable.EMPTY,
    private val entityOfTenant: (TenantId, String) -> Boolean = { _, _ -> false },
    private val observer: DegradedAllowObserver = DegradedAllowObserver { },
    breakGlassCounter: BreakGlassCounter = BreakGlassCounter.NONE,
    internal val tombstoneTtl: Duration? = null,
    internal val eventLogRetention: Duration? = null,
    structure: StructureReplica? = null,
) : ReplicaFeedSink<AuthorityChanged> {
    init {
        require((tombstoneTtl == null) == (eventLogRetention == null)) {
            "tombstoneTtl and eventLogRetention are set together or not at all, " +
                "was tombstoneTtl=$tombstoneTtl, eventLogRetention=$eventLogRetention"
        }
        require(tombstoneTtl == null || eventLogRetention == null || tombstoneTtl > eventLogRetention) {
            "tombstoneTtl must be strictly greater than eventLogRetention, " +
                "was tombstoneTtl=$tombstoneTtl, eventLogRetention=$eventLogRetention"
        }
    }

    /** One feed, so one lag for all record kinds. */
    val tracker = ReplicaLagTracker(clock)
    val consents =
        EventFedReplica<ConsentKey, AuthorityRecord.ConsentRecord>(
            "authority-consent",
            clock,
            lagBound,
            tracker = tracker,
        )
    val principals =
        EventFedReplica<PrincipalKey, PrincipalRevocation>("authority-principal", clock, lagBound, tracker = tracker)

    private val roleBook = RoleBook(clock, lagBound, tracker, consents, principals, structure, tombstoneTtl)

    /** The role grants per (tenant, principal, grantRef) (INV-Q-8972 A). */
    val roleGrants: EventFedReplica<RoleGrantKey, RoleGrant> get() = roleBook.grants

    /** Both key sets, fed by one `OperatorSessionKeysChanged` (INV-Q-4162, INV-Q-4171, INV-Q-9028 A). */
    val keySets = AuthorityKeySets(clock)

    /** access-svc's operator-session signing keys (INV-Q-4162, INV-Q-4171). */
    val operatorSessionKeys = keySets.operator

    /** The break-glass sessions, fed by `BreakGlassSessionChanged` (INV-Q-9119 A). */
    val breakGlassSessions = BreakGlassSessionReplica(clock, breakGlassCounter)

    /** The machine-access signing keys; a key is in exactly one of the two sets (INV-Q-9028 A). */
    val machineAccessKeys = keySets.machine

    fun decide(
        request: AuthorizationRequest,
        deadline: Deadline,
    ): AuthorityDecision {
        if (tracker.lag().isWithin(lagBound)) return decideLocally(request, deadline)
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
                degraded(request)
            }
        }
    }

    /**
     * Decides an operation that names no entity: permitted only on a TENANT-scope grant of a role the
     * [OperationRoleMap] lists for [action] (INV-Q-11688 A, INV-AC-11706). The access contract cannot be asked for a
     * tenant scope, so beyond the lag bound, or with no version received, the answer is AUTHORITY_UNAVAILABLE for every
     * principal kind: no fallback call and no machine continuation. A grant voided by the principal record (same rule
     * as [decideLocally]) never grants; REVOKED when only such grants would.
     */
    fun decideTenant(
        tenant: TenantId,
        principal: Principal,
        action: String,
    ): AuthorityDecision {
        if (!tracker.lag().isWithin(lagBound)) {
            return AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
        }
        val request = AuthorizationRequest(tenant, principal, "", action, level = ScopeLevel.TENANT)
        return decideLocally(request, Deadline.after(Duration.ofSeconds(1), clock))
    }

    /**
     * The roles [principalId] holds in [tenant] at [level] (INV-Q-8972 A, INV-Q-8913 A). Internal: read by the
     * decision and by tests, never a public role answer (INV-Q-10107 C). [scopeRef] is not read; the level and
     * [entityRef] decide. Beyond the lag bound it throws and reads nothing.
     */
    @Suppress("UnusedParameter")
    internal fun rolesAt(
        tenant: TenantId,
        principalId: String,
        level: com.dynokore.platform.configresolver.ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        deadline: Deadline,
    ): Set<String> = roleBook.rolesAt(tenant, principalId, level, entityRef, deadline)

    /** The principals holding [role] at TENANT or INSTALLATION scope in [tenant]; any other [level] holds none. */
    fun holdersAt(
        tenant: TenantId,
        role: String,
        level: com.dynokore.platform.configresolver.ScopeLevel,
    ): Set<String> = roleBook.holdersAt(tenant, role, level)

    /** The principals holding [role] on [entityRef] of [tenant]: grants that cover that entity. */
    fun holdersOn(
        tenant: TenantId,
        role: String,
        entityRef: String,
        deadline: Deadline,
    ): Set<String> = roleBook.holdersOn(tenant, role, entityRef, deadline)

    /**
     * Granted when the principal holds a role grant that covers the request at its level (the ENTITY row of the
     * role-answer table at the entity levels), inside its validity window, not voided by a principal record, whose
     * role is one of the roles [operationRoles] lists for the action; any one listed role is enough. An ENTITY_PERIOD
     * grant also needs a span that encloses the request's period. A CONSENT grant also needs the live consent record
     * of the entity, read on every call, listing the required scope item.
     */
    private fun decideLocally(
        request: AuthorizationRequest,
        deadline: Deadline,
    ): AuthorityDecision {
        val listed = operationRoles.rolesFor(request.action).keys
        val candidates =
            roleBook
                .candidates(
                    request.tenant,
                    request.principal.id,
                    request.level,
                    request.entityRef,
                    request.resourcePeriod,
                    deadline,
                ).filter { it.grant.role in listed }
        val now = clock.instant()
        val verdicts =
            candidates.filter { !it.voided }.map { candidate ->
                if (candidate.grant.scope != GrantScope.CONSENT) {
                    DecisionReason.GRANTED
                } else {
                    val consent = consents.peek(ConsentKey(request.tenant, request.entityRef))
                    when {
                        consent == null || !consent.isLiveAt(now) -> DecisionReason.NO_LIVE_CONSENT
                        consent.lists(request.requiredScopeItem) -> DecisionReason.GRANTED
                        else -> DecisionReason.CONSENT_SCOPE_MISSING
                    }
                }
            }
        return when {
            DecisionReason.GRANTED in verdicts -> local(DecisionReason.GRANTED)
            verdicts.isNotEmpty() -> local(verdicts.first())
            candidates.isNotEmpty() -> local(DecisionReason.REVOKED)
            else -> local(DecisionReason.NO_GRANT)
        }
    }

    /**
     * The entities on which [principalId] holds a role grant in [tenant] now: the end-user principal's entity set
     * (INV-Q-3716, INV-REQ-1202 b4). A grant voided by a principal record or outside its validity window counts
     * for nothing. ENTITY and ENTITY_PERIOD grants give their `scopeRef`, a CONSENT grant its `scopeRef` while the
     * consent is live (INV-REQ-865), a GROUP grant the entities of that group and a TENANT grant every entity of the
     * tenant, both read from the structure replica without a fallback call (a retired entity is excluded). The
     * period of an ENTITY_PERIOD grant is checked per decision.
     */
    fun heldEntities(
        tenant: TenantId,
        principalId: String,
    ): Set<String> = roleBook.heldEntities(tenant, principalId)

    private fun local(reason: DecisionReason): AuthorityDecision {
        val allowed = reason == DecisionReason.GRANTED
        return AuthorityDecision(allowed, DecisionSource.LOCAL, reason)
    }

    /**
     * The answer when no decision can be had (INV-Q-9065 A): a MACHINE request whose operation is a row of the
     * degraded-allow table, whose token scope is that operation's principal, whose token tenant is the request's and
     * whose entity the token's coverage covers is allowed, once logged and counted; everything else is denied.
     */
    private fun degraded(request: AuthorizationRequest): AuthorityDecision {
        val principal = request.principal
        val operation = request.operation
        if (operation != null && permitted(request, operation)) {
            val allow = DegradedAllow(operation, request.tenant, principal.id, request.entityRef, principal.tokenId)
            LOG.log(System.Logger.Level.INFO, allow.line())
            observer.onAllow(allow)
            return AuthorityDecision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
        }
        return AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
    }

    /** MACHINE, a marked [operation], the operation's principal as scope, the request's tenant, a covered entity. */
    private fun permitted(
        request: AuthorizationRequest,
        operation: OperationKey,
    ): Boolean {
        val principal = request.principal
        val token = principal.kind == PrincipalKind.MACHINE && principal.scope == operation.principal
        return token && degradedAllow.marks(operation) && principal.tenant == request.tenant &&
            principal.coversEntity(request.entityRef, entityOfTenant)
    }

    override fun accept(event: AuthorityChanged) {
        roleBook.tombstones.evictExpired()
        val version = event.authorityVersion
        when (val record = event.record) {
            is AuthorityRecord.LegacyGrant -> {
                tracker.onApplied(version)
            }

            is AuthorityRecord.RoleGrantRecord -> {
                roleBook.accept(event.tenant, event.principalId, version, record)
            }

            is AuthorityRecord.ConsentRecord -> {
                consents.apply(ReplicaChange.Upsert(ConsentKey(event.tenant, record.clientEntityRef), record, version))
            }

            is AuthorityRecord.PrincipalRecord -> {
                revoke(event, record.state)
            }

            null -> {
                revoke(event, null)
                roleBook.tombstone(event.tenant, event.principalId)
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

        private val LOG: System.Logger = System.getLogger(AuthorityReplica::class.java.name)
    }
}
