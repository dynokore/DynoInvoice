package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.Resolution
import com.dynokore.gst.masterdata.domain.identity.SubmittingIdentity
import com.dynokore.gst.masterdata.domain.identity.inForceOn
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** The question: which triple does a document of [entity] under [constitution] dated [transactionDate] carry. */
data class ResolveCommand(
    val entity: EntityRef,
    val constitution: Constitution,
    val transactionDate: LocalDate,
    val submitter: SubmittingIdentity? = null,
)

/**
 * INV-REQ-310: the triple is taken from the configuration of (entity, constitution) in force on the document's date.
 * It is never taken from the entity's own GSTN, from the group, or from who submits (INV-REQ-311, INV-REQ-803); a
 * group member's triple is the one configured for the member. Nothing configured: [Resolution.Blocked] with MDM-005.
 */
class ResolveIdentityTriple(
    private val tx: TransactionRunner,
    private val triples: TripleRepository,
) {
    fun handle(command: ResolveCommand): Resolution =
        tx.inTransaction {
            val version = triples.versions(command.entity, command.constitution).inForceOn(command.transactionDate)
            if (version == null) {
                Resolution.Blocked(command.entity, command.constitution, command.transactionDate, command.submitter)
            } else {
                Resolution.Resolved(version, command.submitter)
            }
        }
}
