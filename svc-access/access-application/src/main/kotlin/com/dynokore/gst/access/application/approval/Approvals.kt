package com.dynokore.gst.access.application.approval

import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.domain.approval.AckRef
import com.dynokore.gst.access.domain.approval.ApproverStanding
import com.dynokore.gst.access.domain.approval.OverrideDocument
import com.dynokore.gst.access.domain.approval.OverridePolicy
import com.dynokore.gst.access.domain.approval.OverrideReleaseOption
import com.dynokore.gst.access.domain.approval.OverrideReleaseRules
import com.dynokore.gst.access.domain.approval.OverrideRequest
import com.dynokore.gst.access.domain.approval.OverrideVerdict
import com.dynokore.gst.access.domain.approval.PackApprovalOption
import com.dynokore.gst.access.domain.approval.PackApprovalRules
import com.dynokore.gst.access.domain.approval.PackVerdict
import com.dynokore.gst.access.domain.consent.AgentConsentPolicy
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The values of VP-03, VP-04 and VP-32 in force for an entity today (business date, Asia/Singapore), read live from
 * the policy store of this service: a failed read or a value this service does not know throws, and no other value is
 * returned in its place (fail closed).
 */
interface ApprovalSettings {
    /** VP-03 of [entity]. */
    fun staffOption(entity: EntityRef): PackApprovalOption

    /** VP-04 of the client entity [client]. */
    fun agentOption(client: EntityRef): PackApprovalOption

    /** VP-32 of [entity]. */
    fun overridePolicy(entity: EntityRef): OverridePolicy
}

/** The kind of grantee an identity of the bound tenant was created as (STAFF, EXTERNAL or CROSS_TENANT). */
fun interface Identities {
    fun kindOf(identity: IdentityRef): GranteeKind?
}

/** Whether the error catalogue holds [code] with the disposition OVERRIDABLE; a code it does not hold is not. */
fun interface ErrorDispositions {
    fun overridable(code: String): Boolean
}

/** A client's acknowledgement of a mapping pack version, as `acknowledgeMapping` stored it (INV-REQ-996). */
data class MappingAcknowledgement(
    val packVersion: String,
    val clientRef: String,
    val acknowledgedAt: Instant,
)

interface MappingAcknowledgements {
    /** The acknowledgement for exactly this pair under the bound tenant, or null. */
    fun find(
        packVersion: String,
        clientRef: String,
    ): MappingAcknowledgement?
}

/** The permission keys of the catalogue the decisions read (INV-Q-10027): never a role name. */
object ApprovalPermissions {
    val APPROVE_PACK = Permission("mapping-pack.approve")
    val MANAGE_OVERRIDE_POLICY = Permission("override-policy.manage")
    val OVERRIDE = Permission("validation.override")
}

/** The answer of `decidePackApproval` with the value in force. */
data class PackApprovalAnswer(
    val verdict: PackVerdict,
    val option: PackApprovalOption,
)

/** The answer of `decideOverrideRelease` with the value in force. */
data class OverrideReleaseAnswer(
    val verdict: OverrideVerdict,
    val option: OverrideReleaseOption,
)

/** What the grants of a person say about the entity of a pack, read live in the caller's transaction. */
class ApproverStandings(
    private val authorise: Authorise,
    private val identities: Identities,
) {
    fun of(
        person: IdentityRef,
        entity: EntityRef,
    ): ApproverStanding {
        val covering = authorise.coveringGrants(person, ApprovalPermissions.APPROVE_PACK, Target(entity))
        if (covering.isEmpty()) return ApproverStanding()
        val kind = identities.kindOf(person)
        val own = covering.filter { it.scope !is Scope.ConsentDerived }
        return ApproverStanding(
            staff = kind == GranteeKind.STAFF && own.isNotEmpty(),
            externalOnEntity =
                kind == GranteeKind.EXTERNAL && own.any { (it.scope as? Scope.Entity)?.entity == entity },
            agentSecondPerson = covering.any { it.scope is Scope.ConsentDerived },
            clientOwnApprover = kind == GranteeKind.STAFF && own.isNotEmpty(),
        )
    }
}

/**
 * `decidePackApproval` (INV-Q-10695 R3): the VP-04 value of the client entity applies when that entity is under a live
 * agent consent that holds MAPPING_APPROVAL, otherwise the VP-03 value of the entity. Always an answer, never an HTTP
 * error; the decision carries a DENY. Reads the live grants, consent and acknowledgement at every question.
 */
