package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.ElectionRecord
import com.dynokore.gst.tenancy.application.election.ElectionWriteRefused
import com.dynokore.gst.tenancy.application.election.ISSUANCE_MODE_ATTRIBUTE
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.domain.ElectionKeys
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/**
 * The request-scoped marker of the election operations: set only around the write of [ConfigElectionPolicyStore] on
 * the current thread, for exactly that key (compared by equality, nothing normalised) and restored when the write
 * finishes or throws.
 */
object ElectionWriteMarker {
    private val current = ThreadLocal<VariationPointKey?>()

    fun <T> allowing(
        key: VariationPointKey,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(key)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    /** Whether a write of exactly [key] is in progress on this thread. */
    fun allows(key: VariationPointKey): Boolean = current.get() == key
}

/**
 * The guard on the [PolicyWriter] bean (ruling INV-Q-10023): the shared `setPolicy` is refused for VP-22, VP-23, VP-24
 * and VP-25 (422 POL-014, nothing stored, no audit entry) unless [ElectionWriteMarker] is set for that key, i.e. the
 * write comes from an election operation. Every other key, an unknown one included, goes to [delegate] unchanged.
 */
class ElectionPolicyWriterGuard(
    private val delegate: PolicyWriter,
) : PolicyWriter by delegate {
    override fun setPolicy(command: SetPolicyCommand): PolicyChange {
        if (command.key in ELECTION_KEYS && !ElectionWriteMarker.allows(command.key)) {
            throw PolicyRefused(
                PolicyRefusal.CONSTRAINT_REFUSED,
                command.key,
                "${command.key} is set through the election operations of tenancy-svc, not setPolicy",
            )
        }
        return delegate.setPolicy(command)
    }

    companion object {
        val ELECTION_KEYS: Set<VariationPointKey> =
            ElectionKind.entries.map { VariationPointKey(it.variationPoint) }.toSet()
    }
}

/** The four elections as config-resolver stores them (rulings INV-Q-9692, INV-Q-9700). */
class ConfigElectionPolicyStore(
    private val writer: PolicyWriter,
    private val history: PolicyHistory,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : ElectionPolicyStore {
    override fun history(
        entityRef: String,
        kind: ElectionKind,
    ): List<ElectionRecord> {
        val target = PolicyScope.entity(tenants.current(), entityRef).target(ScopeLevel.ENTITY)
        return history
            .versions(VariationPointKey(kind.variationPoint), target)
            .sortedBy { it.recordedAt }
            .map { version ->
                val option = version.selected.single()
                if (kind == ElectionKind.EXCHANGE_RATE_SOURCE) {
                    val record = mapper.readTree(checkNotNull(option.valueJson))
                    ElectionRecord(
                        record["source"].asString(),
                        record["basis"].asString(),
                        version.effectiveFrom,
                        version.setBy,
                        version.reason,
                    )
                } else {
                    ElectionRecord(option.key, null, version.effectiveFrom, version.setBy, version.reason)
                }
            }.groupBy { it.effectiveFrom }
            .map { (_, sameDate) -> sameDate.last() }
            .sortedBy { it.effectiveFrom }
    }

    @Suppress("LongParameterList") // one election with its actor
    override fun write(
        entityRef: String,
        groupRef: String,
        kind: ElectionKind,
        value: String,
        basis: String?,
        effectiveFrom: LocalDate,
        actor: String,
        roles: Set<String>,
        reason: String,
    ) {
        val key = VariationPointKey(kind.variationPoint)
        val selected =
            if (kind == ElectionKind.EXCHANGE_RATE_SOURCE) {
                SelectedOption(
                    ElectionKeys.APPROVED_SOURCE,
                    mapper.writeValueAsString(mapOf("source" to value, "basis" to checkNotNull(basis))),
                )
            } else {
                SelectedOption(value)
            }
        val command =
            SetPolicyCommand(
                key,
                PolicyScope.entity(tenants.current(), entityRef, group = groupRef),
                ScopeLevel.ENTITY,
                listOf(selected),
                effectiveFrom,
                reason,
                Actor(actor, roles),
                CorrelationId("corr-${UUID.randomUUID()}"),
                CausationId("cause-${UUID.randomUUID()}"),
            )
        try {
            ElectionWriteMarker.allowing(key) { writer.setPolicy(command) }
        } catch (refused: PolicyRefused) {
            throw ElectionWriteRefused(
                refused.refusal == PolicyRefusal.ROLE_NOT_ALLOWED,
                refused.message.orEmpty(),
                refused,
            )
        }
    }
}

/** The issuance mode entries of an entity's profile, dated by the day each takes effect (INV-Q-4993 B). */
class IssuanceProfileExtras(
    private val elections: ElectionPolicyStore,
) : ProfileExtras {
    override fun entries(entityRef: String): List<ProfileEntry> =
        elections
            .history(entityRef, ElectionKind.ISSUANCE_MODE)
            .map { ProfileEntry(ISSUANCE_MODE_ATTRIBUTE, it.value, it.effectiveFrom) }
}
