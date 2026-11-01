package com.dynokore.gst.controlPlane.application.audit

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeoutException

class PurgeAuditEntriesTest {
    private val now = Instant.parse("2026-10-29T08:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val calls = ArrayList<String>()
    private val purged = ArrayList<Triple<Instant, String, RequestContext>>()
    private val ctx = RequestContext("corr-1", "key-1")
    private val asked = ArrayList<Triple<String, String, String>>()
    private val fixedId = UUID.fromString("00000000-0000-0000-0000-000000000042")
    private val idCalls = ArrayList<String>()
    private val idSource =
        AuditPurgeIdSource {
            calls += "id"
            idCalls += "id"
            fixedId
        }

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
            { _, cutOff, reason, context ->
                calls += "purger"
                purged += Triple(cutOff, reason, context)
                AuditPurgeResult(1, "entry-1")
            },
        clock = clock,
        idSource = idSource,
    )

    private fun run(
        useCase: PurgeAuditEntries,
        cutOff: Instant,
        reason: String? = null,
        context: RequestContext = ctx,
    ) = useCase.execute(PurgeAuditEntriesCommand("platform.engineer", cutOff, reason, context))

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
        run(useCase(), yearsAgo(10)) shouldBe
            PurgeAuditOutcome.Purged(AuditPurgeResult(1, "entry-1"), fixedId, Period.ofYears(10))
        calls shouldBe listOf("authority", "floor", "purger", "id")
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
        run(useCase(figures = short), yearsAgo(5)) shouldBe
            PurgeAuditOutcome.Purged(AuditPurgeResult(1, "entry-1"), fixedId, Period.ofYears(5))
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
                purger = { _, _, _, _ ->
                    calls += "purger"
                    throw failure
                },
                clock = clock,
                idSource = idSource,
            )
        val thrown = runCatching { run(failing, yearsAgo(6)) }.exceptionOrNull()
        (thrown === failure) shouldBe true
        calls shouldBe listOf("authority", "floor", "purger")
        idCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-3823 the reason text of the request is passed to the purger`() {
        run(useCase(), yearsAgo(11), reason = "annual clean-up")
        purged.map { it.second } shouldBe listOf("annual clean-up")
    }

    @Test
    fun `INV-AC-3823 a missing or blank reason gives the Idempotency-Key as the reason`() {
        listOf(null, "", "   ").forEach { run(useCase(), yearsAgo(11), reason = it) }
        purged.map { it.second } shouldBe listOf("key-1", "key-1", "key-1")
    }

    @Test
    fun `INV-AC-3823 the request context reaches the purger unchanged`() {
        run(useCase(), yearsAgo(11))
        (purged.single().third === ctx) shouldBe true
    }

    @Test
    fun `INV-AC-3823 the cut-off reaches the purger unchanged to the nanosecond`() {
        val cutOff = yearsAgo(11).plusNanos(123_456_789)
        run(useCase(), cutOff)
        purged.single().first shouldBe cutOff
    }

    @Test
    fun `INV-AC-3823 a purge answers the floor used, the purge id of the id source and the result of the purger`() {
        val figures = { listOf<Period?>(Period.ofYears(6), Period.ofYears(2)) }
        run(useCase(figures = figures), yearsAgo(7)) shouldBe
            PurgeAuditOutcome.Purged(AuditPurgeResult(1, "entry-1"), fixedId, Period.ofYears(6))
    }

    @Test
    fun `INV-AC-3823 the purge id is drawn once and only after the purger returned`() {
        run(useCase(), yearsAgo(11))
        calls shouldBe listOf("authority", "floor", "purger", "id")
        idCalls.size shouldBe 1
    }

    @Test
    fun `INV-AC-3823 a purger that throws draws no purge id and the exception propagates`() {
        val failure = IllegalStateException("purge failed")
        val failing =
            PurgeAuditEntries(
                authority = { _, _, _ -> true },
                floorSource = { listOf(Period.ofYears(5)) },
                purger = { _, _, _, _ -> throw failure },
                clock = clock,
                idSource = idSource,
            )
        val thrown = runCatching { run(failing, yearsAgo(6)) }.exceptionOrNull()
        (thrown === failure) shouldBe true
        idCalls.size shouldBe 0
    }

    @Test
    fun `INV-AC-3823 a refused call draws no purge id`() {
        run(useCase(authority = { false }), yearsAgo(11)) shouldBe PurgeAuditOutcome.NotPermitted
        idCalls.size shouldBe 0
        run(useCase(), yearsAgo(6)) shouldBe PurgeAuditOutcome.CutOffYoungerThanFloor
        idCalls.size shouldBe 0
        run(useCase(figures = { listOf(null) }), yearsAgo(11)) shouldBe PurgeAuditOutcome.FloorUndeterminable
        idCalls.size shouldBe 0
    }

    @Test
    fun `INV-AC-3823 the default id source gives a different purge id on each call`() {
        val useCase =
            PurgeAuditEntries(
                { _, _, _ -> true },
                { listOf(Period.ofYears(5)) },
                { _, _, _, _ -> AuditPurgeResult(1, "entry-1") },
                clock,
            )
        val first = run(useCase, yearsAgo(6)) as PurgeAuditOutcome.Purged
        val second = run(useCase, yearsAgo(6)) as PurgeAuditOutcome.Purged
        (first.purgeId != second.purgeId) shouldBe true
    }
}
