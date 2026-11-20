package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.application.session.TenantSessionPolicy
import com.dynokore.gst.consoleBff.domain.session.SessionPolicy
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDate

/**
 * The session timeouts of a tenant (VP-84, INV-REQ-25100): the tenant's resolved value, read per call with
 * `PolicyReader.resolve` at tenant scope, capped by the installation value so the result is the shorter of the two
 * (NIST SP 800-63B AAL2, OWASP ASVS V3.3). The tenant of the row is bound for one read transaction. A value that is
 * not exactly the catalogue's shape throws: fail closed, never guessed and never clamped to a longer value.
 */
class PolicyTenantSessionPolicy(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val installation: SessionPolicy,
) : TenantSessionPolicy {
    override fun of(
        tenantId: String,
        at: LocalDate,
    ): SessionPolicy {
        val tenant = TenantId.of(tenantId)
        val selected =
            tenants.bind(tenant, ACTOR) {
                transactions.inTransaction { reader.resolve(KEY, PolicyScope.tenant(tenant), at).selected }
            }
        val resolved = resolvedOf(selected)
        return SessionPolicy(
            minOf(resolved.idle, installation.idle),
            minOf(resolved.absolute, installation.absolute),
        )
    }

    private fun resolvedOf(selected: List<SelectedOption>): Timeouts {
        check(selected.size == 1) { "VP-84 resolves to exactly one option" }
        val option = selected.single()
        return when (option.key) {
            DEFAULT -> Timeouts(DEFAULT_IDLE, DEFAULT_ABSOLUTE)
            SHORTER -> shorter(option.valueJson)
            else -> error("VP-84 option is not known")
        }
    }

    private fun shorter(valueJson: String?): Timeouts {
        val node = MAPPER.readTree(checkNotNull(valueJson) { "VP-84 SHORTER has no value" })
        val idle = whole(node, "idle", MAX_IDLE_MINUTES)
        val absolute = whole(node, "absolute", MAX_ABSOLUTE_HOURS)
        return Timeouts(Duration.ofMinutes(idle), Duration.ofHours(absolute))
    }

    private fun whole(
        node: JsonNode,
        field: String,
        max: Long,
    ): Long {
        val value = node.get(field)
        check(
            value != null && value.isIntegralNumber && value.canConvertToLong(),
        ) { "VP-84 $field is not a whole number" }
        return value.asLong().also { check(it in 1..max) { "VP-84 $field is out of its bound" } }
    }

    private class Timeouts(
        val idle: Duration,
        val absolute: Duration,
    )

    companion object {
        /** The variation point of the console session timeouts. */
        val KEY = VariationPointKey("VP-84")
        const val ACTOR = "svc:console-bff/session-policy"
        private const val DEFAULT = "DEFAULT_30M_12H"
        private const val SHORTER = "SHORTER"
        private const val MAX_IDLE_MINUTES = 30L
        private const val MAX_ABSOLUTE_HOURS = 12L
        private val DEFAULT_IDLE = Duration.ofMinutes(MAX_IDLE_MINUTES)
        private val DEFAULT_ABSOLUTE = Duration.ofHours(MAX_ABSOLUTE_HOURS)
        private val MAPPER = ObjectMapper()
    }
}
