package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * The entity's voluntary over-submission election, variation point VP-21 (INV-REQ-375, INV-REQ-1282), read from the
 * policy replica only: no service call and no constant. The replica holds the value as the producer writes it, an
 * option list. It is on only when the value in force on the transaction date is the text of a JSON array of exactly
 * one object whose only member is `key`, the string `ON` (strict parse: duplicate members and trailing tokens are
 * refused). An absent value, any other value, and a value of another key or entity are not applied and never throw
 * (allow-list input validation, fail closed, INV-AC-2268, INV-AC-25208).
 */
class VoluntaryOverSubmission(
    private val policy: PolicyReplica,
) {
    fun isOn(
        tenant: TenantId,
        entityRef: String,
        date: TransactionDate,
    ): Boolean =
        !tenant.isPlatform &&
            policy
                .valueAt(
                    PolicyScope.entity(tenant, entityRef),
                    VARIATION_POINT,
                    date.value,
                )?.value
                ?.let(::isOnList) ==
            true

    private fun isOnList(text: String): Boolean =
        runCatching { mapper.readTree(text) }.getOrNull()?.let { list ->
            list.isArray &&
                list.size() == 1 &&
                list[0].isObject &&
                list[0].size() == 1 &&
                list[0][KEY]?.let { it.isString && it.stringValue() == ON } == true
        } == true

    companion object {
        const val VARIATION_POINT = "VP-21"
        const val ON = "ON"
        private const val KEY = "key"
        private val mapper: JsonMapper =
            JsonMapper
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build()
    }
}
