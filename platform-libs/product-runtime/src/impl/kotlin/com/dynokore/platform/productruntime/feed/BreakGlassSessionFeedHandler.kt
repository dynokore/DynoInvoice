package com.dynokore.platform.productruntime.feed

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.serviceruntime.events.PlatformEventReader
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionChanged

/**
 * Feeds `BreakGlassSessionChanged` into the break-glass session replica (INV-Q-9119 A, INV-REQ-896). The envelope is
 * read by service-runtime's [PlatformEventReader]; an event that does not meet the contract (wrong type, missing
 * `approvedBy`, ...) is a [ContractViolation], and an unknown status text is passed on, the replica ignores it.
 */
class BreakGlassSessionFeedHandler(
    private val replica: AuthorityReplica,
    private val reader: PlatformEventReader = PlatformEventReader(),
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        replica.breakGlassSessions.accept(decode(event))
        return HandleOutcome.APPLIED
    }

    /** The service-runtime event for [event]; throws [ContractViolation] when it does not meet the contract. */
    fun decode(event: Envelope): BreakGlassSessionChanged = reader.breakGlassSessionChanged(event)
}
