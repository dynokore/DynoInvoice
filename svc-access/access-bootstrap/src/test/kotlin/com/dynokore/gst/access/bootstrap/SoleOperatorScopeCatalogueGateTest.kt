package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.separation.SoleOperatorScope
import com.dynokore.gst.catalogue.VariationPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The value set equals the catalogue (INV-T-8292, INV-REQ-8287; fails closed): the option keys of VP-81 in the pinned
 * gst-catalogue `VariationPoint` catalogue and the constants of [SoleOperatorScope], compared as sets in both
 * directions. The probes run on copies of the two sets.
 */
class SoleOperatorScopeCatalogueGateTest {
    private fun catalogueKeys(): Set<String> {
        val row = VariationPoint.entries.filter { it.id == "VP-81" }
        assertThat(row).describedAs("the row VP-81 in the pinned gst-catalogue").hasSize(1)
        val keys =
            row
                .single()
                .optionSpecs
                .orEmpty()
                .map { it.key }
        assertThat(keys).describedAs("option keys of VP-81 are distinct").doesNotHaveDuplicates()
        return keys.toSet()
    }

    private fun constants(): Set<String> = SoleOperatorScope.entries.map { it.name }.toSet()

    /** The differing keys, each naming its side; empty when the sets are equal. */
    private fun gate(
        catalogue: Set<String>,
        enumConstants: Set<String>,
    ): List<String> =
        (catalogue - enumConstants).map { "key $it is in the catalogue and not in SoleOperatorScope" } +
            (enumConstants - catalogue).map { "key $it is in SoleOperatorScope and not in the catalogue" }

    @Test
    fun `INV-AC-8289 the constants of SoleOperatorScope equal the option keys of VP-81 in the pinned catalogue`() {
        assertThat(catalogueKeys()).isNotEmpty()
        assertThat(gate(catalogueKeys(), constants())).isEmpty()
    }

    @Test
    fun `INV-AC-8289 probe a fourth catalogue key is named`() {
        assertThat(gate(catalogueKeys() + "FOUR_HOLDERS", constants()))
            .containsExactly("key FOUR_HOLDERS is in the catalogue and not in SoleOperatorScope")
    }

    @Test
    fun `INV-AC-8289 probe a constant renamed to a look-alike is named on both sides`() {
        val renamed = constants() - "ONE_HOLDER" + "ONE_HOLDERS"
        assertThat(gate(catalogueKeys(), renamed))
            .containsExactlyInAnyOrder(
                "key ONE_HOLDER is in the catalogue and not in SoleOperatorScope",
                "key ONE_HOLDERS is in SoleOperatorScope and not in the catalogue",
            )
    }

    @Test
    fun `INV-AC-8289 probe a deleted constant is named`() {
        assertThat(gate(catalogueKeys(), constants() - "ANY_NUMBER"))
            .containsExactly("key ANY_NUMBER is in the catalogue and not in SoleOperatorScope")
    }
}
