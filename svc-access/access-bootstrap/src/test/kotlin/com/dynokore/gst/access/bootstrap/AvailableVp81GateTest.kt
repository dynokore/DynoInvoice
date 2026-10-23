package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Gate (1) of the catalogue availability (INV-T-8292, INV-Q-8883; fails closed): the double [AvailableVp81] answers
 * `keys()` and, for every key, its row exactly as the pinned catalogue does; the one difference allowed is `status` of
 * VP-81, which the double answers AVAILABLE with every other field equal to the pinned row. The probes run the same
 * comparison on doubles that overreach.
 */
class AvailableVp81GateTest {
    private val pinned = GstVariationPointCatalogue("access-svc")
    private val vp81 = VariationPointKey("VP-81")

    /** The keys whose row differs from the pinned row in more than the allowed way, plus a key-set difference. */
    private fun violations(double: VariationPointCatalogue): List<String> {
        val keySet = if (double.keys() == pinned.keys()) emptyList() else listOf("keys() differs")
        val rows =
            pinned.keys().mapNotNull { key ->
                val expected = pinned.find(key)
                val actual = double.find(key)
                val allowed = if (key == vp81) expected?.copy(status = VpStatus.AVAILABLE) else expected
                if (actual == allowed) null else "row ${key.value} differs"
            }
        return keySet + rows
    }

    @Test
    fun `INV-AC-8289 the double differs from the pinned catalogue only by the status of VP-81`() {
        assertThat(pinned.keys()).contains(vp81)
        assertThat(violations(AvailableVp81(pinned))).isEmpty()
        assertThat(AvailableVp81(pinned).find(vp81)!!.status).isEqualTo(VpStatus.AVAILABLE)
    }

    @Test
    fun `INV-AC-8289 probe a double that also raises VP-73 fails the gate`() {
        val vp73 = VariationPointKey("VP-73")
        val double =
            object : VariationPointCatalogue {
                override fun find(key: VariationPointKey) =
                    AvailableVp81(pinned).find(key)?.let {
                        if (key == vp73) it.copy(status = VpStatus.entries.first { s -> s != it.status }) else it
                    }

                override fun keys() = pinned.keys()
            }
        assertThat(violations(double)).containsExactly("row VP-73 differs")
    }

    @Test
    fun `INV-AC-8289 probe a double that changes an option key of VP-81 fails the gate`() {
        val double =
            object : VariationPointCatalogue {
                override fun find(key: VariationPointKey) =
                    AvailableVp81(pinned).find(key)?.let {
                        if (key == vp81) it.copy(options = it.options.map { o -> o.copy(key = o.key + "S") }) else it
                    }

                override fun keys() = pinned.keys()
            }
        assertThat(violations(double)).containsExactly("row VP-81 differs")
    }

    @Test
    fun `INV-AC-8289 probe a double that leaves VP-81 as pinned also fails the gate while the pinned row is planned`() {
        if (pinned.find(vp81)!!.status != VpStatus.AVAILABLE) {
            assertThat(violations(pinned)).containsExactly("row VP-81 differs")
        }
    }
}
