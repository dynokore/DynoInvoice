package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/** The control-relaxation rules of [JdbcPolicyWriter] (INV-REQ-1244, INV-Q-3134/3136/3138/3142). */
internal class RelaxationRules(
    private val form: DeploymentForm,
    private val delegation: DelegationProbe?,
    private val writes: JdbcSettingWrites,
    private val mapper: JsonMapper,
) {
    /** The LOOSER options of the selection; the validator has already refused them on a row that offers none. */
    fun looserOptions(
        definition: VariationPointDefinition,
        selected: List<SelectedOption>,
    ): List<OptionSpec> =
        selected.mapNotNull { definition.option(it.key) }.filter { it.strictness == Strictness.LOOSER }

    /**
     * The roles that must acknowledge, each with its distinct-from-setter flag (INV-Q-3134, INV-Q-3136). An
     * option with no acknowledger, a by-form acknowledger naming no role in this form, or only
     * UNDER_DELEGATION acknowledgers where no delegation applies leaves nobody accountable: refused.
     */
    fun requiredRoles(
        definition: VariationPointDefinition,
        looser: List<OptionSpec>,
        target: SettingTarget,
        onDate: LocalDate,
    ): List<Pair<String, Boolean>> {
        val key = definition.key
        if (target.level == ScopeLevel.INSTALLATION) {
            refuse(key, PolicyRefusal.LOOSER_AT_INSTALLATION, "a looser option at installation scope is not in 1.1.0")
        }
        val delegated by lazy { isDelegated(target, onDate) }
        val required = linkedMapOf<String, Boolean>()
        looser.forEach { option ->
            if (option.acknowledgers.isEmpty()) {
                refuse(key, PolicyRefusal.NO_ACCOUNTABLE_ROLE, "option '${option.key}' names no acknowledger")
            }
            val applying =
                option.acknowledgers.filter {
                    it.condition == AcknowledgerCondition.ALWAYS || delegated
                }
            if (applying.isEmpty()) {
                refuse(
                    key,
                    PolicyRefusal.NO_ACCOUNTABLE_ROLE,
                    "option '${option.key}' has no acknowledger for this scope",
                )
            }
            applying.forEach {
                val role =
                    it.roleIn(form)
                        ?: refuse(
                            key,
                            PolicyRefusal.NO_ACCOUNTABLE_ROLE,
                            "option '${option.key}' names no acknowledger in $form",
                        )
                required[role] = (required[role] ?: false) || it.distinctFromSetter
            }
        }
        return required.map { it.key to it.value }
    }

    /** A client entity is always delegated; otherwise the probe, and no answer counts as delegated (INV-Q-3136). */
    private fun isDelegated(
        target: SettingTarget,
        onDate: LocalDate,
    ): Boolean =
        target.level == ScopeLevel.CLIENT_ENTITY ||
            (delegation?.let { probe -> runCatching { probe.isDelegated(target, onDate) }.getOrNull() } ?: true)

    /** A complete, matching, unused relaxation, effective on or before the value (INV-AC-205, INV-Q-3138). */
    fun requireRelaxation(
        definition: VariationPointDefinition,
        target: SettingTarget,
        command: SetPolicyCommand,
        looser: List<OptionSpec>,
    ): RelaxationId {
        val key = definition.key
        val required = requiredRoles(definition, looser, target, command.effectiveFrom)
        val relaxation =
            command.relaxation
                ?: refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "a looser option needs ${required.map { it.first }}")
        val tenant = checkNotNull(target.tenant)
        val rows = writes.acknowledgements(tenant, relaxation)
        if (rows.isEmpty() || rows.any { !matches(it, key, target, command.selected) }) {
            refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation does not acknowledge this value")
        }
        if (rows.any { it.acknowledgement.effectiveFrom > command.effectiveFrom }) {
            refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation takes effect after the value")
        }
        if (writes.consumed(tenant, relaxation)) {
            refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation is already used")
        }
        required.forEach { (role, distinct) ->
            val given =
                rows.firstOrNull { it.acknowledgement.role == role }
                    ?: refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation lacks $role")
            if (distinct && given.acknowledgement.principal == command.actor.principal) {
                refuse(
                    key,
                    PolicyRefusal.RELAXATION_REQUIRED,
                    "$role must be acknowledged by someone other than the setter",
                )
            }
        }
        return relaxation
    }

    /**
     * The acknowledgements already given to the [relaxation] a further acknowledger joins: empty when the command
     * starts a new relaxation, otherwise the rows once they are known to be for this value and date, unused, and
     * not from the same role or principal.
     */
    fun earlierAcknowledgements(
        command: AcknowledgeRelaxationCommand,
        relaxation: RelaxationId,
        target: SettingTarget,
        tenant: TenantId,
    ): List<AcknowledgementRow> {
        if (command.relaxation == null) return emptyList()
        val key = command.key
        val rows = writes.acknowledgements(tenant, relaxation)
        if (rows.isEmpty()) refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "no relaxation $relaxation")
        if (rows.any {
                !matches(it, key, target, command.selected) || it.acknowledgement.effectiveFrom != command.effectiveFrom
            }
        ) {
            refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation is for another value or date")
        }
        if (writes.consumed(tenant, relaxation)) {
            refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation is already used")
        }
        if (rows.any {
                it.acknowledgement.role == command.asRole || it.acknowledgement.principal == command.actor.principal
            }
        ) {
            refuse(
                key,
                PolicyRefusal.ACKNOWLEDGER_DUPLICATE,
                "role or principal already acknowledged $relaxation",
            )
        }
        return rows
    }

    private fun matches(
        row: AcknowledgementRow,
        key: VariationPointKey,
        target: SettingTarget,
        selected: List<SelectedOption>,
    ): Boolean = row.key == key && row.target == target && sameSelection(row.selected, selected)

    /** Same option keys and the same typed values, numbers compared by value (INV-Q-3138). */
    private fun sameSelection(
        a: List<SelectedOption>,
        b: List<SelectedOption>,
    ): Boolean {
        if (a.map { it.key }.toSet() != b.map { it.key }.toSet() || a.size != b.size) return false
        val bByKey = b.associateBy { it.key }
        return a.all { left ->
            val right = bByKey.getValue(left.key)
            when {
                left.valueJson == null || right.valueJson == null -> left.valueJson == right.valueJson
                else -> sameJson(mapper.readTree(left.valueJson), mapper.readTree(right.valueJson))
            }
        }
    }

    private fun sameJson(
        a: JsonNode,
        b: JsonNode,
    ): Boolean =
        when {
            a.isNumber && b.isNumber -> {
                a.decimalValue().compareTo(b.decimalValue()) == 0
            }

            a.isObject && b.isObject -> {
                a.propertyNames().toSet() == b.propertyNames().toSet() &&
                    a.propertyNames().all { sameJson(a.get(it), b.get(it)) }
            }

            a.isArray && b.isArray -> {
                a.size() == b.size() && (0 until a.size()).all { sameJson(a.get(it), b.get(it)) }
            }

            else -> {
                a == b
            }
        }
}
