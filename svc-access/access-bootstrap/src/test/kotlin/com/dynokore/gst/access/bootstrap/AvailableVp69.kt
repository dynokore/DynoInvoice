package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus

/**
 * The pinned catalogue with VP-69 alone answered as AVAILABLE (INV-T-15800, INV-REQ-2465): every other key, and every
 * other field of VP-69, is the pinned row. Test source set only; no property or profile switch.
 */
internal class AvailableVp69(
    private val pinned: VariationPointCatalogue,
) : VariationPointCatalogue {
    override fun find(key: VariationPointKey) =
        pinned.find(key)?.let { if (key.value == "VP-69") it.copy(status = VpStatus.AVAILABLE) else it }

    override fun keys() = pinned.keys()
}
