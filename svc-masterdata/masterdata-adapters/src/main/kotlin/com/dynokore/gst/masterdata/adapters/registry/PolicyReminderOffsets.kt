package com.dynokore.gst.masterdata.adapters.registry

import com.dynokore.gst.masterdata.application.connection.ReminderOffsets
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneId

/**
 * VP-68 (INV-REQ-2464): the days before a connection credential expires at which a reminder is requested, resolved
 * through `PolicyReader` at tenant scope on the transaction date (INV-REQ-1351), on every check. The option `DAYS_14_3`
 * is [DEFAULT_OFFSET_DAYS]; `CUSTOM_DAYS` is the integer list of its value; any other option is an error (fail closed),
 * and so is a custom value that is not a non-empty list of positive integers. The option keys are those of the entry
 * `vp-68.set` in platform-contracts `catalogue/variation-points/variation-points.yaml`.
 */
class PolicyReminderOffsets(
    private val policy: PolicyReader,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : ReminderOffsets {
    override fun days(): List<Int> {
        val on = clock.instant().atZone(ZoneId.of("Asia/Singapore")).toLocalDate()
        val option = policy.resolve(VARIATION_POINT, PolicyScope.tenant(tenants.current()), on).selected.single()
        return when (option.key) {
            DEFAULT_OPTION -> {
                DEFAULT_OFFSET_DAYS
            }

            CUSTOM_OPTION -> {
                val value =
                    mapper.readTree(
                        checkNotNull(option.valueJson) { "$VARIATION_POINT ${option.key} has no value" },
                    )
                check(value.isArray && value.size() > 0) { "$VARIATION_POINT ${option.key} is not a non-empty list" }
                (0 until value.size()).map {
                    val day = value.get(it)
                    check(day.isInt && day.asInt() >= 1) { "$VARIATION_POINT ${option.key} holds a non-integer day" }
                    day.asInt()
                }
            }

            else -> {
                error("$VARIATION_POINT resolved to an option this service does not know: ${option.key}")
            }
        }
    }

    companion object {
        /** VP-68: the credential-expiry reminder days, tenant scope. */
        val VARIATION_POINT = VariationPointKey("VP-68")

        const val DEFAULT_OPTION = "DAYS_14_3"
        const val CUSTOM_OPTION = "CUSTOM_DAYS"

        /** INV-REQ-2464 and INV-REQ-837: 14 and 3 days before expiry. The one place the numbers are written. */
        val DEFAULT_OFFSET_DAYS: List<Int> = listOf(14, 3)
    }
}
