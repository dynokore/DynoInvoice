package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.Finding
import com.dynokore.gst.masterdata.domain.counterparty.FindingCode
import com.dynokore.gst.masterdata.domain.counterparty.Sameness
import com.dynokore.gst.masterdata.domain.counterparty.SchemeStatus
import com.dynokore.gst.masterdata.domain.counterparty.findings
import com.dynokore.gst.masterdata.domain.counterparty.samenessOf
import com.dynokore.gst.masterdata.domain.counterparty.schemesInForceOn
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** The counterparty a document names by one of its identifiers, seen from [entity] on [transactionDate]. */
data class PartyQuestion(
    val entity: String,
    val scheme: IdentifierScheme,
    val value: String,
    val transactionDate: LocalDate,
)

/** The answer: the counterparty as held with its findings, or no counterparty and MDM-001. */
sealed interface PartyAnswer {
    val findings: List<Finding>

    data class Unresolved(
        override val findings: List<Finding> = listOf(Finding(FindingCode.MDM_001, "/partyValue")),
    ) : PartyAnswer

    data class Resolved(
        val version: CounterpartyVersion,
        override val findings: List<Finding>,
        val sameness: Sameness,
        val schemesInForce: List<SchemeStatus>,
    ) : PartyAnswer
}

/**
 * INV-REQ-313, INV-REQ-315, INV-REQ-720, INV-REQ-738, INV-REQ-2445: finds the counterparty, reports what blocks a
 * document to it, sets `sameTenant` and `sameGroup` from the structure nodes, and returns the approved scheme status
 * items in force at the transaction date. It never validates counterparty-supplied data as a precondition. The
 * scheme `NONE` is no key: its value `NA` is shared by every individual, so it finds no counterparty.
 */
class ResolveCounterparty(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val structure: StructureNodeRepository,
) {
    fun handle(question: PartyQuestion): PartyAnswer =
        tx.inTransaction {
            val found =
                if (question.scheme ==
                    IdentifierScheme.NONE
                ) {
                    null
                } else {
                    parties.findByIdentifier(question.scheme, question.value)
                }
            if (found == null) {
                PartyAnswer.Unresolved()
            } else {
                val content = found.content
                val uen = content.identifiers.firstOrNull { it.scheme == IdentifierScheme.UEN }?.value
                PartyAnswer.Resolved(
                    version = found,
                    findings = content.findings(),
                    sameness = samenessOf(question.entity, uen, structure.all()),
                    schemesInForce = content.schemesInForceOn(question.transactionDate),
                )
            }
        }
}
