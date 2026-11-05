package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Gate (1) of the catalogue availability (INV-T-4936, after INV-T-8292; fails closed): the double [AvailableVp32]
 * answers `keys()` and, for every key, its row exactly as the pinned catalogue does. The one difference allowed is
 * `status` of VP-32, which the double answers AVAILABLE with every other field equal to the pinned row. The probes
 * run the same comparison on doubles that overreach.
 */
class AvailableVp32GateTest {
    private val pinned = GstVariationPointCatalogue("access-svc")
    private val vp32 = VariationPointKey("VP-32")

    /** The keys whose row differs from the pinned row in more than the allowed way, plus a key-set difference. */
    private fun violations(double: VariationPointCatalogue): List<String> {
        val keySet = if (double.keys() == pinned.keys()) emptyList() else listOf("keys() differs")
        val rows =
            pinned.keys().mapNotNull { key ->
                val expected = pinned.find(key)
                val actual = double.find(key)
                val allowed = if (key == vp32) expected?.copy(status = VpStatus.AVAILABLE) else expected
                if (actual == allowed) null else "row ${key.value} differs"
            }
        return keySet + rows
    }

    @Test
    fun `INV-AC-9561 the double differs from the pinned catalogue only by the status of VP-32`() {
        assertThat(pinned.keys()).contains(vp32)
        assertThat(violations(AvailableVp32(pinned))).isEmpty()
        assertThat(AvailableVp32(pinned).find(vp32)!!.status).isEqualTo(VpStatus.AVAILABLE)
    }

    @Test
    fun `INV-AC-9561 probe a double that also raises VP-03 fails the gate`() {
        val vp03 = VariationPointKey("VP-03")
        val double =
            object : VariationPointCatalogue {
                override fun find(key: VariationPointKey) =
                    AvailableVp32(pinned).find(key)?.let {
                        if (key == vp03) it.copy(status = VpStatus.entries.first { s -> s != it.status }) else it
                    }

                override fun keys() = pinned.keys()
            }
        assertThat(violations(double)).containsExactly("row VP-03 differs")
    }

    @Test
    fun `INV-AC-9561 probe a double that changes an option key of VP-32 fails the gate`() {
        val double =
            object : VariationPointCatalogue {
                override fun find(key: VariationPointKey) =
                    AvailableVp32(pinned).find(key)?.let {
                        if (key == vp32) it.copy(options = it.options.map { o -> o.copy(key = o.key + "S") }) else it
                    }

                override fun keys() = pinned.keys()
            }
        assertThat(violations(double)).containsExactly("row VP-32 differs")
    }

    @Test
    fun `INV-AC-9561 probe a double that leaves VP-32 as pinned also fails the gate while the pinned row is planned`() {
        if (pinned.find(vp32)!!.status != VpStatus.AVAILABLE) {
            assertThat(violations(pinned)).containsExactly("row VP-32 differs")
        }
    }
}