class DecidePackApprovalUseCase(
    private val tx: TransactionRunner,
    private val settings: ApprovalSettings,
    private val standings: ApproverStandings,
    private val consents: ConsentRepository,
    private val acks: SoleOperatorAcks,
    private val clock: Clock,
) {
    fun handle(
        entity: EntityRef,
        author: IdentityRef,
        approver: IdentityRef,
        priorApprovers: List<IdentityRef>,
    ): PackApprovalAnswer =
        tx.inTransaction {
            val today = clock.instant().atZone(BUSINESS_ZONE).toLocalDate()
            val agentManaged =
                AgentConsentPolicy.permitsClient(
                    consents.findLiveForClient(entity),
                    entity,
                    ScopeItem.MAPPING_APPROVAL,
                    today,
                )
            val option = if (agentManaged) settings.agentOption(entity) else settings.staffOption(entity)
            val standing = standings.of(approver, entity)
            val verdict =
                PackApprovalRules.decide(
                    option,
                    author,
                    approver,
                    standing,
                    acks.findActive()?.let { AckRef(it.ref) },
                    priorApprovers.any { it != author && standings.of(it, entity).agentSecondPerson },
                )
            PackApprovalAnswer(verdict, option)
        }

    private companion object {
        val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Singapore")
    }
}

/**
 * `decideOverrideRelease` (INV-Q-10695 R4): the catalogue disposition of the code, the VP-32 value of the entity, the
 * people who approved before and the sole-operator fact of an approver who also holds `override-policy.manage`.
 */
class DecideOverrideReleaseUseCase(
    private val tx: TransactionRunner,
    private val settings: ApprovalSettings,
    private val dispositions: ErrorDispositions,
    private val authorise: Authorise,
    private val acks: SoleOperatorAcks,
) {
    fun handle(
        entity: EntityRef,
        errorCode: String,
        document: OverrideDocument?,
        approver: IdentityRef,
        priorApprovers: List<IdentityRef>,
    ): OverrideReleaseAnswer =
        tx.inTransaction {
            val policy = settings.overridePolicy(entity)
            val manages =
                authorise.handle(approver, ApprovalPermissions.MANAGE_OVERRIDE_POLICY, Target(entity)) is Decision.Allow
            val request =
                OverrideRequest(
                    overridable = dispositions.overridable(errorCode),
                    document = document,
                    approver = approver,
                    priorApprovers = priorApprovers,
                    managesOverridePolicy = manages,
                    activeAck = acks.findActive()?.let { AckRef(it.ref) },
                )
            OverrideReleaseAnswer(OverrideReleaseRules.decide(policy, request), policy.option)
        }
}

/** `getMappingAcknowledgement`: the stored acknowledgement of exactly this pack version and client, or null. */
class ReadMappingAcknowledgementUseCase(
    private val tx: TransactionRunner,
    private val store: MappingAcknowledgements,
) {
    fun handle(
        packVersion: String,
        clientRef: String,
    ): MappingAcknowledgement? = tx.inTransaction { store.find(packVersion, clientRef) }
}

/** The installation fact (INV-REQ-992): whether a `tax.approver` distinct from the server operator exists. */
data class InstallationApproverFact(
    val distinctTaxApprover: Boolean,
    val recordedAt: Instant,
)

interface InstallationFacts {
    fun find(): InstallationApproverFact?

    /** Stores the fact, replacing an earlier one; returns what is stored. */
    fun put(
        distinctTaxApprover: Boolean,
        at: Instant,
    ): InstallationApproverFact
}

/** `recordInstallationApproverFact`: stores the fact and audits it with the acting user ID, in one transaction. */
class RecordInstallationFactUseCase(
    private val tx: TransactionRunner,
    private val facts: InstallationFacts,
    private val audit: GrantAudit,
    private val clock: Clock,
) {
    fun handle(
        actor: String,
        distinctTaxApprover: Boolean,
    ): InstallationApproverFact =
        tx.inTransaction {
            val prior = facts.find()
            val stored = facts.put(distinctTaxApprover, clock.instant())
            audit.record(
                GrantAuditEntry(
                    actor = actor,
                    action = "recordInstallationApproverFact",
                    scope = "installation",
                    reason = null,
                    priorStateJson = prior?.let { """{"distinctTaxApprover":${it.distinctTaxApprover}}""" },
                ),
            )
            stored
        }
}

/** `getInstallationApproverFact`: the stored fact, or null before any record. */
class ReadInstallationFactUseCase(
    private val tx: TransactionRunner,
    private val facts: InstallationFacts,
) {
    fun handle(): InstallationApproverFact? = tx.inTransaction { facts.find() }
}
