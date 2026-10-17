package com.dynokore.platform.adapterruntime

import com.dynokore.platform.adapterruntime.PassThroughViolationKind.DROPPED
import com.dynokore.platform.adapterruntime.PassThroughViolationKind.LINE_COUNT_CHANGED
import com.dynokore.platform.adapterruntime.PassThroughViolationKind.RECODED
import com.dynokore.platform.adapterruntime.PassThroughViolationKind.UNKNOWN_EVENT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class TaxPassThroughVerifierTest {
    private fun ev(
        key: String?,
        vararg codes: String,
    ) = UpstreamEvent(
        key,
        Instant.EPOCH,
        "INVOICE",
        codes.map { UpstreamLine(it, "0", "0") },
        "P",
        AmendmentIndicator(false, null),
        null,
    )

    private fun kinds(
        source: List<SourceTransaction>,
        emitted: List<UpstreamEvent>,
    ) = TaxPassThroughVerifier.verify(source, emitted).map { it.kind }

    @Test
    fun `out-of-scope ZP and NA codes pass through unchanged (INV-AC-1854)`() {
        val source = listOf(SourceTransaction("A", listOf("ZP")), SourceTransaction("B", listOf("NA", "SR")))
        TaxPassThroughVerifier.verify(source, listOf(ev("A", "ZP"), ev("B", "NA", "SR"))).shouldBeEmpty()
    }

    @Test
    fun `one dropped transaction of three gives one DROPPED`() {
        val source = listOf("A", "B", "C").map { SourceTransaction(it, listOf("SR")) }
        val v = TaxPassThroughVerifier.verify(source, listOf(ev("A", "SR"), ev("C", "SR")))
        v.map { it.kind } shouldBe listOf(DROPPED)
        v.single().sourceKey shouldBe "B"
    }

    @Test
    fun `a line recoded from SR to ZR gives one RECODED`() =
        (kinds(listOf(SourceTransaction("A", listOf("SR"))), listOf(ev("A", "ZR"))) shouldBe listOf(RECODED))

    @Test
    fun `a recode in any position is found`() =
        (
            kinds(
                listOf(SourceTransaction("A", listOf("SR", "ZR"))),
                listOf(ev("A", "SR", "SR")),
            ) shouldBe listOf(RECODED)
        )

    @Test
    fun `a removed line gives LINE_COUNT_CHANGED`() =
        (
            kinds(listOf(SourceTransaction("A", listOf("SR", "ZR"))), listOf(ev("A", "SR"))) shouldBe
                listOf(LINE_COUNT_CHANGED)
        )

    @Test
    fun `an added line gives LINE_COUNT_CHANGED`() =
        (
            kinds(listOf(SourceTransaction("A", listOf("SR"))), listOf(ev("A", "SR", "SR"))) shouldBe
                listOf(LINE_COUNT_CHANGED)
        )

    @Test
    fun `a code differing only in case is RECODED (exact equality)`() =
        (kinds(listOf(SourceTransaction("A", listOf("SR"))), listOf(ev("A", "sr"))) shouldBe listOf(RECODED))

    @Test
    fun `an extra emitted key gives UNKNOWN_EVENT`() =
        (
            kinds(listOf(SourceTransaction("A", listOf("SR"))), listOf(ev("A", "SR"), ev("X", "SR"))) shouldBe
                listOf(UNKNOWN_EVENT)
        )

    @Test
    fun `an event without a source key is UNKNOWN_EVENT`() =
        (
            kinds(listOf(SourceTransaction("A", listOf("SR"))), listOf(ev("A", "SR"), ev(null, "SR"))) shouldBe
                listOf(UNKNOWN_EVENT)
        )

    @Test
    fun `a key differing only in case is not the source key`() =
        (
            kinds(listOf(SourceTransaction("A", listOf("SR"))), listOf(ev("a", "SR"))) shouldBe
                listOf(DROPPED, UNKNOWN_EVENT)
        )

    @Test
    fun `duplicate source keys are refused`() {
        shouldThrow<IllegalArgumentException> {
            TaxPassThroughVerifier.verify(
                listOf(SourceTransaction("A", listOf("SR")), SourceTransaction("A", listOf("ZR"))),
                emptyList(),
            )
        }
    }
}
