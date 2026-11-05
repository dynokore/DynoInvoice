package com.dynokore.gst.masterdata.adapters.registry

import com.dynokore.gst.masterdata.application.registry.RegistryPolicies
import com.dynokore.gst.masterdata.domain.registry.CacheLifetimes
import com.dynokore.gst.masterdata.domain.registry.RegisterPolicy
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDate

/** The variation points masterdata-svc owns and reads through config-resolver (CS-28, TPL-03). */
object RegistryVariationPoints {
    /** VP-29: what the GST register lookup does with a difference (INV-REQ-1290), entity scope. */
    val REGISTER_USE = VariationPointKey("VP-29")

    /** VP-38: the Peppol directory cache lifetimes in minutes (INV-REQ-1299), tenant scope. */
    val DIRECTORY_LIFETIMES = VariationPointKey("VP-38")

    /** VP-65: the Check GST Register cache lifetimes in minutes (INV-REQ-1548), tenant scope. */
    val REGISTER_LIFETIMES = VariationPointKey("VP-65")
}

/**
 * VP-29, VP-38 and VP-65 as `PolicyReader.resolve` gives them on the date, read on every lookup (INV-Q-9649 A). A value
 * the writer accepted is tighten-only, so it is never above the default; this reader still caps a stored value at the
 * default, so a value that got there another way cannot lengthen a lifetime. An option it does not know is refused,
 * never guessed.
 */
class PolicyRegistryPolicies(
    private val policy: PolicyReader,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : RegistryPolicies {
    override fun directoryLifetimes(
        entity: String,
        on: LocalDate,
    ): CacheLifetimes = lifetimes(RegistryVariationPoints.DIRECTORY_LIFETIMES, entity, on, "positive", "negative")

    override fun registerLifetimes(
        entity: String,
        on: LocalDate,
    ): CacheLifetimes = lifetimes(RegistryVariationPoints.REGISTER_LIFETIMES, entity, on, "found", "not_found")

    override fun registerPolicy(
        entity: String,
        on: LocalDate,
    ): RegisterPolicy {
        val resolved = policy.resolve(RegistryVariationPoints.REGISTER_USE, scope(entity), on)
        val key = resolved.selected.single().key
        return RegisterPolicy.entries.firstOrNull { it.name == key }
            ?: error("VP-29 resolved to an option this service does not know: $key")
    }

    private fun scope(entity: String) = PolicyScope.entity(tenants.current(), entity)

    private fun lifetimes(
        key: VariationPointKey,
        entity: String,
        on: LocalDate,
        positiveField: String,
        negativeField: String,
    ): CacheLifetimes {
        val option = policy.resolve(key, scope(entity), on).selected.single()
        return when (option.key) {
            DEFAULT_OPTION -> {
                CacheLifetimes.DEFAULT
            }

            SHORTER_OPTION -> {
                val record = mapper.readTree(checkNotNull(option.valueJson) { "$key ${option.key} has no value" })
                CacheLifetimes(
                    minutes(record, positiveField).coerceAtMost(CacheLifetimes.DEFAULT.positive),
                    minutes(record, negativeField).coerceAtMost(CacheLifetimes.DEFAULT.negative),
                )
            }

            else -> {
                error("$key resolved to an option this service does not know: ${option.key}")
            }
        }
    }

    private fun minutes(
        record: JsonNode,
        field: String,
    ): Duration = Duration.ofMinutes(checkNotNull(record.get(field)) { "the value has no $field" }.asLong())

    private companion object {
        const val DEFAULT_OPTION = "DEFAULT_24H_1H"
        const val SHORTER_OPTION = "SHORTER"
    }
}
