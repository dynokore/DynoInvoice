package com.dynokore.gst.tenancy.application.lifecycle

import com.dynokore.gst.tenancy.application.certification.CertificationStore
import com.dynokore.gst.tenancy.application.confirmation.ConfirmationVerdict
import com.dynokore.gst.tenancy.application.confirmation.SecondConfirmation
import com.dynokore.gst.tenancy.application.election.ElectionGate
import com.dynokore.gst.tenancy.application.election.ElectionGateDecision
import com.dynokore.gst.tenancy.application.offboarding.OffboardingCodes
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.application.period.PeriodGeneration
import com.dynokore.gst.tenancy.application.period.PeriodOutcome
import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.application.tenant.TenantStates
import com.dynokore.gst.tenancy.domain.GoLiveFacts
import com.dynokore.gst.tenancy.domain.GoLiveGate
import com.dynokore.gst.tenancy.domain.LifecycleAttributes
import com.dynokore.gst.tenancy.domain.LifecycleRules
import com.dynokore.gst.tenancy.domain.LifecycleState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.TenantState
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** The trigger classes of the entity that are neither mapped, waived nor covered by another solution (INV-REQ-1322). */
fun interface TriggerCoverage {
    /** The uncovered classes (empty when complete); throws [UnavailableInput] when coverage cannot be read. */
    fun uncovered(entityRef: String): List<String>
}

/** The binding of the coverage port while no producer exists (before Wave 3, INV-Q-4900): go-live is refused. */
class UnboundTriggerCoverage : TriggerCoverage {
    override fun uncovered(entityRef: String): List<String> = throw UnavailableInput(GoLiveGate.COVERAGE_UNKNOWN)
}

/** Writes the stored lifecycle state of an entity in the caller's transaction (adapter: the boundary slice). */
fun interface EntityLifecycleStore {
    fun setLifecycleState(
        entityRef: String,
        state: LifecycleState,
        structureVersion: Long,
    )
}

/** Records the acknowledgement that overrides the unsubmitted-document block, in the caller's transaction. */
fun interface UnsubmittedOverrideLog {
    fun record(
        entityRef: String,
        override: UnsubmittedOverride,
        on: LocalDate,
    )
}

/** The final-period step of deregistration; production binds `PeriodGeneration::finalPeriod`. */
fun interface FinalPeriod {
    fun create(
        entityRef: String,
        registrationEndDate: LocalDate,
    ): PeriodOutcome

    companion object {
        fun of(generation: PeriodGeneration): FinalPeriod = FinalPeriod(generation::finalPeriod)
    }
}

/** The result of goLive, suspendEntity and deregisterEntity. */
sealed interface LifecycleOutcome {
    /** 200: the entity is in [state]; [repeated] when nothing was written (a repeated goLive). */
    data class Done(
        val entityRef: String,
        val state: LifecycleState,
        val repeated: Boolean = false,
    ) : LifecycleOutcome

    /** 400 SRT-001: a missing property; nothing is written. */
    data class Invalid(
        val reason: String,
    ) : LifecycleOutcome

    /** 403: the second confirmation failed; nothing is written. */
    data class Forbidden(
        val reason: String,
    ) : LifecycleOutcome

    data object NotFound : LifecycleOutcome

    /** 409 [code] (TNC-008); nothing is written. */
    data class Conflict(
        val code: String,
        val reason: String,
    ) : LifecycleOutcome

    /** 422 POL-014; [detail] names every unmet item or the unavailable input; nothing is written. */
    data class Refused(
        val detail: String,
    ) : LifecycleOutcome
}

/** The shared writer of a lifecycle change: store, version, one EntityProfileChanged in the same transaction. */
internal class LifecycleWriter(
    private val structure: StructureStore,
    private val lifecycle: EntityLifecycleStore,
    private val statuses: EntityStatusStore,
    private val extras: ProfileExtras,
    private val publisher: EntityProfileChangePublisher,
    private val tenant: () -> TenantId,
) {
    fun write(
        entity: EntityRecord,
        to: LifecycleState,
        on: LocalDate,
        registrationEnd: LocalDate? = null,
    ) {
        val version = structure.nextStructureVersion()
        structure.touchEntity(entity.ref, version)
        lifecycle.setLifecycleState(entity.ref, to, version)
        val changes =
            listOfNotNull(
                registrationEnd?.let {
                    ChangedAttribute(LifecycleAttributes.REGISTRATION_END_DATE, null, LifecycleRules.isoDate(it))
                },
                ChangedAttribute(LifecycleAttributes.LIFECYCLE_STATE, entity.lifecycleState, to.name),
            )
        val added = changes.map { ProfileEntry(it.attribute, it.newValue, on) }
        val profile =
            (
                statuses.history(entity.id).map { ProfileEntry(it.attribute, it.value, it.effectiveFrom) } +
                    extras.entries(entity.ref) + added
            ).sortedBy { it.effectiveDate }
        val updated = entity.copy(structureVersion = version, lifecycleState = to.name)
        publisher.publish(EntityProfileChange(tenant().value, entity.ref, changes, on, entityNode(updated, profile)))
    }
}

