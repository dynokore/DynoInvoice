package com.dynokore.platform.productruntime.feed

import com.dynokore.gst.productRuntimeAuthorityHeadEvents.adapters.events.model.AuthorityHeadObservedPayload
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica

/**
 * Feeds the `AuthorityHeadObserved` heartbeat of access-svc (a platform event, INV-Q-17070) into the authority replica's lag tracker (INV-REQ-1201,
 * INV-Q-9047 A): the head it reports is the highest committed authority version, so a replica at that version has
 * applied every change. The payload is the generated access type (INV-Q-13597 A). A payload without a version, or with
 * one below 1, and an event of another type are a [ContractViolation] (ARC 4.2 step 5).
 */
class AuthorityHeadFeed(
    private val replica: AuthorityReplica,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val head = decodeHead(event)
        replica.observeHead(head.version, head.incarnation)
        return HandleOutcome.APPLIED
    }

    /** The authority version [event] reports; throws [ContractViolation] when it does not meet the contract. */
    fun decode(event: Envelope): Long = decodeHead(event).version

    /** The version and the incarnation (null when absent, read as 1 by the replica) [event] reports. */
    fun decodeHead(event: Envelope): ObservedHead {
        if (event.type != PlatformEventCatalogue.AUTHORITY_HEAD_OBSERVED.eventType) {
            throw ContractViolation("the authority head feed takes no ${event.type}")
        }
        val payload = ContractJson.read(event.dataJson, AuthorityHeadObservedPayload::class.java)
        val version = payload.authorityVersion.toLong()
        if (version < 1) {
            throw ContractViolation(
                "AuthorityHeadObserved ${event.id} carries authorityVersion $version, the contract 1 or more",
            )
        }
        val incarnation = payload.incarnation?.toLong()
        if (incarnation != null && incarnation < 1) {
            throw ContractViolation(
                "AuthorityHeadObserved ${event.id} carries incarnation $incarnation, the contract 1 or more",
            )
        }
        return ObservedHead(version, incarnation)
    }

    /** The head of one heartbeat: [version] within [incarnation] (null when the payload has none). */
    data class ObservedHead(
        val version: Long,
        val incarnation: Long?,
    )
}
