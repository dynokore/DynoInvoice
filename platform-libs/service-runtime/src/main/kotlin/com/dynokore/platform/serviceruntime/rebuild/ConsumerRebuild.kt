package com.dynokore.platform.serviceruntime.rebuild

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.outboxandqueue.ReplayScope
import java.time.Instant
import java.util.UUID

/**
 * One rebuildConsumer request (platform-contracts `service-runtime/openapi.yaml`, INV-API-3656; owner ruling
 * INV-Q-3599). The actor comes from the propagated console end-user token, never a field (CS-23); [scope] is present
 * exactly when [mode] is [ReplayMode.SCOPE] (a mismatch is the code-less 400 of ARC-19, refused here by `require`).
 * [reason] is the caller's optional text; the audit entry's reason is that text, or else the [idempotencyKey]
 * (INV-Q-3663, INV-REQ-2588 b5).
 */
data class RebuildConsumerRequest(
    val actor: String,
    val topic: String,
    val mode: ReplayMode,
    val scope: ReplayScope?,
    val idempotencyKey: UUID,
    val reason: String? = null,
    val correlation: CorrelationId,
    val causation: CausationId,
) {
    init {
        require(actor.isNotBlank()) { "actor must not be blank" }
        require(topic.isNotBlank()) { "topic must not be blank" }
        require((mode == ReplayMode.SCOPE) == (scope != null)) { "a scope is given with SCOPE and only with SCOPE" }
        require(reason == null || (reason.isNotBlank() && reason.length <= MAX_REASON)) {
            "reason is 1 to $MAX_REASON characters"
        }
    }

    companion object {
        const val MAX_REASON = 500
    }
}

/**
 * `ReplayState` of the operation contract (service-runtime contract 1.2.0): the replay started (202) or the pending one
 * repeated (200). [producers] lists every addressee, one for a single-producer topic; [producer] is that one (a service
 * or 'every service'), or [SEVERAL_PRODUCERS] for a topic with several catalogue producers (owner ruling INV-Q-3788 A).
 */
data class ReplayState(
    val replayId: UUID,
    val topic: String,
    val producer: String,
    val producers: List<String>,
    val mode: ReplayMode,
    val scope: ReplayScope?,
    val requestedAt: Instant,
    val repeat: Boolean,
) {
    init {
        require(producers.isNotEmpty()) { "one producer or more" }
        require(producers.toSet().size == producers.size) { "distinct producers" }
        require(producer == producerOf(producers)) { "producer names the one addressee, or several" }
    }

    companion object {
        /** The `producer` literal of a replay addressed to several producers (INV-API-3656, INV-Q-3788 A). */
        const val SEVERAL_PRODUCERS = "several producers"

        /** The `producer` value for [producers]. */
        fun producerOf(producers: List<String>): String = producers.singleOrNull() ?: SEVERAL_PRODUCERS
    }
}

private const val HTTP_NOT_FOUND = 404
private const val HTTP_FORBIDDEN = 403
private const val HTTP_CONFLICT = 409

/** The catalogue codes of rebuildConsumer (owner ruling INV-Q-3667; platform-contracts gst-catalogue 1.11.0). */
enum class RebuildErrorCode(val code: String, val httpStatus: Int) {
    /** The topic is not one this service consumes. */
    TOPIC_NOT_CONSUMED("EVL-001", HTTP_NOT_FOUND),

    /** The caller's role may not rebuild a consumer (INV-Q-3663). */
    ROLE_NOT_PERMITTED("EVL-002", HTTP_FORBIDDEN),

    /** The consumer does not hold the topic in 'rebuild required'. */
    NOT_REBUILD_REQUIRED("EVL-003", HTTP_CONFLICT),

    /**
     * A replay for the (consumer, producer) pair, or for one of the pairs of a topic with several producers, is pending
     * with another replay id, mode or scope (INV-Q-3665, INV-Q-3788 A).
     */
    OTHER_REPLAY_PENDING("EVL-004", HTTP_CONFLICT),
}

/** The outcome: done with the replay's state, or refused with a catalogue code and nothing written (ARC-19). */
sealed interface RebuildOutcome {
    /** [created] is true when this call moved the head and wrote the request (202); false for a repeat (200). */
    data class Done(val state: ReplayState, val created: Boolean) : RebuildOutcome

    data class Refused(val code: RebuildErrorCode) : RebuildOutcome
}

/**
 * Who may rebuild a consumer: only the form's platform operator role, `platform.engineer` (SaaS) or
 * `deployment.operator` (private), for every mode and scope (coordinator ruling INV-Q-3663, INV-REQ-888). The service
 * answers against its authority replica (CS-23); the role names are product vocabulary, kept out of L1 (ARC-16).
 */
fun interface ConsumerRebuildAuthorizer {
    fun isPlatformOperator(actor: String): Boolean
}

/**
 * rebuildConsumer (INV-REQ-1205 b4, b6; owner ruling INV-Q-3599), served by service-runtime in every service that
 * consumes event-log topics: recovers a topic this service's consumer holds in 'rebuild required' by a replay by scope
 * or a full sync from the topic's producer. The head move, the `ReplayRequested` and the `AuditEntryRecorded` commit in
 * one transaction or not at all (INV-AC-3657); the replay runs after the call returns, and the incident closes when the
 * consumer applies the producer's `ReplayCompleted` (INV-AC-3658).
 */
interface ConsumerRebuildCommands {
    fun rebuildConsumer(request: RebuildConsumerRequest): RebuildOutcome
}
