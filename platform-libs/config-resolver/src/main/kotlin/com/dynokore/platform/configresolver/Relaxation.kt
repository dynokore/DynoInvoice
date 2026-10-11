package com.dynokore.platform.configresolver

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** When an acknowledger is required (ruling INV-Q-3134). */
enum class AcknowledgerCondition {
    /** Always. */
    ALWAYS,

    /** Only where a delegation (an agent consent) covers the scope ([DelegationProbe], INV-Q-3136). */
    UNDER_DELEGATION,
}

/**
 * One role that must acknowledge a looser option (ruling INV-Q-3134; INV-REQ-1244 behaviour #2). Each
 * required role is met by a distinct principal (INV-Q-3125).
 *
 * @property role the role in both forms, or `null` when [roleByForm] names it per form.
 * @property roleByForm the role per deployment form (e.g. VP-66: security.officer in SaaS, the customer's
 *   tax.approver in private). A form it does not name has no acknowledger, so the option is refused there.
 * @property distinctFromSetter the acknowledging principal must not be the one who sets the value.
 */
data class Acknowledger(
    val role: String? = null,
    val condition: AcknowledgerCondition = AcknowledgerCondition.ALWAYS,
    val distinctFromSetter: Boolean = false,
    val roleByForm: Map<DeploymentForm, String> = emptyMap(),
) {
    init {
        require((role == null) != roleByForm.isEmpty()) { "an acknowledger names one role, or one role per form" }
        require((listOfNotNull(role) + roleByForm.values).all { it.isNotBlank() }) { "a role must not be blank" }
    }

    /** The role in [form], or `null` when the by-form list names none for it (refused, never skipped). */
    fun roleIn(form: DeploymentForm): String? = role ?: roleByForm[form]
}

/** Identifies one control relaxation: the acknowledgements given for it share it (ruling INV-Q-3138). */
@JvmInline
value class RelaxationId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** One recorded acknowledgement of a relaxation. */
data class RelaxationAcknowledgement(
    val id: UUID,
    val relaxation: RelaxationId,
    val role: String,
    val principal: String,
    val effectiveFrom: LocalDate,
    val acknowledgedAt: Instant,
)

/**
 * A relaxation after an acknowledgement: what has been given and which roles are still outstanding as
 * judged on the relaxation's effective date. The setter's own distinctness is checked only when the value is
 * set, because the setter is not known before.
 */
data class RelaxationStatus(
    val relaxation: RelaxationId,
    val key: VariationPointKey,
    val target: SettingTarget,
    val selected: List<SelectedOption>,
    val effectiveFrom: LocalDate,
    val acknowledgements: List<RelaxationAcknowledgement>,
    val outstandingRoles: List<String>,
) {
    val complete: Boolean get() = outstandingRoles.isEmpty()
}

/**
 * Whether a live delegation (an agent consent) covers [SettingTarget]'s entity on a date (ruling
 * INV-Q-3136). product-runtime binds it from the authority replica. `null` means the replica cannot answer;
 * like an unbound port, it counts as delegated (fail closed). A client-entity target is delegated without
 * asking.
 */
fun interface DelegationProbe {
    fun isDelegated(
        target: SettingTarget,
        onDate: LocalDate,
    ): Boolean?
}

/**
 * The earliest date a scope's first value of a row may take effect (ruling INV-Q-3140; INV-REQ-1243
 * behaviour #6): the entity's obligation start for an entity-scoped level, the earliest of its members'
 * for a group, and `null` when a document under the row has been transmitted for the scope. The owning
 * service binds it. `null`, or no binding, refuses a back-dated value. Tenant and installation targets
 * have no floor and are never asked.
 */
fun interface BackdatingFloor {
    fun earliest(
        target: SettingTarget,
        key: VariationPointKey,
    ): LocalDate?
}

/** The owning service's answer on the parts of a value only it can check (ruling INV-Q-3167). */
sealed interface ConstraintVerdict {
    data object Accept : ConstraintVerdict

    data class Refuse(
        val reason: String,
    ) : ConstraintVerdict
}

/**
 * Checks what config-resolver cannot see in a value (ruling INV-Q-3167): named bounds, references, worded
 * rules and cross-option rules. Bound by the owning service; asked only when the row's spec needs it. No
 * binding, an exception or `null` refuses the value (fail closed).
 */
fun interface ValueConstraints {
    fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict?
}
