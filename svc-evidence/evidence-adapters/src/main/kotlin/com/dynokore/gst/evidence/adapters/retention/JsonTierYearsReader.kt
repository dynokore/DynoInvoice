package com.dynokore.gst.evidence.adapters.retention

import com.dynokore.gst.evidence.application.retention.TierYearsReader
import tools.jackson.databind.ObjectMapper

/** [TierYearsReader] over Jackson: the field must be a whole number, anything else fails closed. */
class JsonTierYearsReader(
    private val mapper: ObjectMapper = ObjectMapper(),
) : TierYearsReader {
    override fun years(
        recordJson: String,
        property: String,
    ): Int {
        val field = mapper.readTree(recordJson).get(property)
        check(field != null && field.isIntegralNumber && field.canConvertToInt()) {
            "the VP-43 value has no whole number for $property"
        }
        return field.intValue()
    }
}
