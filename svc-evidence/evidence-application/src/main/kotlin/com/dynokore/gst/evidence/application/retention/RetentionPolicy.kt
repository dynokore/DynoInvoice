package com.dynokore.gst.evidence.application.retention

import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey

/** The variation point and option names of the evidence retention period (VP-43, INV-REQ-1304). */
object RetentionPolicy {
    val KEY = VariationPointKey("VP-43")

    /** The option that carries a record of years per tier. */
    const val LONGER = "LONGER"

    private const val YEARS_PREFIX = "YEARS_"

    /**
     * The years every tier has when no value is set: the default option of [KEY] in the catalogue, `YEARS_<n>`
     * (INV-REQ-887).
     * The number is read from the catalogue, never written here (INV-AC-10452).
     */
    fun defaultYears(catalogue: VariationPointCatalogue): Int {
        val definition = catalogue.require(KEY)
        val option = definition.options.single { it.strictness == Strictness.DEFAULT }
        require(option.key.startsWith(YEARS_PREFIX)) { "the default option of $KEY is '${option.key}', not YEARS_<n>" }
        return option.key.removePrefix(YEARS_PREFIX).toInt()
    }
}
