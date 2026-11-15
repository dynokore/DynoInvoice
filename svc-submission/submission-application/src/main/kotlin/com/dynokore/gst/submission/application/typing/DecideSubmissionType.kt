package com.dynokore.gst.submission.application.typing

import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedData
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedDataReader
import com.dynokore.gst.submission.domain.typing.Direction
import com.dynokore.gst.submission.domain.typing.NetworkPath
import com.dynokore.gst.submission.domain.typing.RoutingFacts
import com.dynokore.gst.submission.domain.typing.SelfBillingAssignment
import com.dynokore.gst.submission.domain.typing.SubmissionType
import com.dynokore.gst.submission.domain.typing.SubmissionTypeDecision
import com.dynokore.gst.submission.domain.typing.TypeDecision
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome

/** The facts of one decided `SubmissionTypeDecided`: [submissionType] is the enum name of the decided type. */
data class TypeDecidedFacts(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val submissionType: String,
)

/** Stages one `SubmissionTypeDecided` in the caller's transaction (the adapter is slice 2). */
fun interface TypeDecisionSignals {
    fun stage(facts: TypeDecidedFacts)
}

/** Reads the optional `routingFacts` member of a `CanonicalVersionCreated` payload map. */
object RoutingFactsOf {
    /**
     * @return null when `routingFacts` is absent.
     * @throws ContractViolation on a member of another type or an unknown enum text.
     */
    fun of(payload: Map<String, Any?>): RoutingFacts? {
        if (!payload.containsKey("routingFacts")) return null
        val facts = member(payload, "routingFacts")
        return RoutingFacts(
            direction = enumOf<Direction>(facts, "direction"),
            networkPath = enumOf<NetworkPath>(facts, "networkPath"),
            counterpartyOnNetwork = optionalBoolean(facts, "counterpartyOnNetwork"),
            debitNote = optionalBoolean(facts, "debitNote") ?: false,
            selfBilling = selfBilling(facts),
        )
    }

    private fun selfBilling(facts: Map<String, Any?>): SelfBillingAssignment? {
        if (!facts.containsKey("selfBillingAssignment")) return null
        val a = member(facts, "selfBillingAssignment")
        return SelfBillingAssignment(enumOf<Direction>(a, "direction"), enumOf<SubmissionType>(a, "submissionType"))
    }

    private fun member(
        map: Map<String, Any?>,
        name: String,
    ): Map<String, Any?> {
        val value = map[name]
        if (value !is Map<*, *> || value.keys.any { it !is String }) {
            throw ContractViolation("CanonicalVersionCreated: $name is not an object")
        }

        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private inline fun <reified E : Enum<E>> enumOf(
        map: Map<String, Any?>,
        name: String,
    ): E {
        val text = map[name] as? String ?: throw ContractViolation("CanonicalVersionCreated: $name is not text")
        return enumValues<E>().firstOrNull { it.name == text }
            ?: throw ContractViolation("CanonicalVersionCreated: $name is not a known value")
    }

    private fun optionalBoolean(
        map: Map<String, Any?>,
        name: String,
    ): Boolean? {
        if (!map.containsKey(name)) return null
        return map[name] as? Boolean ?: throw ContractViolation("CanonicalVersionCreated: $name is not a boolean")
    }
}

/** Decides the submission type of one applied version (INV-REQ-415); the rule is [SubmissionTypeDecision]. */
class DecideSubmissionType(
    private val signals: TypeDecisionSignals,
) {
    /** Stages one fact set for a decided type and nothing for an undecided one. */
    fun decide(
        data: CanonicalVersionCreatedData,
        entityRef: String,
    ) {
        val decision = SubmissionTypeDecision.decide(RoutingFactsOf.of(data.payload))
        if (decision is TypeDecision.Decided) {
            signals.stage(TypeDecidedFacts(data.docUuid, data.version, entityRef, decision.type.name))
        }
    }
}

/**
 * Wraps the register handler: after it applies a version, stages the decided type in the same transaction. It opens
 * no transaction and catches nothing, so a failure rolls back the delegate's write too.
 */
class TypeDecidingHandler(
    private val delegate: EventHandler,
    private val reader: CanonicalVersionCreatedDataReader,
    private val decide: DecideSubmissionType,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val outcome = delegate.handle(event)
        if (outcome == HandleOutcome.APPLIED) {
            decide.decide(reader.read(event.dataJson), event.entity.value)
        }
        return outcome
    }
}
