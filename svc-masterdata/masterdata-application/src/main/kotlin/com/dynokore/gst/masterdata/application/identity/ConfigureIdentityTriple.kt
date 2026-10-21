package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.ConfirmationRefusal
import com.dynokore.gst.masterdata.domain.identity.ConfirmationRule
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.SecondConfirmation
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** `configureIdentityTriple`: [proposer] sets the triple of [entity] under [constitution] from [effectiveFrom]. */
data class ConfigureCommand(
    val entity: EntityRef,
    val constitution: Constitution,
    val triple: IdentityTriple,
    val effectiveFrom: LocalDate,
    val proposer: PersonRef,
    val confirmation: SecondConfirmation,
)

sealed interface ConfigureOutcome {
    /** The change took effect from [version].effectiveFrom. */
    data class Applied(
        val version: TripleVersion,
    ) : ConfigureOutcome

    /** The confirmation does not count; nothing was stored, so documents keep resolving to the previous triple. */
    data class Refused(
        val reason: ConfirmationRefusal,
    ) : ConfigureOutcome
}

/**
 * INV-REQ-1007, INV-REQ-1270: a change takes effect only with the second confirmation of a different person in a role
 * of the confirmer set. A refused change stores nothing. The stored version applies from its stated date, so a document
 * dated earlier keeps the previous triple.
 */
class ConfigureIdentityTriple(
    private val tx: TransactionRunner,
    private val triples: TripleRepository,
    private val policy: ConfirmerPolicy,
) {
    fun handle(command: ConfigureCommand): ConfigureOutcome =
        tx.inTransaction {
            val refusal = ConfirmationRule.check(command.proposer, command.confirmation, policy.confirmers())
            if (refusal != null) {
                ConfigureOutcome.Refused(refusal)
            } else {
                val version =
                    TripleVersion(command.entity, command.constitution, command.triple, command.effectiveFrom)
                triples.save(version, command.proposer, command.confirmation.person, command.confirmation.role)
                ConfigureOutcome.Applied(version)
            }
        }
}
