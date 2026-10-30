package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus

/**
 * The pinned catalogue with VP-82 alone answered as AVAILABLE (INV-T-9276, INV-Q-12349): every other key, and every
 * other field of VP-82, is the pinned row. Test source set only; no property or profile switch.
 */
internal class AvailableVp82(
    private val pinned: VariationPointCatalogue,
) : VariationPointCatalogue {
    override fun find(key: VariationPointKey) =
        pinned.find(key)?.let { if (key.value == "VP-82") it.copy(status = VpStatus.AVAILABLE) else it }

    override fun keys() = pinned.keys()
}