/**
 * goLive (INV-REQ-987, INV-REQ-963, INV-REQ-875): refused 403 unless the confirmer is another person who holds
 * `entity-config.confirm`; a repeat on a live entity is a no-op; refused 422 POL-014 naming every unmet item of
 * trigger coverage, sandbox certification (latest record) and elections.
 */
@Suppress("LongParameterList")
class GoLive internal constructor(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val confirmation: SecondConfirmation,
    private val coverage: TriggerCoverage,
    private val certifications: CertificationStore,
    private val elections: ElectionGate,
    private val writer: LifecycleWriter,
    private val today: () -> LocalDate,
    private val tenantStates: TenantStates,
) {
    @Suppress("LongParameterList")
    constructor(
        tx: TransactionRunner,
        structure: StructureStore,
        confirmation: SecondConfirmation,
        coverage: TriggerCoverage,
        certifications: CertificationStore,
        elections: ElectionGate,
        lifecycle: EntityLifecycleStore,
        statuses: EntityStatusStore,
        extras: ProfileExtras,
        publisher: EntityProfileChangePublisher,
        tenant: () -> TenantId,
        today: () -> LocalDate,
        tenantStates: TenantStates,
    ) : this(
        tx,
        structure,
        confirmation,
        coverage,
        certifications,
        elections,
        LifecycleWriter(structure, lifecycle, statuses, extras, publisher, tenant),
        today,
        tenantStates,
    )

    fun handle(
        caller: String,
        entityRef: String,
        secondConfirmationBy: String?,
    ): LifecycleOutcome {
        if (secondConfirmationBy.isNullOrEmpty()) return LifecycleOutcome.Invalid("secondConfirmationBy is required")
        return tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction LifecycleOutcome.NotFound
            when (confirmation.verdict(caller, secondConfirmationBy, entity.ref)) {
                ConfirmationVerdict.SAME_PERSON -> {
                    LifecycleOutcome.Forbidden("the confirmer is the caller")
                }

                ConfirmationVerdict.NOT_PERMITTED -> {
                    LifecycleOutcome.Forbidden("the confirmer does not hold entity-config.confirm or cannot be proven")
                }

                ConfirmationVerdict.ACCEPTED -> {
                    if (LifecycleRules.goLiveIsRepeat(entity.lifecycleState)) {
                        LifecycleOutcome.Done(entity.ref, LifecycleState.LIVE, repeated = true)
                    } else {
                        gateAndWrite(entity)
                    }
                }
            }
        }
    }

    private fun gateAndWrite(entity: EntityRecord): LifecycleOutcome {
        val on = today()
        val uncovered = runCatching { coverage.uncovered(entity.ref) }
        val decision = elections.check(entity.ref, on)
        return if (decision == ElectionGateDecision.EntityNotFound) {
            LifecycleOutcome.NotFound
        } else {
            val unmet = GoLiveGate.unmet(facts(entity, uncovered, decision))
            if (unmet.isEmpty()) {
                writer.write(entity, LifecycleState.LIVE, on)
                LifecycleOutcome.Done(entity.ref, LifecycleState.LIVE)
            } else {
                LifecycleOutcome.Refused(unmet.joinToString("; "))
            }
        }
    }

    private fun facts(
        entity: EntityRecord,
        uncovered: Result<List<String>>,
        decision: ElectionGateDecision,
    ): GoLiveFacts {
        val latest = certifications.latest(entity.ref)
        val outstanding = (decision as? ElectionGateDecision.Refused)?.outstanding.orEmpty().map { it.name }
        val unavailable = uncovered.exceptionOrNull()
        if (unavailable != null && unavailable !is UnavailableInput) throw unavailable
        return GoLiveFacts(
            uncovered.getOrDefault(emptyList()),
            latest?.outcome,
            latest?.failedScenarios.orEmpty(),
            outstanding,
            coverageUnknown = unavailable != null,
            tenantOpen = tenantStates.current() == TenantState.OPEN.name,
        )
    }
}

