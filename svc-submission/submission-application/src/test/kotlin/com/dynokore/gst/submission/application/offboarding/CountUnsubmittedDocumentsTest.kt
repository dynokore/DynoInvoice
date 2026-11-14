package com.dynokore.gst.submission.application.offboarding

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CountUnsubmittedDocumentsTest {
    private val now = Instant.parse("2026-11-15T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenant = TenantId(UUID.randomUUID())

    @Test
    fun `INV-AC-22870 the source receives exactly the tenant and entity passed in, once`() {
        val calls = mutableListOf<Pair<TenantId, String?>>()
        val useCase =
            CountUnsubmittedDocuments({ t, e ->
                calls += t to e
                3
            }, clock)
        useCase.execute(tenant, "E1")
        calls shouldBe listOf(tenant to "E1")
        calls.clear()
        useCase.execute(tenant, null)
        calls shouldBe listOf(tenant to null)
    }

    @Test
    fun `INV-AC-22870 countedAt equals the clock instant and the count equals the source answer`() {
        val result = CountUnsubmittedDocuments({ _, _ -> 5 }, clock).execute(tenant, null)
        result.countedAt shouldBe now
        result.unsubmittedDocumentCount shouldBe 5
        CountUnsubmittedDocuments({ _, _ -> 0 }, clock).execute(tenant, null).unsubmittedDocumentCount shouldBe 0
    }

    @Test
    fun `INV-AC-22870 a throwing source makes execute throw the same exception`() {
        val boom = IllegalStateException("read failed")
        val useCase = CountUnsubmittedDocuments({ _, _ -> throw boom }, clock)
        val thrown = assertThrows<IllegalStateException> { useCase.execute(tenant, null) }
        (thrown === boom) shouldBe true
    }
}
