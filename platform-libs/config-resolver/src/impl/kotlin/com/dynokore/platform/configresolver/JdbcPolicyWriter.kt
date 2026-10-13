package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * [PolicyWriter] on the connection of the caller's open transaction (INV-Q-3115: every write path).
 *
 * Checks, in order, before anything is written (INV-Q-2930): the key (unknown and retired keys throw,
 * planned is refused), the scope level, the setting role (for every source, INV-Q-3169), the reason, rules
 * config-resolver cannot evaluate (INV-Q-3173), the selection and its bounds (INV-Q-3167), the cross-scope
 * rule and installation bound (INV-T-3176, INV-Q-3202/3204/3206), the control relaxation
 * (INV-REQ-1244, INV-Q-3134/3136/3138/3142) and the effective date (INV-REQ-1243, INV-Q-2940/2977/3140).
 * Then it stores the version, writes the audit entry with the prior value and, where the service has an
 * outbox, `PolicyChanged` (INV-Q-3142), all in the caller's transaction (INV-REQ-1226).
 *
 * An installation value is written on the runtime login with the platform tenant and the setting actor bound, only
 * through the sys-owned [ConfigSchema.WRITE_INSTALLATION_SETTING] with its staged audit entry and `PolicyChanged`;
 * [audit] must then also be an [AuditStager] (fail closed otherwise). Its events are tenantless platform events
 * (INV-Q-2927 option D). A tenant or group
 * value's events carry the real tenant and `EntityRef.TENANT_WIDE` (INV-Q-3165).
 *
 * @param outbox `null` for a service without an outbox (control-plane-svc): the audit entry only.
 * @param delegation `null` means every scope counts as delegated (INV-Q-3136, fail closed).
 * @param backdating `null` refuses every back-dated value (INV-Q-3140).
 * @param constraints `null` refuses every value that needs the owning service's check (INV-Q-3167).
 */
