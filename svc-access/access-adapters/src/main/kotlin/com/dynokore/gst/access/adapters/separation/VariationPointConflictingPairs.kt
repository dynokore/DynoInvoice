package com.dynokore.gst.access.adapters.separation

import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.application.separation.RolePair
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The tenant-scope value of VP-82 (INV-REQ-9271), set by `tenant.admin` through service-runtime's `setPolicy`: the one
 * selected option key, mapped by string equality. NO_EXTRA_PAIRS gives the empty set; CONFLICTING_PAIRS gives its list
 * of `{first, second}` pairs of wire role names. Any other key, an empty or longer selection, a missing or empty list,
 * a pair of two equal roles, a name that is no role and a failed read throw; no other value is returned in their place.
 */
class VariationPointConflictingPairs(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) : ConflictingRolePairs {
    override fun current(): Set<RolePair> {
        val setting =
            reader.resolve(
                VP_82,
                PolicyScope.tenant(tenants.current()),
                BusinessDate.today(clock),
            )
        val selected = setting.selected.single()
        return when (selected.key) {
            NO_EXTRA_PAIRS -> emptySet()
            CONFLICTING_PAIRS -> pairsOf(checkNotNull(selected.valueJson) { "VP-82 lists no pairs" })
            else -> error("VP-82 selects an option this service does not know")
        }
    }

    private fun pairsOf(json: String): Set<RolePair> {
        val list = mapper.readTree(json)
        check(list.isArray && list.size() > 0) { "VP-82 lists no pairs" }
        return (0 until list.size()).map { pairOf(list.get(it)) }.toSet()
    }

    private fun pairOf(node: JsonNode): RolePair {
        check(node.isObject && node.propertyNames().asSequence().toSet() == FIELDS) { "VP-82 pair is malformed" }
        val first = roleOf(node.get("first"))
        val second = roleOf(node.get("second"))
        check(first != second) { "VP-82 pair names one role twice" }
        return RolePair.of(first, second)
    }

    private fun roleOf(node: JsonNode): Role {
        check(node.isString) { "VP-82 pair holds a value that is no role name" }
        return checkNotNull(Role.ofWire(node.asString())) { "VP-82 pair names a role this service does not know" }
    }

    private companion object {
        val VP_82 = VariationPointKey("VP-82")
        const val NO_EXTRA_PAIRS = "NO_EXTRA_PAIRS"
        const val CONFLICTING_PAIRS = "CONFLICTING_PAIRS"
        val FIELDS = setOf("first", "second")
    }
}
