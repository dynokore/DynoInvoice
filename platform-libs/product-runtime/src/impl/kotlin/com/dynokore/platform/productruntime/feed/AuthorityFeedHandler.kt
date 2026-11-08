package com.dynokore.platform.productruntime.feed

import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayload
import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadConsentRecord
import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadRoleGrantRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentScopeItem
import com.dynokore.platform.serviceruntime.replica.ConsentState
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState

/**
 * Feeds `AuthorityChanged` into the authority replica (INV-REQ-1201, AD-26), decoded from the generated access
 * payload type (INV-Q-3852): one role grant of a principal (`roleGrantRecord`, INV-Q-8972 A), a consent record per
 * client entity, or a principal record (deleted or revoked), each versioned by `authorityVersion` (INV-Q-3259,
 * INV-Q-3322, INV-Q-3702). A legacy per-action `grantRecord`, written before the publisher moved to role grants, is
 * decoded to [AuthorityRecord.LegacyGrant], which only advances the lag head. An event that carries none of the four
 * is passed on with no record, so the replica drops what it holds for that principal and denies (INV-REQ-1214); one
 * that carries more than one does not meet the contract ("exactly one record") and is a [ContractViolation], as is
 * any payload that does not bind (ARC 4.2 step 5).
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
        if (event.type != PlatformEventCatalogue.AUTHORITY_CHANGED.eventType) {
            throw ContractViolation("the authority feed takes no ${event.type}")
        }
        val payload = ContractJson.read(event.dataJson, AuthorityChangedPayload::class.java)
        val records =
            listOfNotNull(payload.grantRecord, payload.roleGrantRecord, payload.consentRecord, payload.principalRecord)
        if (records.size > 1) {
            throw ContractViolation("AuthorityChanged ${event.id} carries ${records.size} records, the contract one")
        }
        val record =
            payload.roleGrantRecord?.let(::roleGrantRecord)
                ?: payload.grantRecord?.let { AuthorityRecord.LegacyGrant(it.entityRef) }
                ?: payload.consentRecord?.let(::consentRecord)
                ?: payload.principalRecord?.let { AuthorityRecord.PrincipalRecord(PrincipalState.of(it.state.value)) }
        val incarnation = payload.incarnation?.toLong()
        if (incarnation != null && incarnation < 1) {
            throw ContractViolation(
                "AuthorityChanged ${event.id} carries incarnation $incarnation, the contract 1 or more",
            )
        }
        return AuthorityChanged(
            event.tenant,
            payload.principalRef,
            payload.authorityVersion.toLong(),
            record,
            incarnation,
        )
    }

    private fun roleGrantRecord(record: AuthorityChangedPayloadRoleGrantRecord): AuthorityRecord.RoleGrantRecord {
        // the generated type does not enforce maxLength (INV-Q-17149 A): the handler holds the schema bounds
        requireLength("grantRef", record.grantRef, MAX_GRANT_REF)
        requireLength("role", record.role, MAX_ROLE)
        record.scopeRef?.let { requireLength("scopeRef", it, MAX_SCOPE_REF) }
        return AuthorityRecord.RoleGrantRecord(
            grantRef = record.grantRef,
            state = RoleGrantState.of(record.state.value),
            grant =
                RoleGrant(
                    role = record.role,
                    scope = GrantScope.of(record.scopeLevel.value),
                    scopeRef = record.scopeRef,
                    validFrom = record.validFrom.toInstant(),
                    validUntil = record.validUntil?.toInstant(),
                    periodFrom = record.periodFrom,
                    periodTo = record.periodTo,
                ),
        )
    }

    private fun requireLength(
        name: String,
        value: String,
        max: Int,
    ) {
        if (value.length > max) throw ContractViolation("roleGrantRecord.$name is longer than $max characters")
    }

    private fun consentRecord(record: AuthorityChangedPayloadConsentRecord) =
        AuthorityRecord.ConsentRecord(
            clientEntityRef = record.clientEntityRef,
            state = ConsentState.of(record.state.value),
            scopeItems = record.scopeItems.mapTo(mutableSetOf()) { ConsentScopeItem.of(it.value) },
            validFrom = record.validFrom.toInstant(),
            validUntil = record.validUntil?.toInstant(),
        )

    companion object {
        /** The `maxLength` values of `roleGrantRecord` in authority-changed-payload.schema.json. */
        const val MAX_GRANT_REF = 64
        const val MAX_ROLE = 100
        const val MAX_SCOPE_REF = 64
    }
}
