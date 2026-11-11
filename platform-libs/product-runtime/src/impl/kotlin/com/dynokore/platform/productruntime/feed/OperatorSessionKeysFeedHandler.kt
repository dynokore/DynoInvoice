package com.dynokore.platform.productruntime.feed

import com.dynokore.gst.productRuntimeOperatorSessionKeysEvents.adapters.events.model.OperatorSessionKeysChangedPayload
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged

/**
 * Feeds `OperatorSessionKeysChanged` into the authority replica's key sets (INV-Q-16496 A, INV-Q-4162, INV-Q-4171),
 * decoded from the generated payload type (INV-Q-3852). One event carries every use, so the replica splits the items
 * itself; this handler passes all key items to `keySets.accept` (INV-Q-9028 A). An event of another type, a payload
 * that does not bind and one outside the schema bounds is a [ContractViolation] and changes nothing (ARC 4.2 step 5).
 * The keys have no fetch route (`getVerificationKeys` is gateway-only, INV-Q-4006 A), so the feed has no fallback.
 */
class OperatorSessionKeysFeedHandler(
    private val replica: AuthorityReplica,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        replica.keySets.accept(decode(event))
        return HandleOutcome.APPLIED
    }

    /** The service-runtime event for [event]; throws [ContractViolation] when it does not meet the contract. */
    fun decode(event: Envelope): OperatorSessionKeysChanged {
        if (event.type != PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED.eventType) {
            throw ContractViolation("the operator-session key feed takes no ${event.type}")
        }
        val payload = ContractJson.read(event.dataJson, OperatorSessionKeysChangedPayload::class.java)
        val items = payload.propertyKeys
        // the generated type does not enforce the bounds (INV-Q-17149 A): the handler holds the schema bounds
        if (items.size !in MIN_KEYS..MAX_KEYS) {
            throw ContractViolation(
                "OperatorSessionKeysChanged ${event.id} carries ${items.size} keys, the contract 1 to 4",
            )
        }
        val keys =
            items.map {
                requireBound("kid", it.kid.length, 1, MAX_KID)
                requireBound("alg", it.alg.length, 1, MAX_ALG)
                requireBound("publicKey", it.publicKey.length, 1, MAX_PUBLIC_KEY)
                OperatorSessionKey(
                    kid = it.kid,
                    alg = it.alg,
                    publicKey = it.publicKey,
                    validFrom = it.validFrom.toInstant(),
                    validUntil = it.validUntil.toInstant(),
                    use = it.use?.value,
                )
            }
        return OperatorSessionKeysChanged(keys)
    }

    private fun requireBound(
        name: String,
        length: Int,
        min: Int,
        max: Int,
    ) {
        if (length !in min..max) throw ContractViolation("key item $name has length $length, the contract $min to $max")
    }

    companion object {
        /** The `minItems`, `maxItems` and `maxLength` values of operator-session-keys-changed-payload.schema.json. */
        const val MIN_KEYS = 1
        const val MAX_KEYS = 4
        const val MAX_KID = 32
        const val MAX_ALG = 16
        const val MAX_PUBLIC_KEY = 8192
    }
}
