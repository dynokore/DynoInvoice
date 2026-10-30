package com.dynokore.gst.tenancy.application.installation

import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * One installer run-record step as the operation recordInstallationAction receives it (INV-REQ-1101 b5). [facts] holds
 * the installation facts the step sets, by fact key, already rendered as the stored text; it carries no free-form
 * input, so no secret can reach the entry (API-49).
 */
data class InstallationStep(
    val runId: UUID,
    val step: Int,
    val command: String,
    val stepName: String,
    val outcome: String,
    val occurredAt: Instant,
    val runIdentity: String,
    val deploymentOperator: String,
    val approvingEngineer: String?,
    val trainFrom: String?,
    val trainTo: String?,
    val facts: Map<String, String>,
) {
    /** SHA-256 of every field, length-prefixed: a step is the same request only if every field matches. */
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun part(value: String?) {
            val bytes = (value ?: NULL_MARK).toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        listOf(
            runId.toString(),
            step.toString(),
            command,
            stepName,
            outcome,
            occurredAt.toString(),
            runIdentity,
            deploymentOperator,
            approvingEngineer,
            trainFrom,
            trainTo,
        ).forEach(::part)
        facts.toSortedMap().forEach { (key, value) ->
            part(key)
            part(value)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private companion object {
        const val NULL_MARK = "\u0000null"
    }
}

/** What the installer is told: the step's audit entry and when it was first recorded. */
data class InstallationAck(
    val runId: UUID,
    val step: Int,
    val auditEntryId: UUID,
    val recordedAt: Instant,
)

/** A step as stored: its acknowledgement and the fingerprint of the request that created it. */
data class StoredInstallationStep(
    val ack: InstallationAck,
    val fingerprint: String,
)

sealed interface InstallationOutcome {
    val ack: InstallationAck

    /** The step is new: its facts are applied and one entry is written. */
    data class Recorded(
        override val ack: InstallationAck,
    ) : InstallationOutcome

    /** The (run id, step) is already recorded: nothing changed. [differing]: the body is not the original. */
    data class Repeated(
        override val ack: InstallationAck,
        val differing: Boolean,
    ) : InstallationOutcome
}

/** The append-only record of steps, one row per (run id, step), written once. */
interface InstallationStepStore {
    fun find(
        runId: UUID,
        step: Int,
    ): StoredInstallationStep?

    /** Appends the step with its audit entry id; the primary key refuses a second row of the same (run id, step). */
    fun append(
        step: InstallationStep,
        fingerprint: String,
        auditEntryId: UUID,
        recordedAt: Instant,
    )
}

/** Writes AuditEntryRecorded through the service's own outbox in the caller's transaction (INV-REQ-861). */
interface InstallationAudit {
    /** @return the id of the entry written. */
    fun record(
        step: InstallationStep,
        priorStateJson: String?,
    ): UUID
}

/** Counts a repeat of a recorded (run id, step) whose body differs from the original. */
fun interface InstallationActionMetrics {
    fun differingRepeat()
}

/**
 * recordInstallationAction (scope installation, caller the installer workload): in one transaction under the reserved
 * platform tenant, the step is stored, the facts it carries are applied by `occurredAt`, and one AuditEntryRecorded is
 * written. Idempotent by (run id, step), permanently: a repeat changes nothing and writes no second entry
 * (INV-Q-3245 A).
 */
@Suppress("LongParameterList")
class RecordInstallationAction(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val steps: InstallationStepStore,
    private val facts: InstallationFactRepository,
    private val audit: InstallationAudit,
    private val metrics: InstallationActionMetrics,
    private val clock: () -> Instant,
    private val actor: String,
) {
    fun handle(step: InstallationStep): InstallationOutcome =
        platform.bindPlatform(actor) { tx.inTransaction { record(step) } }

    private fun record(step: InstallationStep): InstallationOutcome {
        val fingerprint = step.fingerprint()
        steps.find(step.runId, step.step)?.let { return repeated(it, fingerprint) }
        val prior =
            step.facts.keys
                .sorted()
                .map { it to facts.find(it)?.value }
        val auditEntryId = audit.record(step, priorStateJson(prior))
        val recordedAt = clock()
        steps.append(step, fingerprint, auditEntryId, recordedAt)
        step.facts.forEach { (key, value) -> facts.putIfNotOlder(InstallationFact(key, value, step.occurredAt)) }
        return InstallationOutcome.Recorded(InstallationAck(step.runId, step.step, auditEntryId, recordedAt))
    }

    private fun repeated(
        stored: StoredInstallationStep,
        fingerprint: String,
    ): InstallationOutcome {
        val differing = stored.fingerprint != fingerprint
        if (differing) metrics.differingRepeat()
        return InstallationOutcome.Repeated(stored.ack, differing)
    }

    /** The facts the step sets as they were, as one JSON object; a fact not yet recorded is `null`. */
    private fun priorStateJson(prior: List<Pair<String, String?>>): String? =
        prior.takeIf { it.isNotEmpty() }?.joinToString(",", "{", "}") { (key, value) ->
            "${quote(key)}:${value?.let(::quote) ?: "null"}"
        }

    private fun quote(text: String): String =
        buildString {
            append('"')
            text.forEach {
                when {
                    it == '"' -> append("\\\"")
                    it == '\\' -> append("\\\\")
                    it.code < CONTROL_LIMIT -> append("\\u%04x".format(Locale.ROOT, it.code))
                    else -> append(it)
                }
            }
            append('"')
        }

    private companion object {
        const val CONTROL_LIMIT = 0x20
    }
}
