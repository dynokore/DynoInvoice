package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.LocalDate
import java.util.UUID

/** A stored setting as the resolver needs it. Profile rows carry the profile id and version (INV-Q-2979). */
data class StoredSetting(
    val id: UUID,
    val selected: List<SelectedOption>,
    val source: SettingSource,
    val profileId: String?,
    val profileVersion: String?,
    val effectiveFrom: LocalDate,
) {
    init {
        require(selected.isNotEmpty()) { "a stored setting selects at least one option" }
        require((source == SettingSource.PROFILE) == (profileId != null && profileVersion != null)) {
            "a profile setting, and only a profile setting, names the profile and its version"
        }
    }
}

/**
 * Finds the stored setting of [source] in force at [at] for one scope: the latest effective date on or
 * before it, then the latest recorded (ruling INV-Q-2940).
 */
fun interface SettingLookup {
    fun find(
        key: VariationPointKey,
        ref: ScopeRef,
        tenant: TenantId?,
        source: SettingSource,
        at: LocalDate,
    ): StoredSetting?
}

/**
 * INV-REQ-1068's fixed precedence as pure logic over a [SettingLookup], so a database reader and a runtime
 * replica resolve alike: the most specific override among the levels the row allows (client entity, AP
 * connection, feed, entity, group, tenant, installation), then the operating profile's value, most
 * specific first, at its version (INV-REQ-1242 behaviour #2, INV-Q-2979), then the catalogue default (by
 * form where the row says so). Evaluated at the transaction date (INV-REQ-1351). A stored value of a row
 * with a cross-scope rule or an installation bound resolves with its directed fields capped at the broader
 * value or bound in force on the date (INV-REQ-1242 behaviour #5, ruling INV-Q-3206; [ScopeBounds]).
 */
class PrecedenceResolver(
    private val catalogue: VariationPointCatalogue,
    private val form: DeploymentForm,
    private val lookup: SettingLookup,
) : PolicyReader {
    private val bounds = ScopeBounds(catalogue, ::resolve)

    override fun resolve(
        key: VariationPointKey,
        scope: PolicyScope,
        at: LocalDate,
    ): ResolvedSetting {
        val definition = catalogue.require(key)
        return when (definition.status) {
            VpStatus.RETIRED -> throw VariationPointRetired(key)

            // A planned row behaves as its default until scenarios cover its options (INV-REQ-1251).
            VpStatus.PLANNED -> defaultOf(definition, at)

            VpStatus.AVAILABLE -> fromSettings(definition, scope, at) ?: defaultOf(definition, at)
        }
    }

    private fun fromSettings(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        at: LocalDate,
    ): ResolvedSetting? {
        val chain = scope.chain().filter { it.level in definition.scopeLevels }
        for (source in SettingSource.entries) {
            for (ref in chain) {
                val found = lookup.find(definition.key, ref, scope.tenant, source, at) ?: continue
                found.selected.forEach {
                    if (definition.option(it.key) == null) throw StoredValueNotInCatalogue(definition.key, it.key)
                }
                val policySource =
                    when (source) {
                        SettingSource.OVERRIDE -> {
                            PolicySource.Override(ref.level, ref.id, found.id)
                        }

                        SettingSource.PROFILE -> {
                            PolicySource.Profile(
                                checkNotNull(found.profileId),
                                checkNotNull(found.profileVersion),
                                ref.level,
                                ref.id,
                                found.id,
                            )
                        }
                    }
                val resolved = ResolvedSetting(definition.key, at, found.selected, policySource)
                return capped(definition, scope, ref.level, resolved)
            }
        }
        return null
    }

    override fun capToBounds(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        at: LocalDate,
    ): ResolvedSetting {
        val definition = catalogue.require(key)
        if (definition.status == VpStatus.RETIRED) throw VariationPointRetired(key)
        val ref = requireNotNull(scope.ref(level)) { "the scope $scope has no $level" }
        val source = PolicySource.Override(level, ref.id, PER_OBJECT)
        return capped(definition, scope, level, ResolvedSetting(key, at, selected, source))
    }

    /** Caps a stored value (INV-REQ-1242 behaviour #5); a bound that cannot be evaluated throws. */
    private fun capped(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        level: ScopeLevel,
        resolved: ResolvedSetting,
    ): ResolvedSetting {
        if (!bounds.applies(definition)) return resolved
        bounds.unsupported(definition)?.let { throw BoundUnavailable(definition.key, it) }
        return bounds.cap(resolved, bounds.limits(definition, scope, level, resolved.selected, resolved.at))
    }

    private fun defaultOf(
        definition: VariationPointDefinition,
        at: LocalDate,
    ): ResolvedSetting {
        val keys = definition.defaultKeys(form) ?: throw ElectionRequired(definition.key)
        val selected =
            keys.map {
                SelectedOption(
                    it,
                    definition
                        .option(it)
                        ?.typedValue
                        ?.let(::defaultValue)
                        ?.toString(),
                )
            }
        return ResolvedSetting(definition.key, at, selected, PolicySource.CatalogueDefault)
    }

    /**
     * A typed default option's value built from the catalogue's typed defaults, e.g. VP-08's DEFAULT_PAIR_4H
     * resolves as `{"window":4}` (INV-Q-3320, INV-Q-3548): a number with a numeric [TypedValueSpec.default], or
     * a record whose every field has one; `null` (no value) otherwise.
     */
    private fun defaultValue(spec: TypedValueSpec): JsonNode? =
        when {
            spec.type.numeric -> {
                spec.default?.number?.let { JsonNodeFactory.instance.numberNode(it) }
            }

            spec.type == ValueType.RECORD -> {
                val node = JsonNodeFactory.instance.objectNode()
                spec.fields.forEach { (name, field) -> node.set(name, defaultValue(field) ?: return null) }
                node
            }

            else -> {
                null
            }
        }

    private companion object {
        /** The setting id of a per-object value: the owning service stores it, config-resolver has no row. */
        val PER_OBJECT: UUID = UUID(0, 0)
    }
}