/** suspendEntity (INV-REQ-963 b2): sets SUSPENDED; retracts nothing already sent and touches no submission. */
class SuspendEntity internal constructor(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val writer: LifecycleWriter,
    private val today: () -> LocalDate,
) {
    @Suppress("LongParameterList")
    constructor(
        tx: TransactionRunner,
        structure: StructureStore,
        lifecycle: EntityLifecycleStore,
        statuses: EntityStatusStore,
        extras: ProfileExtras,
        publisher: EntityProfileChangePublisher,
        tenant: () -> TenantId,
        today: () -> LocalDate,
    ) : this(tx, structure, LifecycleWriter(structure, lifecycle, statuses, extras, publisher, tenant), today)

    fun handle(entityRef: String): LifecycleOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction LifecycleOutcome.NotFound
            writer.write(entity, LifecycleState.SUSPENDED, today())
            LifecycleOutcome.Done(entity.ref, LifecycleState.SUSPENDED)
        }
}

/**
 * deregisterEntity (INV-REQ-956 b1, INV-Q-9731 A, INV-Q-16387, INV-Q-16399): blocked 409 TNC-008 while an in-scope
 * document is unsubmitted unless the acknowledgement is recorded; an unreadable count or a refused final period is
 * 422 POL-014. One EntityProfileChanged carries registrationEndDate first and lifecycleState second.
 */
@Suppress("LongParameterList")
class DeregisterEntity internal constructor(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val documents: UnsubmittedDocuments,
    private val finalPeriod: FinalPeriod,
    private val overrides: UnsubmittedOverrideLog,
    private val writer: LifecycleWriter,
    private val today: () -> LocalDate,
) {
    @Suppress("LongParameterList")
    constructor(
        tx: TransactionRunner,
        structure: StructureStore,
        documents: UnsubmittedDocuments,
        finalPeriod: FinalPeriod,
        overrides: UnsubmittedOverrideLog,
        lifecycle: EntityLifecycleStore,
        statuses: EntityStatusStore,
        extras: ProfileExtras,
        publisher: EntityProfileChangePublisher,
        tenant: () -> TenantId,
        today: () -> LocalDate,
    ) : this(
        tx,
        structure,
        documents,
        finalPeriod,
        overrides,
        LifecycleWriter(structure, lifecycle, statuses, extras, publisher, tenant),
        today,
    )

    fun handle(
        entityRef: String,
        registrationEndDate: LocalDate?,
        unsubmittedOverride: UnsubmittedOverride?,
    ): LifecycleOutcome {
        if (registrationEndDate == null) return LifecycleOutcome.Invalid("registrationEndDate is required")
        return tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction LifecycleOutcome.NotFound
            val unsubmitted =
                try {
                    documents.count(OffboardingScope.ENTITY, entity.ref)
                } catch (e: UnavailableInput) {
                    return@inTransaction LifecycleOutcome.Refused(e.input)
                }
            if (LifecycleRules.deregistrationBlocked(unsubmitted, unsubmittedOverride)) {
                return@inTransaction LifecycleOutcome.Conflict(
                    OffboardingCodes.UNSUBMITTED_DOCUMENT,
                    "$unsubmitted document(s) unsubmitted",
                )
            }
            when (val period = finalPeriod.create(entity.ref, registrationEndDate)) {
                is PeriodOutcome.Refused -> {
                    LifecycleOutcome.Refused(period.reason)
                }

                is PeriodOutcome.Invalid -> {
                    LifecycleOutcome.Invalid(period.reason)
                }

                PeriodOutcome.NotFound -> {
                    LifecycleOutcome.NotFound
                }

                is PeriodOutcome.Done -> {
                    if (unsubmitted > 0 && unsubmittedOverride != null) {
                        overrides.record(entity.ref, unsubmittedOverride, today())
                    }
                    writer.write(entity, LifecycleState.DEREGISTERED, today(), registrationEndDate)
                    LifecycleOutcome.Done(entity.ref, LifecycleState.DEREGISTERED)
                }
            }
        }
    }
}
