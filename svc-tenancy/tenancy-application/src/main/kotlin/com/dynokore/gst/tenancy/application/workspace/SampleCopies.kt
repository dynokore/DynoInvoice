package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.tenant.WorkspaceStore
import com.dynokore.gst.tenancy.domain.SampleAnonymiser
import com.dynokore.gst.tenancy.domain.SampleRow
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner

/** Whether anonymised production samples are on for the bound tenant: VP-44, off by default (INV-REQ-1305). */
fun interface ProductionSamplePolicy {
    fun isOn(): Boolean
}

/**
 * Whether [person] holds the tenant-scope permission key [permission] in the bound tenant, as
 * `AuthorityReplica.decideTenant` answers it (INV-Q-10027, INV-Q-11901 A). An unknown person, a revoked or expired
 * grant and an unavailable replica all answer false (fail closed); an exception is treated as false by the caller.
 */
fun interface TenantAuthority {
    fun holds(
        person: String,
        permission: String,
    ): Boolean
}

/** The permission keys of the sample copy (INV-T-11910): approval and request are two keys, never a role name. */
object SampleCopyPermissions {
    const val APPROVE = "sample-copy.approve"
    const val REQUEST = "sample-copy.request"
}

/** The approvals recorded for sample copies: one per copy, spent by the copy it approves. */
interface SampleApprovals {
    fun record(
        sampleRef: String,
        approvedBy: String,
    )

    /** The user who recorded the earliest unspent approval of [sampleRef], or null when there is none. */
    fun open(sampleRef: String): String?

    /** Spends one open approval of [sampleRef]; false when there is none. */
    fun spend(sampleRef: String): Boolean
}

/**
 * Copies rows into a workspace. The copy between workspaces runs through this port, bound in Wave 3 (coordinator ruling
 * INV-Q-4900); the unbound adapter refuses every call.
 */
fun interface SampleCopyPort {
    fun copy(
        workspace: TenantId,
        sampleRef: String,
        rows: List<SampleRow>,
    )
}

/**
 * The rows of a production sample, read from the service that owns them; bound in Wave 3 (coordinator ruling
 * INV-Q-4900), the unbound adapter refuses every call.
 */
fun interface SampleRowSource {
    fun rows(sampleRef: String): List<SampleRow>
}

/** Where a sample's rows are read from and where the anonymised copy is written. */
data class SampleTransfer(
    val source: SampleRowSource,
    val port: SampleCopyPort,
)

enum class SampleCopyRefusal { SAMPLES_OFF, NO_SUCH_WORKSPACE, NOT_APPROVED }

sealed interface SampleCopyOutcome {
    /** The caller does not hold `sample-copy.request`: nothing is copied (403 SRT-004). */
    data object Forbidden : SampleCopyOutcome

    data class Copied(
        val rows: Int,
    ) : SampleCopyOutcome

    data class Refused(
        val reason: SampleCopyRefusal,
    ) : SampleCopyOutcome
}

sealed interface SampleApprovalOutcome {
    /** The caller does not hold `sample-copy.approve`: nothing is recorded (403 SRT-004). */
    data object Forbidden : SampleApprovalOutcome

    data object SamplesOff : SampleApprovalOutcome

    data class Recorded(
        val approvedBy: String,
    ) : SampleApprovalOutcome

    /** An unspent approval of the sample exists: no second one is recorded. */
    data class AlreadyRecorded(
        val approvedBy: String,
    ) : SampleApprovalOutcome
}

/**
 * Copying an anonymised production sample into a workspace of the bound production tenant (INV-REQ-1130, INV-REQ-1305).
 * Refused while the setting is off (the default); when on, each copy needs one approval recorded by a user who holds
 * `sample-copy.approve`, and arrives anonymised with amounts kept. Nothing reaches the port on a refusal. Both
 * operations are decided by the scoped permission key through [TenantAuthority], never by a role name.
 */
class SampleCopies(
    private val tx: TransactionRunner,
    private val store: WorkspaceStore,
    private val policy: ProductionSamplePolicy,
    private val approvals: SampleApprovals,
    private val authority: TenantAuthority,
    private val transfer: SampleTransfer,
) {
    /** Records the approval of a copy of [sampleRef] by the acting user [actor]; the approval stores the user ID. */
    fun approve(
        actor: String,
        sampleRef: String,
    ): SampleApprovalOutcome =
        when {
            !held(actor, SampleCopyPermissions.APPROVE) -> {
                SampleApprovalOutcome.Forbidden
            }

            else -> {
                tx.inTransaction {
                    val existing = approvals.open(sampleRef)
                    when {
                        !policy.isOn() -> {
                            SampleApprovalOutcome.SamplesOff
                        }

                        existing != null -> {
                            SampleApprovalOutcome.AlreadyRecorded(existing)
                        }

                        else -> {
                            approvals.record(sampleRef, actor)
                            SampleApprovalOutcome.Recorded(actor)
                        }
                    }
                }
            }
        }

    fun copy(
        actor: String,
        workspaceRef: String,
        sampleRef: String,
    ): SampleCopyOutcome =
        if (!held(actor, SampleCopyPermissions.REQUEST)) {
            SampleCopyOutcome.Forbidden
        } else {
            tx.inTransaction {
                val workspace = store.workspace(workspaceRef)
                when {
                    !policy.isOn() -> {
                        SampleCopyOutcome.Refused(SampleCopyRefusal.SAMPLES_OFF)
                    }

                    workspace == null || workspace.retired -> {
                        SampleCopyOutcome.Refused(SampleCopyRefusal.NO_SUCH_WORKSPACE)
                    }

                    !approvals.spend(sampleRef) -> {
                        SampleCopyOutcome.Refused(SampleCopyRefusal.NOT_APPROVED)
                    }

                    else -> {
                        val anonymised = SampleAnonymiser.anonymise(transfer.source.rows(sampleRef))
                        transfer.port.copy(TenantId(workspace.tenantId), sampleRef, anonymised)
                        SampleCopyOutcome.Copied(anonymised.size)
                    }
                }
            }
        }

    private fun held(
        actor: String,
        permission: String,
    ): Boolean =
        try {
            authority.holds(actor, permission)
        } catch (_: RuntimeException) {
            false
        }
}
