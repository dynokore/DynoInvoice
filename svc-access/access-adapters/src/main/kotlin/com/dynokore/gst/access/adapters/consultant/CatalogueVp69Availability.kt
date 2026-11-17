package com.dynokore.gst.access.adapters.consultant

import com.dynokore.gst.access.application.consultant.Vp69Availability
import com.dynokore.gst.access.application.review.VpAvailability
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus

/** The variation point of the consultant grant period (INV-REQ-2465). */
internal val VP_69 = VariationPointKey("VP-69")

/**
 * VP-69's availability read from the catalogue the service runs with, on every call. Only an `available` row answers
 * AVAILABLE; a planned, retired or unknown row answers PLANNED, so a failed lookup never opens other periods (fail
 * closed).
 */
class CatalogueVp69Availability(
    private val catalogue: VariationPointCatalogue,
) : Vp69Availability {
    override fun current(): VpAvailability =
        if (catalogue.find(VP_69)?.status == VpStatus.AVAILABLE) VpAvailability.AVAILABLE else VpAvailability.PLANNED
}
