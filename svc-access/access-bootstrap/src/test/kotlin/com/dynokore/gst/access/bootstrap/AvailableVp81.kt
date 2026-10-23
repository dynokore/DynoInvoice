package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus

/**
 * The pinned catalogue with VP-81 alone answered as AVAILABLE (INV-T-8292, coordinator ruling INV-Q-8883): every other
 * key, and every other field of VP-81, is the pinned row. Test source set only; no property or profile switch.
 */
internal class AvailableVp81(
    private val pinned: VariationPointCatalogue,
) : VariationPointCatalogue {
    override fun find(key: VariationPointKey) =
        pinned.find(key)?.let { if (key.value == "VP-81") it.copy(status = VpStatus.AVAILABLE) else it }

    override fun keys() = pinned.keys()
}
