package com.dynokore.platform.adapterruntime

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class UpstreamContractVerifierTest {
    private val complete =
        UpstreamEvent(
            sourceKey = "K1",
            postingTimestamp = Instant.parse("2026-10-18T00:00:00Z"),
            documentFunctionHint = "INVOICE",
            lines = listOf(UpstreamLine("SR", "9", "9.00")),
            counterpartyRef = "P1",
            amendment = AmendmentIndicator(false, null),
            batchManifest = BatchManifest("B1", 3),
        )

    private fun elements(
        e: UpstreamEvent,
        feed: FeedKind,
    ) = UpstreamContractVerifier.verify(e, feed).map { it.element }

    @Test
    fun `a complete event gives no violation on either feed`() {
        FeedKind.entries.forEach { UpstreamContractVerifier.verify(complete, it).shouldBeEmpty() }
    }

    @Test
    fun `a missing source key is reported alone`() = each(complete.copy(sourceKey = null), ContractElement.SOURCE_KEY)

    @Test
    fun `a missing posting timestamp is reported alone`() =
        each(complete.copy(postingTimestamp = null), ContractElement.POSTING_TIMESTAMP)

    @Test
    fun `a missing document function hint is reported alone`() =
        each(complete.copy(documentFunctionHint = null), ContractElement.DOCUMENT_FUNCTION_HINT)

    @Test
    fun `no line is reported alone`() = each(complete.copy(lines = emptyList()), ContractElement.LINE_TAX_ATTRIBUTES)

    @Test
    fun `a line lacking a tax attribute is reported once`() {
        listOf(UpstreamLine(null, "9", "9"), UpstreamLine("SR", null, "9"), UpstreamLine("SR", "9", " ")).forEach {
            each(complete.copy(lines = listOf(it)), ContractElement.LINE_TAX_ATTRIBUTES)
        }
    }

    @Test
    fun `a missing counterparty reference is reported alone`() =
        each(complete.copy(counterpartyRef = null), ContractElement.COUNTERPARTY_REF)

    @Test
    fun `a missing amendment indicator is reported alone`() =
        each(complete.copy(amendment = null), ContractElement.AMENDMENT_INDICATOR)

    @Test
    fun `an amendment with a blank prior key is reported alone`() {
        each(complete.copy(amendment = AmendmentIndicator(true, " ")), ContractElement.AMENDMENT_INDICATOR)
        each(complete.copy(amendment = AmendmentIndicator(true, null)), ContractElement.AMENDMENT_INDICATOR)
    }

    @Test
    fun `a non-amendment carrying a prior key is reported`() =
        each(complete.copy(amendment = AmendmentIndicator(false, "K0")), ContractElement.AMENDMENT_INDICATOR)

    @Test
    fun `a valid amendment is accepted`() {
        UpstreamContractVerifier
            .verify(
                complete.copy(amendment = AmendmentIndicator(true, "K0")),
                FeedKind.FILE_FEED,
            ).shouldBeEmpty()
    }

    @Test
    fun `a missing manifest is reported only for a file feed`() {
        val e = complete.copy(batchManifest = null)
        elements(e, FeedKind.FILE_FEED) shouldBe listOf(ContractElement.BATCH_MANIFEST)
        elements(e, FeedKind.EVENT_STREAM) shouldBe emptyList()
    }

    @Test
    fun `a manifest with blank id or count below one is reported for a file feed`() {
        listOf(BatchManifest(" ", 1), BatchManifest(null, 1), BatchManifest("B", 0), BatchManifest("B", null)).forEach {
            elements(complete.copy(batchManifest = it), FeedKind.FILE_FEED) shouldBe
                listOf(ContractElement.BATCH_MANIFEST)
            elements(complete.copy(batchManifest = it), FeedKind.EVENT_STREAM) shouldBe emptyList()
        }
    }

    @Test
    fun `a whitespace-only source key is a violation like an absent one`() =
        each(complete.copy(sourceKey = "  \t"), ContractElement.SOURCE_KEY)

    @Test
    fun `an event missing everything reports every element for a file feed`() {
        val empty = UpstreamEvent(null, null, null, emptyList(), null, null, null)
        elements(empty, FeedKind.FILE_FEED) shouldBe ContractElement.entries
        elements(empty, FeedKind.EVENT_STREAM) shouldBe ContractElement.entries - ContractElement.BATCH_MANIFEST
    }

    private fun each(
        e: UpstreamEvent,
        element: ContractElement,
    ) = FeedKind.entries.forEach { elements(e, it) shouldBe listOf(element) }
}
