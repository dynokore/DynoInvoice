package com.dynokore.gst.controlPlane.application.audit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import java.util.concurrent.TimeoutException

class PurgeAuditEntriesTest {
    private val now = Instant.parse("2026-10-29T08:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val calls = ArrayList<String>()
    private val asked = ArrayList<Triple<String, String, String>>()

    private fun yearsAgo(years: Long): Instant = now.atOffset(ZoneOffset.UTC).minusYears(years).toInstant()

    private fun useCase(
        figures: () -> List<Period?> = { listOf(Period.ofYears(5), Period.ofYears(10)) },
        authority: (String) -> Boolean = { it == "audit-entry.purge" },
        throwing: Exception? = null,
    ) = PurgeAuditEntries(
        authority =
            { actor, permission, scope ->
                calls += "authority"
                asked += Triple(actor, permission, scope)
                throwing?.let { throw it }
                authority(permission)
            },
        floorSource =
            {
                calls += "floor"
                figures()
            },
        purger =
            { _, _ ->
                calls += "purger"
                AuditPurgeResult(1, "entry-1")
            },
        clock = clock,
    )

    private fun run(
        useCase: PurgeAuditEntries,
        cutOff: Instant,
    ) = useCase.execute(PurgeAuditEntriesCommand("platform.engineer", cutOff))

    @Test
    fun `INV-AC-3823 a refusal of the authority gives NotPermitted and calls no other port`() {
        run(useCase(authority = { false }), yearsAgo(11)) shouldBe PurgeAuditOutcome.NotPermitted
        calls shouldBe listOf("authority")
    }

    @Test
    fun `INV-AC-3823 an error of the authority gives NotPermitted and calls no other port`() {
        run(useCase(throwing = IllegalStateException("down")), yearsAgo(11)) shouldBe PurgeAuditOutcome.NotPermitted
        calls shouldBe listOf("authority")
    }

    @Test
    fun `INV-AC-3823 a timeout of the authority gives NotPermitted and calls no other port`() {
        run(useCase(throwing = TimeoutException("slow")), yearsAgo(11)) shouldBe PurgeAuditOutcome.NotPermitted
        calls shouldBe listOf("authority")
    }

    @Test
    fun `INV-AC-3823 the authority is asked for exactly audit-entry dot purge in the platform scope`() {
        run(useCase(), yearsAgo(11))
        asked shouldBe listOf(Triple("platform.engineer", "audit-entry.purge", "platform"))
    }

    @Test
    fun `INV-AC-3823 a stub that allows a look-alike permission string gives NotPermitted`() {
        run(useCase(authority = { it == "audit-entries.purge" }), yearsAgo(11)) shouldBe PurgeAuditOutcome.NotPermitted
        run(useCase(authority = { it == "audit-entry.purge " }), yearsAgo(11)) shouldBe PurgeAuditOutcome.NotPermitted
        calls.contains("purger") shouldBe false
    }

    @Test
    fun `INV-AC-3823 a cut-off younger than the floor gives CutOffYoungerThanFloor and no purge`() {
        run(useCase(), yearsAgo(6)) shouldBe PurgeAuditOutcome.CutOffYoungerThanFloor
        calls shouldBe listOf("authority", "floor")
    }

    @Test
    fun `INV-AC-3823 a cut-off exactly at the floor is purged`() {
        run(useCase(), yearsAgo(10)) shouldBe PurgeAuditOutcome.Purged(AuditPurgeResult(1, "entry-1"))
        calls shouldBe listOf("authority", "floor", "purger")
    }

    @Test
    fun `INV-AC-3823 a cut-off one second younger than the floor is refused`() {
        run(useCase(), yearsAgo(10).plusSeconds(1)) shouldBe PurgeAuditOutcome.CutOffYoungerThanFloor
    }

    @Test
    fun `INV-AC-3823 one region without a figure gives FloorUndeterminable and no purge`() {
        run(useCase(figures = { listOf(Period.ofYears(10), null) }), yearsAgo(11)) shouldBe
            PurgeAuditOutcome.FloorUndeterminable
        calls.contains("purger") shouldBe false
    }

    @Test
    fun `INV-AC-3823 one unreachable region gives FloorUndeterminable and no purge`() {
        run(useCase(figures = { throw IllegalStateException("unreachable") }), yearsAgo(11)) shouldBe
            PurgeAuditOutcome.FloorUndeterminable
        calls.contains("purger") shouldBe false
    }

    @Test
    fun `INV-AC-3823 an empty region list gives FloorUndeterminable and no purge`() {
        run(useCase(figures = { emptyList() }), yearsAgo(11)) shouldBe PurgeAuditOutcome.FloorUndeterminable
        calls.contains("purger") shouldBe false
    }

    @Test
    fun `INV-AC-3823 a maximum under 5 years gives a floor of 5 years`() {
        val short = { listOf<Period?>(Period.ofYears(2), Period.ofYears(3)) }
        run(useCase(figures = short), yearsAgo(4)) shouldBe PurgeAuditOutcome.CutOffYoungerThanFloor
        run(useCase(figures = short), yearsAgo(5)) shouldBe PurgeAuditOutcome.Purged(AuditPurgeResult(1, "entry-1"))
    }

    @Test
    fun `INV-AC-3823 an exception of the purger propagates unchanged and is not mapped to an outcome`() {
        val failure = IllegalStateException("purge failed")
        val failing =
            PurgeAuditEntries(
                authority = { _, _, _ ->
                    calls += "authority"
                    true
                },
                floorSource = {
                    calls += "floor"
                    listOf(Period.ofYears(5))
                },
                purger = { _, _ ->
                    calls += "purger"
                    throw failure
                },
                clock = clock,
            )
        val thrown = runCatching { run(failing, yearsAgo(6)) }.exceptionOrNull()
        (thrown === failure) shouldBe true
        calls shouldBe listOf("authority", "floor", "purger")
    }
}
