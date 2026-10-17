package com.dynokore.platform.adapterruntime

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Consumer-driven contract test of the module API, written against `-api` types only the way an
 * ingestion-svc adapter runs both verifiers in its contract and mapping tests (INV-AC-1853, INV-AC-2032).
 * ingestion-svc does not exist yet: the stub plays its role.
 */
class ModuleApiConsumerContractTest {
    private class AdapterStub {
        fun emit(tx: SourceTransaction) =
            UpstreamEvent(
                tx.sourceKey,
                Instant.EPOCH,
                "INVOICE",
                tx.lineTaxCodes.map { UpstreamLine(it, "0", "0") },
                "P1",
                AmendmentIndicator(false, null),
                BatchManifest("B1", 1),
            )
    }

    private val source = listOf(SourceTransaction("A", listOf("ZP")), SourceTransaction("B", listOf("NA", "SR")))
    private val emitted = source.map(AdapterStub()::emit)

    @Test
    fun `UpstreamContractVerifier accepts the stub adapter's events on a file feed`() {
        emitted.forEach { UpstreamContractVerifier.verify(it, FeedKind.FILE_FEED).shouldBeEmpty() }
    }

    @Test
    fun `TaxPassThroughVerifier accepts the stub adapter's pass-through and flags a drop`() {
        TaxPassThroughVerifier.verify(source, emitted).shouldBeEmpty()
        TaxPassThroughVerifier.verify(source, emitted.drop(1)).map { it.kind } shouldBe
            listOf(PassThroughViolationKind.DROPPED)
    }
}
