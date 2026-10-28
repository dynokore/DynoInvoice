package com.dynokore.gst.canonical.application.inbound

import com.dynokore.gst.canonical.domain.inbound.BuyerDisposition
import com.dynokore.gst.canonical.domain.inbound.Existing
import com.dynokore.gst.canonical.domain.inbound.FallbackKey
import com.dynokore.gst.canonical.domain.inbound.InboundEvent
import com.dynokore.gst.canonical.domain.inbound.UuidKey
import java.util.UUID

/**
 * Port: the purchase events recorded per Doc UUID version and the correlation lookups over them (INV-REQ-429,
 * INV-REQ-430, INV-REQ-569). Every operation reads or writes the session's tenant only. Append-only.
 */
interface InboundEventStore {
    /** Appends the row of [event] as [version] of [docUuid]; the key columns are the ones `InboundKeys` returns. */
    fun append(
        event: InboundEvent,
        docUuid: UUID,
        version: Int,
    )

    /** One [Existing] per Doc UUID having any row with the key's endpoint and supplier Doc UUID in its entity. */
    fun findByUuidKey(uuidKey: UuidKey): List<Existing>

    /** One [Existing] per Doc UUID whose latest version equals the key in every column. */
    fun findByFallbackKey(fallbackKey: FallbackKey): List<Existing>

    /** The `buyerDisposition` of the highest-version RECEIPT row of [docUuid], or null when it has none. */
    fun previousDisposition(docUuid: UUID): BuyerDisposition?
}