class JdbcPolicyWriter(
    private val connections: CurrentConnection,
    private val catalogue: VariationPointCatalogue,
    private val form: DeploymentForm,
    schema: String,
    private val audit: AuditWriter,
    private val outbox: OutboxWriter?,
    private val clock: Clock,
    private val delegation: DelegationProbe? = null,
    private val backdating: BackdatingFloor? = null,
    private val constraints: ValueConstraints? = null,
) : PolicyWriter {
    private val store = JdbcSettingStore(schema)
    private val writes = JdbcSettingWrites(schema)
    private val validator = ValueValidator(form)
    private val mapper: JsonMapper =
        JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    private val resolver =
        PrecedenceResolver(catalogue, form) { key, ref, tenant, source, at ->
            store.find(connections.require(), key, ref, tenant, source, at)
        }
    private val bounds = ScopeBounds(catalogue, resolver::resolve)
    private val stagedRows = StagedRows(mapper)

    override fun setPolicy(command: SetPolicyCommand): PolicyChange {
        val definition = settable(command.key)
        val key = definition.key
        val target = targetOf(definition, command.scope, command.level)
        if (definition.setBy.none { it in command.actor.roles }) {
            refuse(key, PolicyRefusal.ROLE_NOT_ALLOWED, "setting needs one of ${definition.setBy.sorted()}")
        }
        val reason =
            command.reason?.takeIf { it.isNotBlank() } ?: refuse(key, PolicyRefusal.REASON_REQUIRED, "no reason")
        if (command.source == SettingSource.PROFILE &&
            (command.profileId.isNullOrBlank() || command.profileVersion.isNullOrBlank())
        ) {
            refuse(key, PolicyRefusal.PROFILE_INCOMPLETE, "a profile value names the profile and its version")
        }
        if (command.source == SettingSource.PROFILE && target.level == ScopeLevel.INSTALLATION) {
            // Profile values are tenant-and-below settings in config_setting (T26 slot scope, INV-Q-2979).
            refuse(
                key,
                PolicyRefusal.PROFILE_AT_INSTALLATION,
                "an operating profile applies to a tenant, not the installation",
            )
        }
        unsupported(definition)
        checkSelection(definition, target, command.selected, command.effectiveFrom)
        checkBounds(definition, command.scope, target, command.selected, command.effectiveFrom)
        val looser = looserOptions(definition, command.selected)
        val connection = connections.require()
        val relaxation =
            if (looser.isEmpty()) {
                // A relaxation backs exactly the looser value it acknowledges (INV-Q-3138).
                command.relaxation?.let {
                    refuse(
                        key,
                        PolicyRefusal.NOT_LOOSER,
                        "relaxation $it given for a value that is not looser",
                    )
                }
            } else {
                requireRelaxation(definition, target, command, looser)
            }
        checkEffectiveDate(definition, target, command.effectiveFrom)

        val prior =
            try {
                resolver.resolve(key, command.scope.narrowedTo(command.level), command.effectiveFrom)
            } catch (_: ElectionRequired) {
                null
            }
        val version =
            SettingVersion(
                id = UUID.randomUUID(),
                key = key,
                target = target,
                selected = command.selected,
                source = command.source,
                profileId = command.profileId.takeIf { command.source == SettingSource.PROFILE },
                profileVersion = command.profileVersion.takeIf { command.source == SettingSource.PROFILE },
                effectiveFrom = command.effectiveFrom,
                setBy = command.actor.principal,
                reason = reason,
                recordedAt = clock.instant(),
                relaxation = relaxation,
            )
        val context = contextOf(key, target, command.correlation, command.causation)
        val entry =
            AuditEntry(
                actor = command.actor.principal,
                action = SET_POLICY,
                scope = target.scopeText,
                reason = reason,
                priorStateJson = priorState(key, target, command.effectiveFrom, prior),
            )
        val event = OutboundEvent(POLICY_CHANGED, policyChanged(version))
        if (target.level == ScopeLevel.INSTALLATION) {
            return writeInstallation(connection, version, prior, entry, event, context)
        }
        writes.insert(connection, version)
        val auditEnvelope = audit.record(entry, context)
        val changed = outbox?.append(listOf(event), context)?.single()
        return PolicyChange(version, prior, auditEnvelope.id.toString(), changed?.id?.toString())
    }

    /**
     * An installation value is written only by [ConfigSchema.WRITE_INSTALLATION_SETTING] (INV-ADR-3968 item 6, owner
     * ruling INV-Q-3931): its audit entry and, where the service has an outbox, its `PolicyChanged` are staged
     * through their owning modules ([AuditStager], [OutboxWriter.stage]) and passed to the function, which checks
     * them against the value and `app.actor` and writes all three or none, stamping the id, actor and database time
     * itself (coordinator rulings INV-Q-4254 A, INV-Q-4316). The caller binds the platform tenant with the setting
     * actor (`PlatformTenantBinding.bindPlatform(actor)`, INV-Q-3997).
     */
    private fun writeInstallation(
        connection: Connection,
        version: SettingVersion,
        prior: ResolvedSetting?,
        entry: AuditEntry,
        event: OutboundEvent,
        context: EventContext,
    ): PolicyChange {
        val stager =
            checkNotNull(audit as? AuditStager) {
                "an installation value needs an AuditStager to stage its audit entry (INV-Q-4254), " +
                    "was ${audit.javaClass.name}"
            }
        val staged = stager.stage(entry, context)
        val changed = outbox?.stage(listOf(event), context)?.single()
        val stored =
            writes.writeInstallation(
                connection,
                version,
                stagedRows.json(staged.envelope),
                changed?.let { stagedRows.json(it.envelope, it.topic) },
            )
        val written = version.copy(id = stored.id, recordedAt = stored.recordedAt)
        return PolicyChange(written, prior, staged.envelope.id.toString(), changed?.envelope?.id?.toString())
    }

    override fun acknowledgeControlRelaxation(command: AcknowledgeRelaxationCommand): RelaxationStatus {
        val definition = settable(command.key)
        val key = definition.key
        val target = targetOf(definition, command.scope, command.level)
        unsupported(definition)
        checkSelection(definition, target, command.selected, command.effectiveFrom)
        checkBounds(definition, command.scope, target, command.selected, command.effectiveFrom)
        val looser = looserOptions(definition, command.selected)
        if (looser.isEmpty()) refuse(key, PolicyRefusal.NOT_LOOSER, "the value relaxes nothing")
        val required = requiredRoles(definition, looser, target, command.effectiveFrom).map { it.first }
        // Only the roles that apply here may acknowledge: an UNDER_DELEGATION role where no delegation applies
        // is not accountable for this relaxation (T26 slot default_source, INV-Q-3136).
        val allowed = required.toSet()
        if (command.asRole !in allowed || command.asRole !in command.actor.roles) {
            refuse(key, PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, "acknowledging needs one of ${allowed.sorted()}, held")
        }
        val tenant = checkNotNull(target.tenant)
        val connection = connections.require()
        val relaxation = command.relaxation ?: RelaxationId(UUID.randomUUID())
        val earlier =
            if (command.relaxation == null) {
                emptyList()
            } else {
                val rows = writes.acknowledgements(connection, tenant, relaxation)
                if (rows.isEmpty()) refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "no relaxation $relaxation")
                if (rows.any {
                        !matches(it, key, target, command.selected) ||
                            it.acknowledgement.effectiveFrom != command.effectiveFrom
                    }
                ) {
                    refuse(
                        key,
                        PolicyRefusal.RELAXATION_REQUIRED,
                        "relaxation $relaxation is for another value or date",
                    )
                }
                if (writes.consumed(connection, tenant, relaxation)) {
                    refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation is already used")
                }
                if (rows.any {
                        it.acknowledgement.role == command.asRole ||
                            it.acknowledgement.principal == command.actor.principal
                    }
                ) {
                    refuse(
                        key,
                        PolicyRefusal.ACKNOWLEDGER_DUPLICATE,
                        "role or principal already acknowledged $relaxation",
                    )
                }
                rows
            }
        val row =
            AcknowledgementRow(
                key = key,
                target = target,
                selected = command.selected,
                reason = command.reason?.takeIf { it.isNotBlank() },
                acknowledgement =
                    RelaxationAcknowledgement(
                        id = UUID.randomUUID(),
                        relaxation = relaxation,
                        role = command.asRole,
                        principal = command.actor.principal,
                        effectiveFrom = command.effectiveFrom,
                        acknowledgedAt = clock.instant(),
                    ),
            )
        writes.insertAcknowledgement(connection, row)
        val prior = mapper.createObjectNode()
        prior.put("relaxation", relaxation.toString())
        prior.putArray("acknowledgedRoles").apply { earlier.forEach { add(it.acknowledgement.role) } }
        audit.record(
            AuditEntry(
                actor = command.actor.principal,
                action = ACKNOWLEDGE_CONTROL_RELAXATION,
                scope = target.scopeText,
                reason = row.reason,
                priorStateJson = mapper.writeValueAsString(prior),
            ),
            contextOf(key, target, command.correlation, command.causation),
        )
        val given = (earlier + row).map { it.acknowledgement }
        return RelaxationStatus(
            relaxation = relaxation,
            key = key,
            target = target,
            selected = command.selected,
            effectiveFrom = command.effectiveFrom,
            acknowledgements = given,
            outstandingRoles = required.filter { role -> given.none { it.role == role } },
        )
    }

    override fun validate(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ) {
        val definition = settable(key)
        val target = targetOf(definition, scope, level)
        unsupported(definition)
        checkSelection(definition, target, selected, onDate)
        checkBounds(definition, scope, target, selected, onDate)
    }

    /** Unknown and retired keys throw; a planned key cannot be set (INV-Q-2930, INV-REQ-1251). */
    private fun settable(key: VariationPointKey): VariationPointDefinition {
        val definition = catalogue.require(key)
        when (definition.status) {
            VpStatus.RETIRED -> throw VariationPointRetired(key)
            VpStatus.PLANNED -> refuse(key, PolicyRefusal.PLANNED, "a planned variation point cannot be set")
            VpStatus.AVAILABLE -> Unit
        }
        return definition
    }

    /** The scope level comes first (INV-Q-2930, INV-AC-2227). */
    private fun targetOf(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        level: ScopeLevel,
    ): SettingTarget {
        if (level !in definition.scopeLevels) {
            refuse(definition.key, PolicyRefusal.SCOPE_NOT_ALLOWED, "the row allows ${definition.scopeLevels.sorted()}")
        }
        return scope.target(level)
    }

    /** Lifted only for what is implemented (INV-T-3176): anything [ScopeBounds] cannot evaluate is refused. */
    private fun unsupported(definition: VariationPointDefinition) {
        bounds.unsupported(definition)?.let { refuse(definition.key, PolicyRefusal.NOT_SUPPORTED, it) }
    }

    /**
     * A narrower value may not be looser than the broader value, nor pass the installation bound (INV-REQ-780
     * b1, INV-REQ-1248, INV-Q-3202/3204), on its effective date and on every later date a broader value or
     * the bounding row already takes effect. A tighter broader write is never refused: narrower values then
     * resolve capped (INV-Q-3206).
     */
    private fun checkBounds(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        target: SettingTarget,
        selected: List<SelectedOption>,
        effectiveFrom: LocalDate,
    ) {
        if (!bounds.applies(definition)) return
        val key = definition.key
        val connection = connections.require()
        val limiting =
            bounds.broaderLevels(definition, scope, target.level).map { key to scope.target(it) } +
                listOfNotNull(definition.installationParameter).map {
                    it to
                        PolicyScope.installation().target(ScopeLevel.INSTALLATION)
                }
        val later =
            limiting.flatMap { (row, at) -> writes.versions(connection, row, at).map { it.effectiveFrom } }.filter {
                it >
                    effectiveFrom
            }
        (listOf(effectiveFrom) + later.distinct().sorted()).forEach { date ->
            val limits =
                try {
                    bounds.limits(definition, scope, target.level, selected, date)
                } catch (unavailable: BoundUnavailable) {
                    refuse(key, PolicyRefusal.BOUND_UNAVAILABLE, unavailable.message.orEmpty())
                }
            limits.firstOrNull { it.violatedBy(it.field.value) }?.let {
                val side = if (it.ceiling) "above" else "below"
                val where = "'${it.field.option}' ${it.field.path.ifEmpty {
                    "value"
                }} ${it.field.value?.toPlainString()} is $side"
                if (it.crossScope) {
                    refuse(
                        key,
                        PolicyRefusal.LOOSER_THAN_BROADER,
                        "$where the ${it.level} value ${it.bound.toPlainString()} on $date",
                    )
                } else {
                    refuse(
                        key,
                        PolicyRefusal.BEYOND_INSTALLATION_BOUND,
                        "$where ${it.by} ${it.bound.toPlainString()} on $date",
                    )
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // any failure of the owning service's port refuses (INV-Q-3167)
    private fun checkSelection(
        definition: VariationPointDefinition,
        target: SettingTarget,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ) {
        if (!validator.check(definition, selected)) return
        val verdict =
            try {
                constraints?.check(target, definition.key, selected, onDate)
            } catch (failure: RuntimeException) {
                refuse(
                    definition.key,
                    PolicyRefusal.CONSTRAINT_REFUSED,
                    "the owning service's check failed: ${failure.javaClass.simpleName}",
                )
            }
        when (verdict) {
            ConstraintVerdict.Accept -> {}

            is ConstraintVerdict.Refuse -> {
                refuse(definition.key, PolicyRefusal.CONSTRAINT_REFUSED, verdict.reason)
            }

            null -> {
                refuse(
                    definition.key,
                    PolicyRefusal.CONSTRAINT_REFUSED,
                    "the owning service did not check the value",
                )
            }
        }
    }

    /** The LOOSER options of the selection; the validator has already refused them on a row that offers none. */
    private fun looserOptions(
        definition: VariationPointDefinition,
        selected: List<SelectedOption>,
    ): List<OptionSpec> =
        selected.mapNotNull { definition.option(it.key) }.filter { it.strictness == Strictness.LOOSER }

    /**
     * The roles that must acknowledge, each with its distinct-from-setter flag (INV-Q-3134, INV-Q-3136). An
     * option with no acknowledger, a by-form acknowledger naming no role in this form, or only
     * UNDER_DELEGATION acknowledgers where no delegation applies leaves nobody accountable: refused.
     */
    private fun requiredRoles(
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
    private fun requireRelaxation(
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
        val connection = connections.require()
        val rows = writes.acknowledgements(connection, tenant, relaxation)
        if (rows.isEmpty() || rows.any { !matches(it, key, target, command.selected) }) {
            refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation does not acknowledge this value")
        }
        if (rows.any { it.acknowledgement.effectiveFrom > command.effectiveFrom }) {
            refuse(key, PolicyRefusal.RELAXATION_REQUIRED, "relaxation $relaxation takes effect after the value")
        }
        if (writes.consumed(connection, tenant, relaxation)) {
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

    /** Never retroactive, except a scope's first value back to its floor (INV-Q-2940, INV-Q-2977, INV-Q-3140). */
    private fun checkEffectiveDate(
        definition: VariationPointDefinition,
        target: SettingTarget,
        effectiveFrom: LocalDate,
    ) {
        val today = BusinessDate.today(clock)
        if (effectiveFrom >= today) return
        val key = definition.key
        if (target.level == ScopeLevel.TENANT || target.level == ScopeLevel.INSTALLATION) {
            refuse(key, PolicyRefusal.BACKDATING_REFUSED, "a ${target.level} value takes effect $today or later")
        }
        if (writes.hasAny(connections.require(), key, target)) {
            refuse(key, PolicyRefusal.BACKDATING_REFUSED, "only a scope's first value may be back-dated")
        }
        val floor =
            backdating?.let { port -> runCatching { port.earliest(target, key) }.getOrNull() }
                ?: refuse(key, PolicyRefusal.BACKDATING_REFUSED, "no back-dating floor for ${target.scopeText}")
        if (effectiveFrom < floor) {
            refuse(key, PolicyRefusal.BACKDATING_REFUSED, "the earliest allowed date is $floor")
        }
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

    private fun contextOf(
        key: VariationPointKey,
        target: SettingTarget,
        correlation: CorrelationId,
        causation: CausationId,
    ): EventContext {
        val tenant = target.tenant
        val entityId = target.entity
        val (eventTenant, entity) =
            when {
                tenant == null -> TenantId.PLATFORM to EntityRef.PLATFORM
                entityId != null -> tenant to EntityRef(entityId)
                else -> tenant to EntityRef.TENANT_WIDE
            }
        return EventContext(
            tenant = eventTenant,
            entity = entity,
            docUuid = null,
            transactionDate = null,
            aggregateKey = "config:$key:${target.scopeText}",
            aggregateVersion = null,
            correlation = correlation,
            causation = causation,
        )
    }

    private fun valueNode(selected: List<SelectedOption>) =
        mapper.createArrayNode().apply {
            selected.forEach { option ->
                val node = addObject()
                node.put("key", option.key)
                option.valueJson?.let { node.set("value", mapper.readTree(it)) }
            }
        }

    /** The prior value at the target on the effective date, as the audit entry's prior state (INV-AC-2229). */
    private fun priorState(
        key: VariationPointKey,
        target: SettingTarget,
        effectiveFrom: LocalDate,
        prior: ResolvedSetting?,
    ): String {
        val node: ObjectNode = mapper.createObjectNode()
        node.put("vpId", key.value)
        node.put("scope", target.scopeText)
        node.put("effectiveDate", effectiveFrom.toString())
        if (prior == null) {
            node.putNull("value")
            node.putNull("source")
        } else {
            node.set("value", valueNode(prior.selected))
            node.put("source", sourceName(prior.source))
            when (val source = prior.source) {
                is PolicySource.Override -> {
                    node.put("sourceScope", source.scopeId).put("settingId", source.settingId.toString())
                }

                is PolicySource.Profile -> {
                    node
                        .put("sourceScope", source.scopeId)
                        .put("settingId", source.settingId.toString())
                        .put("profileId", source.profileId)
                        .put("profileVersion", source.profileVersion)
                }

                PolicySource.CatalogueDefault -> {}
            }
        }
        return mapper.writeValueAsString(node)
    }

    /** The `PolicyChanged` payload (platform-contracts policy-changed-payload.schema.json, INV-API-3306). */
    private fun policyChanged(version: SettingVersion): String {
        val node = mapper.createObjectNode()
        node.put("scope", version.target.scopeText)
        node.put("vpId", version.key.value)
        node.set("value", valueNode(version.selected))
        node.put("effectiveDate", version.effectiveFrom.toString())
        node.put("source", version.source.name)
        version.relaxation?.let { node.put("relaxationAcknowledgementReference", it.toString()) }
        // Exactly the INV-API-3306 data model: the setting id, profile id and profile version stay on the
        // setting record, where INV-AC-2092 reads them, not on the wire (owner ruling INV-Q-2979, INV-Q-3714).
        return mapper.writeValueAsString(node)
    }

    private fun sourceName(source: PolicySource) =
        when (source) {
            is PolicySource.Override -> "OVERRIDE"
            is PolicySource.Profile -> "PROFILE"
            PolicySource.CatalogueDefault -> "DEFAULT"
        }

    companion object {
        /** The platform event type (API-40), schema owned by `service-runtime`. */
        const val POLICY_CHANGED = ConfigSchema.POLICY_CHANGED
        const val SET_POLICY = ConfigSchema.SET_POLICY
        const val ACKNOWLEDGE_CONTROL_RELAXATION = "acknowledgeControlRelaxation"
    }
}
