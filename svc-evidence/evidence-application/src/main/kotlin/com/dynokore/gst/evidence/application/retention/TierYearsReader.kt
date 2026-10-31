package com.dynokore.gst.evidence.application.retention

/** Reads the years of one tier from the stored record of a LONGER value of VP-43 (JSON), in an adapter. */
fun interface TierYearsReader {
    /**
     * @param recordJson the stored value, an object of whole numbers by tier property.
     * @param property the property of the tier ([com.dynokore.gst.evidence.domain.retention.RetentionTier.property]).
     * @throws IllegalStateException when the record has no whole number for [property].
     */
    fun years(
        recordJson: String,
        property: String,
    ): Int
}
