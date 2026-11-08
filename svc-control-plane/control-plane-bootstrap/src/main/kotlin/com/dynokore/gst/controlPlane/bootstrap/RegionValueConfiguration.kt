package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.commercial.RegionList
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * The value check of VP-45 (INV-T-9238, INV-REQ-1239 behaviour #3): the published option is "any region on the
 * platform's region list" (INV-Q-2322), so a selection is accepted only when [RegionList.contains] is true for its key,
 * compared by equality. Every other variation point key is refused (deny by default, INV-T-9238 ruling 8): a later
 * control-plane row adds its own branch in its own task.
 */
@Configuration(proxyBeanMethods = false)
class RegionValueConfiguration {
    @Bean
    fun valueConstraints(regions: RegionList): ValueConstraints = RegionValueConstraints(regions)
}

/** Accepts a VP-45 selection only for a key on the region list; every other variation point key is refused. */
class RegionValueConstraints(
    private val regions: RegionList,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? =
        if (key.value != REGION_POINT) {
            ConstraintVerdict.Refuse("no value check is bound for this variation point")
        } else {
            val region = selected.singleOrNull()?.let { regionOf(it.valueJson) }
            if (region != null && onList(region)) {
                ConstraintVerdict.Accept
            } else {
                ConstraintVerdict.Refuse("the region is not a single key on the region list")
            }
        }

    private fun onList(region: String): Boolean =
        region.length in 1..RegionKey.MAX_LENGTH && regions.contains(RegionKey(region))

    private fun regionOf(valueJson: String?): String? =
        valueJson?.let { runCatching { MAPPER.readTree(it) }.getOrNull() }?.takeIf { it.isString }?.asString()

    private companion object {
        const val REGION_POINT = "VP-45"
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
