package com.dynokore.gst.consoleBff.domain.deferral

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DeferralTest {
    private fun deferral(
        entityRef: String = "e1",
        ref: String = "d1",
        by: String = "u1",
    ) = Deferral(
        UUID.randomUUID(),
        entityRef,
        DeferralTarget(DeferralKind.DOCUMENT, ref),
        DeferralState.DEFERRED,
        by,
        Instant.parse("2026-11-14T00:00:00Z"),
    )

    @Test
    fun `INV-AC-21154 entityRef of 40 characters is accepted and 41 or 0 is rejected`() {
        deferral(entityRef = "a".repeat(40)).entityRef.length shouldBe 40
        shouldThrow<IllegalArgumentException> { deferral(entityRef = "a".repeat(41)) }
        shouldThrow<IllegalArgumentException> { deferral(entityRef = "") }
    }

    @Test
    fun `INV-AC-21154 target ref of 1 and 128 characters is accepted and 0 or 129 is rejected`() {
        DeferralTarget(DeferralKind.GROUP, "a").ref.length shouldBe 1
        DeferralTarget(DeferralKind.GROUP, "a".repeat(128)).ref.length shouldBe 128
        shouldThrow<IllegalArgumentException> { DeferralTarget(DeferralKind.GROUP, "") }
        shouldThrow<IllegalArgumentException> { DeferralTarget(DeferralKind.GROUP, "a".repeat(129)) }
    }

    @Test
    fun `INV-AC-21154 deferredBy blank or 129 characters is rejected and 128 is accepted`() {
        deferral(by = "u".repeat(128)).deferredBy.length shouldBe 128
        shouldThrow<IllegalArgumentException> { deferral(by = "") }
        shouldThrow<IllegalArgumentException> { deferral(by = "   ") }
        shouldThrow<IllegalArgumentException> { deferral(by = "u".repeat(129)) }
    }

    @Test
    fun `INV-AC-21154 kind and state have exactly the contract members`() {
        DeferralKind.entries.map { it.name } shouldBe listOf("DOCUMENT", "GROUP")
        DeferralState.entries.map { it.name } shouldBe listOf("DEFERRED", "UNDONE")
    }
}
