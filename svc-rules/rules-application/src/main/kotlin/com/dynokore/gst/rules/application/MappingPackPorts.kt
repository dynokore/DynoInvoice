package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.MappingAcceptanceRecord
import com.dynokore.gst.rules.domain.pack.MappingDraftEntry
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * The scoped permission keys of the mapping pack commands (owner ruling INV-Q-10027): compared by equality, decided per
 * request by the authority port. No role name appears in this module.
 */
object MappingPackPermissions {
    const val AUTHOR = "mapping-pack.author"
    const val APPROVE = "mapping-pack.approve"
    const val ACTIVATE = "mapping-pack.activate"
}

/** A mapping pack draft header as the store holds it (INV-Q-14648 A). [label] is also the version label. */
data class MappingPackDraft(
    val packId: String,
    val tenant: TenantId,
    val entityRef: String,
    val label: String,
    val authorUserId: String,
    val status: PackStatus,
)

/** One recorded approval; it attaches to the dry-run delta (INV-REQ-380 behaviour 2). */
data class MappingApproval(
    val approverUserId: String,
    val at: Instant,
    val deltaId: String,
    val soleOperator: Boolean,
    val soleOperatorAckRef: String?,
    val option: String,
    val outstanding: Set<String>,
)

data class MappingActivation(
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val activatedBy: String,
    val at: Instant,
    /** True when no distinct tax approver is recorded for the installation; always false in the SaaS form. */
    val noDistinctApprover: Boolean,
)

/** The draft store. A call that throws or refuses leaves nothing behind; each write is atomic in the adapter. */
interface MappingPackStore {
    fun draft(pack: MappingPackDraft)

    fun find(packId: String): MappingPackDraft?

    /** Replaces the entry of the same entry id (or adds it) and drops the acceptances of that entry id. */
    fun replaceEntry(
        packId: String,
        entry: MappingDraftEntry,
    )

    /** Entries ordered by entry id, after the cursor (exclusive), at most [limit]. */
    fun listEntries(
        packId: String,
        after: String?,
        limit: Int,
    ): List<MappingDraftEntry>

    fun recordAcceptance(
        packId: String,
        acceptance: MappingAcceptanceRecord,
    )

    fun acceptances(packId: String): List<MappingAcceptanceRecord>

    fun approvals(packId: String): List<MappingApproval>

    /** Records the approval and sets [resultingStatus] in one transaction. */
    fun recordApproval(
        packId: String,
        approval: MappingApproval,
        resultingStatus: PackStatus,
    )

    fun setStatus(
        packId: String,
        status: PackStatus,
    )

    /** Records the activation and sets ARMED in one transaction. */
    fun recordActivation(
        packId: String,
        activation: MappingActivation,
    )
}

/** The qualifying COMPLETE dry-run delta of a pack version, or null (INV-Q-14646 A). */
fun interface DryRunDeltaPort {
    fun qualifying(
        packType: String,
        packVersion: String,
    ): String?
}

/** Whether the actor holds the scoped permission for the entity (INV-Q-14647 A, INV-Q-14652 A); production refuses. */
fun interface PackActionAuthority {
    fun holds(
        actor: String,
        entityRef: String,
        permission: String,
    ): Boolean
}

sealed interface PackApprovalDecision {
    data class Allow(
        val soleOperator: Boolean,
        val soleOperatorAckRef: String?,
        val option: String,
        val outstanding: Set<String>,
    ) : PackApprovalDecision

    data class Deny(
        val reasonCode: String,
    ) : PackApprovalDecision

    data object Unavailable : PackApprovalDecision
}

/** What the approval decision is asked about: one request per approve attempt (INV-REQ-995, NIST SP 800-162). */
data class PackApprovalRequest(
    val entityRef: String,
    val packId: String,
    /** The version label of the pack. */
    val packVersion: String,
    /** The author's user ID, as stored. */
    val authorRef: String,
    /** The acting user's user ID. */
    val approverRef: String,
    val priorApproverRef: List<String>,
)

/** The approval decision asked of access-svc through a port whose production default refuses (INV-Q-14647 A). */
fun interface PackApprovalDecisionPort {
    fun decide(request: PackApprovalRequest): PackApprovalDecision
}

/** The installation fact of a private deployment: whether a distinct tax approver is recorded. */
sealed interface InstallationApproverFact {
    data class Recorded(
        val distinctTaxApprover: Boolean,
    ) : InstallationApproverFact

    data object NotRecorded : InstallationApproverFact

    data object Unavailable : InstallationApproverFact
}

/** Reads the installation approver fact; the production default refuses (Unavailable). */
fun interface InstallationApproverFactPort {
    fun read(): InstallationApproverFact
}

sealed interface MappingAcknowledgement {
    data object Recorded : MappingAcknowledgement

    data object NotRecorded : MappingAcknowledgement

    data object Unavailable : MappingAcknowledgement
}

/** Reads the client's acknowledgement of one pack version label for one client (entity); matched by equality. */
fun interface MappingAcknowledgementPort {
    fun read(
        packVersion: String,
        clientRef: String,
    ): MappingAcknowledgement
}

/**
 * The INFERRED, unaccepted approved rate sources that apply to the tenant, or null when the port cannot answer
 * (INV-Q-14651 A, INV-Q-16533 A); production refuses until INV-T-16546.
 */
fun interface RateSourcePort {
    fun blocking(
        tenant: TenantId,
        asOf: LocalDate,
    ): List<String>?
}

data class HolderCopyRequest(
    val tenant: TenantId,
    val packId: String,
    val packType: String,
    val versionLabel: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val minEngineVersion: String,
    val contents: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is HolderCopyRequest &&
            tenant == other.tenant &&
            packId == other.packId &&
            packType == other.packType &&
            versionLabel == other.versionLabel &&
            effectiveFrom == other.effectiveFrom &&
            effectiveTo == other.effectiveTo &&
            minEngineVersion == other.minEngineVersion &&
            contents.contentEquals(other.contents)

    override fun hashCode(): Int =
        listOf(tenant, packId, versionLabel, effectiveFrom, contents.contentHashCode()).hashCode()
}

sealed interface HolderCopyResult {
    data object Written : HolderCopyResult

    data object Unavailable : HolderCopyResult
}

/** Writes the signed holder copy of an activated pack (INV-Q-14650 A); signing is INV-T-16544, production refuses. */
fun interface HolderCopyPort {
    fun write(request: HolderCopyRequest): HolderCopyResult
}
