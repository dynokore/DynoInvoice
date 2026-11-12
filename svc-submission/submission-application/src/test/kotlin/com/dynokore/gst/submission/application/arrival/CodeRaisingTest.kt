package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.application.queue.CatalogueEntry
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CodeRaisingTest {
    private val now = Instant.parse("2026-11-12T03:00:00Z")
    private val t1 = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<Triple<TenantId, RaisedCode, Instant>>()
    private val staged = mutableListOf<RaisedCode>()
    private val seen = mutableSetOf<List<Any>>()
    private var stageFails = false

    private val catalogue =
        ErrorCatalogue {
            when (it) {
                "SUB-010" -> CatalogueEntry(it, "Tax operations", "MANUAL")
                "PER-007" -> CatalogueEntry(it, "Tax operations", null)
                else -> null
            }
        }
    private val ports =
        object : RaisedCodes {
            override fun append(
                tenant: TenantId,
                raised: RaisedCode,
                raisedAt: Instant,
            ): Boolean {
                appended += Triple(tenant, raised, raisedAt)
                return seen.add(listOf(tenant, raised.docUuid, raised.version, raised.code))
            }
        }
    private val signals =
        object : CodeSignals {
            override fun stage(raised: RaisedCode) {
                if (stageFails) error("outbox down")
                staged += raised
            }
        }
    private val sut = CodeRaising(catalogue, ports, signals, Clock.fixed(now, ZoneOffset.UTC))

    private fun raise(
        version: Int,
        codes: List<String>,
        period: String = "2026-Q1",
    ) = sut.raise(t1, "D1", version, "E-001", period, codes)

    @Test
    fun `INV-AC-21086 first call appends and stages in order`() {
        raise(1, listOf("SUB-010", "PER-007")) shouldBe 2
        appended.map { it.second.code } shouldBe listOf("SUB-010", "PER-007")
        appended.map { it.third }.toSet() shouldBe setOf(now)
        staged.map { Triple(it.code, it.remediationOwner, it.disposition) } shouldBe
            listOf(Triple("SUB-010", "Tax operations", "MANUAL"), Triple("PER-007", "Tax operations", null))
    }

    @Test
    fun `INV-AC-21086 repeat attempts both appends and stages nothing`() {
        raise(1, listOf("SUB-010", "PER-007"))
        staged.clear()
        appended.clear()
        raise(1, listOf("SUB-010", "PER-007")) shouldBe 0
        appended.size shouldBe 2
        staged.size shouldBe 0
    }

    @Test
    fun `INV-AC-21086 unknown code stages null members for a new version`() {
        raise(1, listOf("SUB-010", "PER-007"))
        staged.clear()
        raise(2, listOf("X-999")) shouldBe 1
        staged.single().let {
            it.remediationOwner shouldBe null
            it.disposition shouldBe null
            it.version shouldBe 2
        }
    }

    @Test
    fun `INV-AC-21086 blank periodRef throws and calls no port`() {
        shouldThrow<IllegalArgumentException> { raise(1, listOf("SUB-010"), " ") }
        appended.size shouldBe 0
        staged.size shouldBe 0
    }

    @Test
    fun `INV-AC-21086 empty list calls no port and returns 0`() {
        raise(1, emptyList()) shouldBe 0
        appended.size shouldBe 0
        staged.size shouldBe 0
    }

    @Test
    fun `codes are processed in the given order`() {
        raise(1, listOf("PER-007", "X-999", "SUB-010")) shouldBe 3
        staged.map { it.code } shouldBe listOf("PER-007", "X-999", "SUB-010")
    }

    @Test
    fun `a throw of the signal port after the append propagates`() {
        stageFails = true
        shouldThrow<IllegalStateException> { raise(1, listOf("SUB-010")) }
        appended.size shouldBe 1
    }
}
