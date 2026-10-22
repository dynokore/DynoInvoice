package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.StructureNode
import com.dynokore.platform.partyregistry.IdentifierScheme
import java.time.Instant

/** The counterparties of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface CounterpartyRepository {
    /**
     * The current version of the counterparty that holds the identifier ([scheme], [value]) in its current version;
     * null when none does. When several do, the one with the lowest party reference.
     */
    fun findByIdentifier(
        scheme: IdentifierScheme,
        value: String,
    ): CounterpartyVersion?

    /** The current (highest) version of [party]; null when it has none. */
    fun current(party: PartyRef): CounterpartyVersion?

    /**
     * Appends [content] as the next version of [party] and returns it. Two appends of one counterparty, even
     * concurrent, both get a version: neither is refused for the other (INV-REQ-2576, last write wins).
     */
    fun append(
        party: PartyRef,
        content: CounterpartyContent,
        recordedAt: Instant,
    ): CounterpartyVersion

    /** The versions of [party] above [afterVersion], ascending, at most [limit]; empty when it has none. */
    fun history(
        party: PartyRef,
        afterVersion: Long,
        limit: Int,
    ): List<CounterpartyVersion>
}

/** The structure nodes of the bound tenant, fed by the structure events (INV-Q-8427 A). */
interface StructureNodeRepository {
    /** Every node of the tenant. */
    fun all(): List<StructureNode>

    /** Stores [node]; a state with a structure version at or below the one held is ignored. True when stored. */
    fun apply(node: StructureNode): Boolean
}
