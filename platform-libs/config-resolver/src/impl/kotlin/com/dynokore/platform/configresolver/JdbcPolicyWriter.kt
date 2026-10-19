package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.jooq.DSLContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * [PolicyWriter] through jOOQ's DSL on the DSLContext of tenancy-jooq's factory, in the caller's open transaction
 * (INV-Q-3115: every write path). The DSLContext runs on the TransactionRunner whose TenantAwareDataSource holds the
 * lane
 * gate (INV-Q-4502). [audit] and [delegation] are public read-only properties (INV-Q-5117).
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
@Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
class JdbcPolicyWriter(
    dsl: DSLContext,
    catalogue: VariationPointCatalogue,
    form: DeploymentForm,
    schema: String,
    val audit: AuditWriter,
    private val outbox: OutboxWriter?,
    private val clock: Clock,
    val delegation: DelegationProbe? = null,
    backdating: BackdatingFloor? = null,
    constraints: ValueConstraints? = null,
) : PolicyWriter {
    private val store = JdbcSettingStore(dsl, schema)
    private val writes = JdbcSettingWrites(dsl, schema)
    private val mapper: JsonMapper =
        JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    private val resolver =
        PrecedenceResolver(catalogue, form) { key, ref, tenant, source, at ->
            store.find(key, ref, tenant, source, at)
        }
    private val stagedRows = StagedRows(mapper)
    private val checks =
        PolicyChecks(catalogue, ValueValidator(form), ScopeBounds(catalogue, resolver::resolve), writes, constraints)
    private val relaxations = RelaxationRules(form, delegation, writes, mapper)
    private val effectiveDates = EffectiveDateCheck(writes, backdating, clock)
    private val payloads = PolicyPayloads(mapper)

    override fun setPolicy(command: SetPolicyCommand): PolicyChange {
        val definition = checks.settable(command.key)
        val key = definition.key
        val target = checks.targetOf(definition, command.scope, command.level)
        val reason = checkSetter(definition, target, command)
        checks.selectionAndBounds(definition, command.scope, target, command.selected, command.effectiveFrom)
        val looser = relaxations.looserOptions(definition, command.selected)
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
                relaxations.requireRelaxation(definition, target, command, looser)
            }
        effectiveDates.check(definition, target, command.effectiveFrom)

        val prior = priorOf(command, key)
        val version = versionOf(command, key, target, reason, relaxation)
        val context = payloads.contextOf(key, target, command.correlation, command.causation)
        val entry =
            AuditEntry(
                actor = command.actor.principal,
                action = SET_POLICY,
                scope = target.scopeText,
                reason = reason,
                priorStateJson = payloads.priorState(key, target, command.effectiveFrom, prior),
            )
        val event = OutboundEvent(POLICY_CHANGED, payloads.policyChanged(version))
        if (target.level == ScopeLevel.INSTALLATION) {
            return writeInstallation(version, prior, entry, event, context)
        }
        writes.insert(version)
        val auditEnvelope = audit.record(entry, context)
        val changed = outbox?.append(listOf(event), context)?.single()
        return PolicyChange(version, prior, auditEnvelope.id.toString(), changed?.id?.toString())
    }

    private fun priorOf(
        command: SetPolicyCommand,
        key: VariationPointKey,
    ): ResolvedSetting? =
        try {
            resolver.resolve(key, command.scope.narrowedTo(command.level), command.effectiveFrom)
        } catch (_: ElectionRequired) {
            null
        }

    /** The setter's role, the reason and the profile fields; returns the reason (INV-Q-3169). */
    private fun checkSetter(
        definition: VariationPointDefinition,
        target: SettingTarget,
        command: SetPolicyCommand,
    ): String {
        val key = definition.key
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
        return reason
    }

    private fun versionOf(
        command: SetPolicyCommand,
        key: VariationPointKey,
        target: SettingTarget,
        reason: String,
        relaxation: RelaxationId?,
    ) = SettingVersion(
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

    /**
     * An installation value is written only by [ConfigSchema.WRITE_INSTALLATION_SETTING] (INV-ADR-3968 item 6, owner
     * ruling INV-Q-3931): its audit entry and, where the service has an outbox, its `PolicyChanged` are staged
     * through their owning modules ([AuditStager], [OutboxWriter.stage]) and passed to the function, which checks
     * them against the value and the bound actor and writes all three or none, stamping the id, actor and database time
     * itself (coordinator rulings INV-Q-4254 A, INV-Q-4316). The caller binds the platform tenant with the setting
     * actor (`PlatformTenantBinding.bindPlatform(actor)`, INV-Q-3997).
     */
    private fun writeInstallation(
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
                version,
                stagedRows.json(staged.envelope),
                changed?.let { stagedRows.json(it.envelope, it.topic) },
            )
        val written = version.copy(id = stored.id, recordedAt = stored.recordedAt)
        return PolicyChange(written, prior, staged.envelope.id.toString(), changed?.envelope?.id?.toString())
    }

    override fun acknowledgeControlRelaxation(command: AcknowledgeRelaxationCommand): RelaxationStatus {
        val definition = checks.settable(command.key)
        val key = definition.key
        val target = checks.targetOf(definition, command.scope, command.level)
        checks.selectionAndBounds(definition, command.scope, target, command.selected, command.effectiveFrom)
        val looser = relaxations.looserOptions(definition, command.selected)
        if (looser.isEmpty()) refuse(key, PolicyRefusal.NOT_LOOSER, "the value relaxes nothing")
        val required = relaxations.requiredRoles(definition, looser, target, command.effectiveFrom).map { it.first }
        // Only the roles that apply here may acknowledge: an UNDER_DELEGATION role where no delegation applies
        // is not accountable for this relaxation (T26 slot default_source, INV-Q-3136).
        val allowed = required.toSet()
        if (command.asRole !in allowed || command.asRole !in command.actor.roles) {
            refuse(key, PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, "acknowledging needs one of ${allowed.sorted()}, held")
        }
        val tenant = checkNotNull(target.tenant)
        val relaxation = command.relaxation ?: RelaxationId(UUID.randomUUID())
        val earlier = relaxations.earlierAcknowledgements(command, relaxation, target, tenant)
        val row = acknowledgementRow(command, target, relaxation)
        writes.insertAcknowledgement(row)
        audit.record(
            AuditEntry(
                actor = command.actor.principal,
                action = ACKNOWLEDGE_CONTROL_RELAXATION,
                scope = target.scopeText,
                reason = row.reason,
                priorStateJson = payloads.acknowledgedState(relaxation, earlier),
            ),
            payloads.contextOf(key, target, command.correlation, command.causation),
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

    private fun acknowledgementRow(
        command: AcknowledgeRelaxationCommand,
        target: SettingTarget,
        relaxation: RelaxationId,
    ) = AcknowledgementRow(
        key = command.key,
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

    override fun validate(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ) {
        val definition = checks.settable(key)
        val target = checks.targetOf(definition, scope, level)
        checks.selectionAndBounds(definition, scope, target, selected, onDate)
    }

    companion object {
        /** The platform event type (API-40), schema owned by `service-runtime`. */
        const val POLICY_CHANGED = ConfigSchema.POLICY_CHANGED
        const val SET_POLICY = ConfigSchema.SET_POLICY
        const val ACKNOWLEDGE_CONTROL_RELAXATION = "acknowledgeControlRelaxation"
    }
}
