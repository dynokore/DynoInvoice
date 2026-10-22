package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.StructureNode
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.counterparty.after
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/** One write to [party] on behalf of [entity]: what changes, the evidence for it and where it comes from. */
data class RecordCommand(
    val entity: String,
    val party: PartyRef,
    val change: CounterpartyChange,
    val evidenceRef: String,
    val origin: WriteOrigin,
)

/**
 * INV-REQ-314, INV-REQ-1111, INV-REQ-2433, INV-REQ-2434, INV-REQ-2445, INV-REQ-2576: writes the next version of a
 * counterparty with the provenance its [WriteOrigin] fixes. Earlier versions stay as they were, so a later change of
 * the ARCS default or of a scheme status never alters a version recorded before it. Last write wins; no update is
 * refused because another one intervened. Every write is a new version and raises `MasterDataResolved` for the
 * command's entity in the same transaction.
 */
class RecordCounterparty(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val clock: Clock,
    private val publisher: MasterDataResolvedPublisher,
) {
    fun handle(command: RecordCommand): CounterpartyVersion =
        tx.inTransaction {
            val now = clock.instant()
            val content =
                parties
                    .current(
                        command.party,
                    )?.content
                    .after(command.change, command.evidenceRef, command.origin, now)
            parties.append(command.party, content, now).also { publisher.publish(command.entity, it) }
        }
}

/** `getCounterpartyHistory`: one page of versions in write order, the cursor being the last version of the page. */
class ReadCounterpartyHistory(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
) {
    data class Page(
        val versions: List<CounterpartyVersion>,
        val current: Long?,
        val nextAfter: Long?,
    )

    /** Null when the counterparty has no version (unknown reference). */
    fun handle(
        party: PartyRef,
        afterVersion: Long,
        limit: Int,
    ): Page? =
        tx.inTransaction {
            val current = parties.current(party) ?: return@inTransaction null
            val page = parties.history(party, afterVersion, limit + 1)
            val shown = page.take(limit)
            Page(shown, current.version, shown.lastOrNull()?.version?.takeIf { page.size > limit })
        }
}

/** Applies the node of a structure event (INV-Q-8427 A, INV-Q-3324: a version at or below the one held is ignored). */
class ApplyStructureNode(
    private val tx: TransactionRunner,
    private val nodes: StructureNodeRepository,
) {
    fun handle(node: StructureNode): Boolean = tx.inTransaction { nodes.apply(node) }
}
