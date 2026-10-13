package com.dynokore.platform.productruntime.feed

import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayload
import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadConsentRecord
import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadGrantRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentScopeItem
import com.dynokore.platform.serviceruntime.replica.ConsentState
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.ScopeLevel

/**
 * Feeds `AuthorityChanged` into the authority replica (INV-REQ-1201, AD-26), decoded from the generated access
 * payload type (INV-Q-3852): the grant set of one (principal, entity) after the change, a consent record per client
 * entity, or a principal record (deleted or revoked), each versioned by `authorityVersion` (INV-Q-3259, INV-Q-3322,
 * INV-Q-3702). An event that carries none of the three is passed on with no record, so the replica drops what it
 * holds for that principal and denies (INV-REQ-1214); one that carries more than one does not meet the contract
 * ("exactly one record") and is a [ContractViolation], as is any payload that does not bind (ARC 4.2 step 5).
 */
class AuthorityFeedHandler(
    private val replica: AuthorityReplica,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        replica.accept(decode(event))
        return HandleOutcome.APPLIED
    }

    /** The service-runtime event for [event]; throws [ContractViolation] when it does not meet the contract. */
    fun decode(event: Envelope): AuthorityChanged {
        if (event.type != ProductEventTypes.AUTHORITY_CHANGED) {
            throw ContractViolation("the authority feed takes no ${event.type}")
        }
        val payload = ContractJson.read(event.dataJson, AuthorityChangedPayload::class.java)
        val records = listOfNotNull(payload.grantRecord, payload.consentRecord, payload.principalRecord)
        if (records.size > 1) {
            throw ContractViolation("AuthorityChanged ${event.id} carries ${records.size} records, the contract one")
        }
        val record =
            payload.grantRecord?.let(::grantRecord)
                ?: payload.consentRecord?.let(::consentRecord)
                ?: payload.principalRecord?.let { AuthorityRecord.PrincipalRecord(PrincipalState.of(it.state.value)) }
        return AuthorityChanged(event.tenant, payload.principalRef, payload.authorityVersion.toLong(), record)
    }

    private fun grantRecord(record: AuthorityChangedPayloadGrantRecord) =
        AuthorityRecord.GrantRecord(
            entityRef = record.entityRef,
            grants =
                record.grants.map {
                    Grant(
                        action = it.action,
                        scopeLevel = ScopeLevel.of(it.scopeLevel.value),
                        scopeRef = it.scopeRef,
                        validFrom = it.validFrom.toInstant(),
                        validUntil = it.validUntil?.toInstant(),
                        periodFrom = it.periodFrom,
                        periodTo = it.periodTo,
                    )
                },
        )

    private fun consentRecord(record: AuthorityChangedPayloadConsentRecord) =
        AuthorityRecord.ConsentRecord(
            clientEntityRef = record.clientEntityRef,
            state = ConsentState.of(record.state.value),
            scopeItems = record.scopeItems.mapTo(mutableSetOf()) { ConsentScopeItem.of(it.value) },
            validFrom = record.validFrom.toInstant(),
            validUntil = record.validUntil?.toInstant(),
        )
}
