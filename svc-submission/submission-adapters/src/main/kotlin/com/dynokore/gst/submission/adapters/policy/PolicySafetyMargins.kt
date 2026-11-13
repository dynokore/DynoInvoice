package com.dynokore.gst.submission.adapters.policy

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.drain.SafetyMargins
import com.dynokore.gst.submission.application.pack.PackDefaultMargin
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.pack.PackMarginAnswer
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * [SafetyMargins] over the configuration resolver (INV-REQ-1279, INV-AC-20848): the variation point VP-18 is resolved
 * at entity scope on the business date passed in (the adapter reads no clock). Option LARGER carries a typed duration
 * `{"value": n, "unit": "DAYS" | "HOURS"}` that is returned as a [PackMargin]; option PACK_DEFAULT returns the answer
 * of [PackDefaultMargin] for the tenant and date. Anything else (a resolver exception, an unreadable value, a unit
 * other than DAYS or HOURS, a missing or repeated option) is [SafetyMarginAnswer.Unavailable]: fail closed, no default
 * number. The key is read from the generated catalogue; no margin number is written here.
 */
class PolicySafetyMargins(
    private val reader: PolicyReader,
    private val packDefault: PackDefaultMargin,
    private val mapper: ObjectMapper = ObjectMapper(),
) : SafetyMargins {
    @Suppress("TooGenericExceptionCaught")
    override fun effective(
        tenant: TenantId,
        entityRef: String,
        on: LocalDate,
    ): SafetyMarginAnswer =
        try {
            answer(tenant, entityRef, on)
        } catch (ignored: RuntimeException) {
            // UnknownVariationPoint, VariationPointRetired, ElectionRequired, BoundUnavailable, unreadable value.
            SafetyMarginAnswer.Unavailable
        }

    private fun answer(
        tenant: TenantId,
        entityRef: String,
        on: LocalDate,
    ): SafetyMarginAnswer {
        val scope = PolicyScope.entity(tenant, entity = entityRef)
        val selected = reader.resolve(VariationPointKey(KEY), scope, on).selected.singleOrNull()
        return when (selected?.key) {
            LARGER -> larger(selected.valueJson)
            PACK_DEFAULT -> packDefault(tenant, on)
            else -> SafetyMarginAnswer.Unavailable
        }
    }

    private fun packDefault(
        tenant: TenantId,
        on: LocalDate,
    ): SafetyMarginAnswer =
        when (val answer = packDefault.on(tenant, on)) {
            is PackMarginAnswer.Known -> SafetyMarginAnswer.Known(answer.margin)
            PackMarginAnswer.Unknown -> SafetyMarginAnswer.Unavailable
        }

    private fun larger(valueJson: String?): SafetyMarginAnswer {
        val node: JsonNode = mapper.readTree(valueJson ?: return SafetyMarginAnswer.Unavailable)
        val value = node.get("value")
        val unit = node.get("unit")
        val margin = margin(value, unit)
        return if (margin == null) SafetyMarginAnswer.Unavailable else SafetyMarginAnswer.Known(margin)
    }

    private fun margin(
        value: JsonNode?,
        unit: JsonNode?,
    ): PackMargin? =
        if (value?.isIntegralNumber == true && unit?.isString == true) {
            PackMargin.of(value.asLong(), unit.asString())
        } else {
            null
        }

    companion object {
        /** VP-18, read from the generated catalogue; a test compares it with the catalogue entry. */
        val KEY: String = VariationPoint.VP_18.id
        private const val LARGER = "LARGER"
        private const val PACK_DEFAULT = "PACK_DEFAULT"
    }
}
