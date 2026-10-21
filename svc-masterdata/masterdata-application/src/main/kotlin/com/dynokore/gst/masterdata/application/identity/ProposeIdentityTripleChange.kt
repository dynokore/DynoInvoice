package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** `configureIdentityTriple`: [proposer] proposes the triple of [entity] under [constitution] from [effectiveFrom]. */
data class ProposeCommand(
    val entity: EntityRef,
    val constitution: Constitution,
    val triple: IdentityTriple,
    val effectiveFrom: LocalDate,
    val proposer: PersonRef,
)

/**
 * INV-REQ-1007: a proposal only records a PENDING_CONFIRMATION change. Nothing is put in force, so a document resolved
 * meanwhile carries the previous triple; the change takes effect when a different person confirms it
 * ([ConfirmIdentityTripleChange]).
 */
class ProposeIdentityTripleChange(
    private val tx: TransactionRunner,
    private val changes: PendingChangeRepository,
) {
    fun handle(command: ProposeCommand): PendingChange =
        tx.inTransaction {
            changes.propose(
                TripleVersion(command.entity, command.constitution, command.triple, command.effectiveFrom),
                command.proposer,
            )
        }
}
