package com.dynokore.platform.configresolver

import org.jooq.DSLContext
import java.time.LocalDate

/**
 * [PolicyHistory] over the setting tables, through jOOQ's DSL on the DSLContext of tenancy-jooq's factory, in the caller's
 * open transaction (INV-REQ-1129, INV-AC-1146). In force on a date is what resolution gave on that date (INV-REQ-1243
 * behaviour #5); changed since is every version recorded after the date, on the installation business date,
 * or taking effect after it (ruling INV-Q-3171).
 */
class JdbcPolicyHistory(
    dsl: DSLContext,
    private val catalogue: VariationPointCatalogue,
    form: DeploymentForm,
    schema: String,
) : PolicyHistory {
    private val writes = JdbcSettingWrites(dsl, schema)
    private val reader = JdbcPolicyReader(dsl, catalogue, form, schema)

    override fun versions(
        key: VariationPointKey,
        target: SettingTarget,
    ): List<SettingVersion> {
        catalogue.require(key)
        return writes.versions(key, target)
    }

    override fun asOf(
        scope: PolicyScope,
        date: LocalDate,
        keys: Set<VariationPointKey>?,
    ): ConfigurationAsOf {
        val owned = catalogue.keys()
        keys?.forEach { if (it !in owned) throw UnknownVariationPoint(it) }
        val chosen = (keys ?: owned).sortedBy { it.value }
        val entries =
            chosen.mapNotNull { key ->
                val definition = catalogue.require(key)
                if (definition.status == VpStatus.RETIRED) return@mapNotNull null
                val inForce =
                    try {
                        reader.resolve(key, scope, date)
                    } catch (_: ElectionRequired) {
                        null
                    }
                val since =
                    scope
                        .chain()
                        .filter { it.level in definition.scopeLevels }
                        .flatMap { writes.versions(key, scope.target(it.level)) }
                        .filter {
                            it.effectiveFrom > date ||
                                it.recordedAt.atZone(BusinessDate.ZONE).toLocalDate() > date
                        }.sortedBy { it.recordedAt }
                AsOfEntry(key, inForce, since)
            }
        return ConfigurationAsOf(scope, date, entries)
    }

    override fun compare(
        a: SettingVersion,
        b: SettingVersion,
    ): VersionComparison {
        require(a.key == b.key) { "only versions of one variation point compare" }
        val order = compareBy<SettingVersion>({ it.recordedAt }, { it.effectiveFrom })
        val (older, newer) = if (order.compare(a, b) <= 0) a to b else b to a
        val olderKeys = older.selected.associateBy { it.key }
        val newerKeys = newer.selected.associateBy { it.key }
        return VersionComparison(
            older = older,
            newer = newer,
            addedOptions = newerKeys.keys.filter { it !in olderKeys },
            removedOptions = olderKeys.keys.filter { it !in newerKeys },
            changedValues =
                newerKeys.keys.filter {
                    it in olderKeys &&
                        olderKeys[it]?.valueJson != newerKeys[it]?.valueJson
                },
        )
    }
}
