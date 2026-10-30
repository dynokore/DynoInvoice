package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.catalogue.VariationPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The value set equals the catalogue (INV-T-9276, INV-REQ-9271; fails closed): the pinned gst-catalogue holds exactly
 * one row VP-82 whose option keys equal `NO_EXTRA_PAIRS` and `CONFLICTING_PAIRS`, compared as sets in both directions,
 * and whose status is PLANNED or AVAILABLE. The probes run on copies of the key set.
 */
class ConflictingPairsCatalogueGateTest {
    private val expected = setOf("NO_EXTRA_PAIRS", "CONFLICTING_PAIRS")

    private fun rows() = VariationPoint.entries.filter { it.id == "VP-82" }

    private fun catalogueKeys(): Set<String> {
        assertThat(rows()).describedAs("the row VP-82 in the pinned gst-catalogue").hasSize(1)
        val keys =
            rows()
                .single()
                .optionSpecs
                .orEmpty()
                .map { it.key }
        assertThat(keys).describedAs("option keys of VP-82 are distinct").doesNotHaveDuplicates()
        return keys.toSet()
    }

    /** The differing keys, each naming its side; empty when the sets are equal. */
    private fun gate(catalogue: Set<String>): List<String> =
        (catalogue - expected).map { "key $it is in the catalogue and not in the service" } +
            (expected - catalogue).map { "key $it is in the service and not in the catalogue" }

    @Test
    fun `INV-AC-9272 the option keys of VP-82 in the pinned catalogue are the two the service reads`() {
        assertThat(gate(catalogueKeys())).isEmpty()
    }

    @Test
    fun `INV-AC-9272 the pinned status of VP-82 is planned or available`() {
        assertThat(rows().single().status.name).isIn("PLANNED", "AVAILABLE")
    }

    @Test
    fun `INV-AC-9272 probe a look-alike key is named on both sides`() {
        assertThat(gate(catalogueKeys() - "NO_EXTRA_PAIRS" + "NO_EXTRA_PAIR"))
            .containsExactlyInAnyOrder(
                "key NO_EXTRA_PAIR is in the catalogue and not in the service",
                "key NO_EXTRA_PAIRS is in the service and not in the catalogue",
            )
    }

    @Test
    fun `INV-AC-9272 probe a third key is named`() {
        assertThat(gate(catalogueKeys() + "THIRD"))
            .containsExactly("key THIRD is in the catalogue and not in the service")
    }

    @Test
    fun `INV-AC-9272 probe a missing key is named`() {
        assertThat(gate(catalogueKeys() - "CONFLICTING_PAIRS"))
            .containsExactly("key CONFLICTING_PAIRS is in the service and not in the catalogue")
    }
}
