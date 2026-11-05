package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus

/**
 * The pinned catalogue with VP-32 alone answered as AVAILABLE (INV-T-4936, after INV-T-8292): every other
 * key, and every other field of VP-32, is the pinned row. Test source set only; no property or profile switch.
 */
internal class AvailableVp32(
    private val pinned: VariationPointCatalogue,
) : VariationPointCatalogue {
    override fun find(key: VariationPointKey) =
        pinned.find(key)?.let { if (key.value == "VP-32") it.copy(status = VpStatus.AVAILABLE) else it }

    override fun keys() = pinned.keys()
}
