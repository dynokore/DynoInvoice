package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.application.IntakeOutcome.Created
import com.dynokore.gst.aggregation.application.IntakeOutcome.CrossesPeriodBoundary
import com.dynokore.gst.aggregation.application.IntakeOutcome.NumberNotBuildable
import com.dynokore.gst.aggregation.application.IntakeOutcome.Replayed
import com.dynokore.gst.aggregation.application.IntakeOutcome.SourceRevisionLower
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.Cadence.MONTHLY
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class IntakeAggregateTest {
    private class Codes(
        var entity: String? = "E",
        var unit: String? = "B",
    ) : ShortCodes {
        val calls = mutableListOf<String>()

        override fun entityCode(entityRef: String): String? = entity.also { calls += "entity" }

        override fun businessUnitCode(businessUnitRef: String): String? = unit.also { calls += "unit" }
    }

    private class Repo : AggregateRepository {
        val rows = mutableListOf<StoredAggregate>()
        val calls = mutableListOf<String>()
        var sequence = 0L
        var raceWinner: StoredAggregate? = null

        override fun findByIntakeKey(
            entityRef: String,
            sourceKey: String,
            sourceRevision: Long,
        ): StoredAggregate? {
            calls += "find"
            return rows.firstOrNull {
                it.aggregate.entityRef == entityRef &&
                    it.aggregate.sourceKey == sourceKey &&
                    it.aggregate.sourceRevision == sourceRevision
            }
        }

        override fun highestRevision(
            entityRef: String,
            sourceKey: String,
        ): Long? {
            calls += "highest"
            return rows
                .filter { it.aggregate.entityRef == entityRef && it.aggregate.sourceKey == sourceKey }
                .maxOfOrNull { it.aggregate.sourceRevision }
        }

        override fun nextSequence(): Long {
            calls += "sequence"
            return ++sequence
        }

        override fun store(aggregate: NewAggregate): StoredAggregate {
            calls += "store"
            raceWinner?.let {
                rows += it
                throw DuplicateIntakeKey()
            }
            return StoredAggregate(aggregate, UUID.randomUUID()).also { rows += it }
        }
    }

    private val codes = Codes()
    private val repo = Repo()
    private val intake = IntakeAggregate(codes, repo)
    private val end = LocalDate.of(2027, 3, 31)

    private fun command(
        entity: String = "ent-1",
        key: String = "K",
        revision: Long = 1,
    ) = IntakeCommand(entity, "bu-1", AggregateType.POS, LocalDate.of(2027, 3, 1), end, key, revision)

    @Test
    fun `INV-AC-1436 a first intake is numbered and dated at its period end`() {
        val created = intake.execute(command()) as Created
        created.aggregate.aggregate.documentNumber shouldBe "AGG-E-POS-B-20270331-1"
        created.aggregate.aggregate.issueDate shouldBe end
        created.aggregate.aggregate.transactionDate shouldBe end
        created.aggregate.aggregate.sequence shouldBe 1
        repo.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-453 a retry of the same triple replays the stored aggregate and reads nothing else`() {
        val first = intake.execute(command()) as Created
        codes.calls.clear()
        repo.calls.clear()
        codes.entity = "OTHER"
        intake.execute(command()) shouldBe Replayed(first.aggregate)
        codes.calls shouldBe emptyList()
        repo.calls shouldBe listOf("find")
        repo.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-453 the same sourceKey under another entity is not a replay`() {
        intake.execute(command(entity = "ent-1"))
        val second = intake.execute(command(entity = "ent-2"))
        (second is Created) shouldBe true
        repo.rows.size shouldBe 2
    }

    @Test
    fun `INV-AC-453 a lower revision is refused, an equal revision is a replay and a higher one is new`() {
        intake.execute(command(revision = 5))
        repo.calls.clear()
        intake.execute(command(revision = 4)) shouldBe SourceRevisionLower(5)
        repo.calls shouldBe listOf("find", "highest")
        repo.rows.size shouldBe 1
        (intake.execute(command(revision = 5)) is Replayed) shouldBe true
        (intake.execute(command(revision = 6)) is Created) shouldBe true
        repo.rows.size shouldBe 2
    }

    @Test
    fun `INV-AC-453 a highest revision equal to the incoming one is not lower`() {
        val equalHighest =
            object : AggregateRepository by repo {
                override fun findByIntakeKey(
                    entityRef: String,
                    sourceKey: String,
                    sourceRevision: Long,
                ): StoredAggregate? = null

                override fun highestRevision(
                    entityRef: String,
                    sourceKey: String,
                ): Long? = 5
            }
        (IntakeAggregate(codes, equalHighest).execute(command(revision = 5)) is Created) shouldBe true
    }

    @Test
    fun `INV-AC-1436 an entity code that is null, lower case or too long is not buildable`() {
        for (bad in listOf(null, "", "a1", "ABCDEFGHIJK", "A-1", "A1\n")) {
            codes.entity = bad
            intake.execute(command(key = "K$bad")) shouldBe NumberNotBuildable(NotBuildableReason.ENTITY_CODE)
        }
        repo.calls.contains("sequence") shouldBe false
        repo.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-1436 a business unit code that is null, lower case or too long is not buildable`() {
        for (bad in listOf(null, "", "a1", "ABCDEFGHIJK")) {
            codes.unit = bad
            intake.execute(command(key = "K$bad")) shouldBe
                NumberNotBuildable(NotBuildableReason.BUSINESS_UNIT_CODE)
        }
        codes.entity = null
        codes.unit = null
        intake.execute(command()) shouldBe NumberNotBuildable(NotBuildableReason.ENTITY_CODE)
        repo.calls.contains("sequence") shouldBe false
        repo.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-1436 codes of 10 characters are buildable`() {
        codes.entity = "ABCDEFGHI0"
        codes.unit = "0123456789"
        (intake.execute(command()) is Created) shouldBe true
    }

    @Test
    fun `INV-AC-453 the codes are read after the lookup and before the sequence`() {
        val order = mutableListOf<String>()
        val spyCodes =
            object : ShortCodes {
                override fun entityCode(entityRef: String): String? = "E".also { order += "codes" }

                override fun businessUnitCode(businessUnitRef: String): String? = "B"
            }
        val spyRepo =
            object : AggregateRepository by repo {
                override fun findByIntakeKey(
                    entityRef: String,
                    sourceKey: String,
                    sourceRevision: Long,
                ): StoredAggregate? =
                    repo.findByIntakeKey(entityRef, sourceKey, sourceRevision).also { order += "find" }

                override fun nextSequence(): Long = repo.nextSequence().also { order += "sequence" }
            }
        IntakeAggregate(spyCodes, spyRepo).execute(command())
        order shouldBe listOf("find", "codes", "sequence")
    }

    @Test
    fun `INV-AC-453 a duplicate key on store is answered with the aggregate that won`() {
        val winner =
            StoredAggregate(
                NewAggregate(
                    entityRef = "ent-1",
                    businessUnitRef = "bu-1",
                    aggregateType = AggregateType.POS,
                    periodStart = LocalDate.of(2027, 3, 1),
                    periodEnd = end,
                    sourceKey = "K",
                    sourceRevision = 1,
                    sequence = 9,
                    entityCode = "E",
                    businessUnitCode = "B",
                    documentNumber = "AGG-E-POS-B-20270331-9",
                    issueDate = end,
                    transactionDate = end,
                ),
                UUID.randomUUID(),
            )
        repo.raceWinner = winner
        intake.execute(command()) shouldBe Replayed(winner)
    }

    @Test
    fun `INV-AC-2779 a replay keeps the original codes and number after the short codes changed`() {
        val first = intake.execute(command()) as Created
        codes.entity = "X"
        codes.unit = "Y"
        val replay = intake.execute(command()) as Replayed
        replay.aggregate.aggregate.documentNumber shouldBe first.aggregate.aggregate.documentNumber
        replay.aggregate.aggregate.entityCode shouldBe "E"
        replay.aggregate.aggregate.businessUnitCode shouldBe "B"
    }

    @Test
    fun `INV-AC-1436 the stored codes are the ones the number was built from`() {
        val created = intake.execute(command()) as Created
        created.aggregate.aggregate.entityCode shouldBe "E"
        created.aggregate.aggregate.businessUnitCode shouldBe "B"
        repo.calls.count { it == "sequence" } shouldBe 1
        repo.calls.count { it == "store" } shouldBe 1
    }

    private class Windows(
        val state: com.dynokore.gst.aggregation.domain.WindowState,
    ) : WindowRepository {
        val placed = mutableListOf<LocalDate>()

        override fun place(
            entityRef: String,
            businessUnitRef: String,
            aggregateType: AggregateType,
            date: LocalDate,
            cadence: com.dynokore.gst.aggregation.domain.Cadence,
        ): Placement {
            placed += date
            return Placement(
                com.dynokore.gst.aggregation.domain.AggregationWindow
                    .containing(cadence, date),
                state,
            )
        }

        override fun isPartClosed(
            window: OpenWindow,
            part: com.dynokore.gst.aggregation.domain.WindowPart,
        ) = false

        override fun openWindowsEndingOnOrBefore(date: LocalDate) = emptyList<OpenWindow>()

        override fun closePart(
            window: OpenWindow,
            part: com.dynokore.gst.aggregation.domain.WindowPart,
            aggregateId: UUID,
        ) = Unit

        override fun recordLate(window: OpenWindow) = Unit
    }

    @Test
    fun `without a window repository the aggregate carries no window`() {
        (intake.execute(command()) as Created).aggregate.aggregate.let {
            it.windowStart shouldBe null
            it.windowEnd shouldBe null
            it.windowState shouldBe null
        }
    }

    @Test
    fun `with a window repository a created intake is placed in the window of its period end`() {
        val windows = Windows(com.dynokore.gst.aggregation.domain.WindowState.LATE)
        val placing = IntakeAggregate(codes, repo, windows, cadences = { _, _, _ -> MONTHLY })
        (placing.execute(command()) as Created).aggregate.aggregate.let {
            it.windowStart shouldBe LocalDate.of(2027, 3, 1)
            it.windowEnd shouldBe end
            it.windowState shouldBe com.dynokore.gst.aggregation.domain.WindowState.LATE
        }
        windows.placed shouldBe listOf(end)
    }

    @Test
    fun `a replay with a window repository returns the stored placement and places nothing again`() {
        val windows = Windows(com.dynokore.gst.aggregation.domain.WindowState.OPEN)
        val placing = IntakeAggregate(codes, repo, windows, cadences = { _, _, _ -> MONTHLY })
        val first = placing.execute(command()) as Created
        val replay = placing.execute(command()) as Replayed
        replay.aggregate shouldBe first.aggregate
        windows.placed.size shouldBe 1
    }

    @Test
    fun `windows set and cadences null is refused`() {
        shouldThrow<IllegalArgumentException> { IntakeAggregate(codes, repo, Windows(WindowState.OPEN), null) }
    }

    @Test
    fun `cadences set and windows null is refused`() {
        shouldThrow<IllegalArgumentException> {
            IntakeAggregate(
                codes,
                repo,
                null,
                CadenceSource {
                    _,
                    _,
                    _,
                    ->
                    MONTHLY
                },
            )
        }
    }

    @Test
    fun `the placement uses the cadence that cadences answers`() {
        val placing = IntakeAggregate(codes, repo, Windows(WindowState.OPEN), cadences = { _, _, _ -> Cadence.WEEKLY })
        (placing.execute(command()) as Created).aggregate.aggregate.let {
            it.windowStart shouldBe LocalDate.of(2027, 3, 29)
            it.windowEnd shouldBe LocalDate.of(2027, 4, 4)
        }
    }

    private val q1 = PeriodSpan(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31), "2027-Q1")
    private val q2 = PeriodSpan(LocalDate.of(2027, 4, 1), LocalDate.of(2027, 6, 30), "2027-Q2")

    private fun withPeriods(
        spans: PeriodSpans,
        asked: MutableList<Triple<String, LocalDate, LocalDate>> = mutableListOf(),
    ) = IntakeAggregate(codes, repo, periods = { entity, from, to ->
        asked += Triple(entity, from, to)
        spans
    })

    private fun window(
        start: LocalDate,
        end: LocalDate,
        revision: Long = 1,
    ) = IntakeCommand("ent-1", "bu-1", AggregateType.POS, start, end, "K", revision)

    @Test
    fun `ING-018 a call from 28 Mar to 3 Apr across two quarter spans is refused and stores nothing`() {
        val checked = withPeriods(PeriodSpans.Known(listOf(q1, q2)))
        checked.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3))) shouldBe CrossesPeriodBoundary
        repo.rows.size shouldBe 0
        repo.calls.contains("store") shouldBe false
        codes.calls shouldBe emptyList()
    }

    @Test
    fun `ING-018 the same call inside one span is accepted`() {
        val checked = withPeriods(PeriodSpans.Known(listOf(q1)))
        (checked.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 3, 31))) is Created) shouldBe true
        repo.rows.size shouldBe 1
    }

    @Test
    fun `ING-018 a call whose end lies in no span is refused, as is one whose start lies in no span`() {
        val checked = withPeriods(PeriodSpans.Known(listOf(q1)))
        checked.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3))) shouldBe CrossesPeriodBoundary
        checked.execute(window(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2))) shouldBe CrossesPeriodBoundary
        repo.rows.size shouldBe 0
    }

    @Test
    fun `ING-018 known but empty spans leave both dates in no span and refuse`() {
        withPeriods(PeriodSpans.Known(emptyList()))
            .execute(window(LocalDate.of(2027, 3, 1), end)) shouldBe CrossesPeriodBoundary
    }

    @Test
    fun `ING-018 unknown spans are accepted`() {
        val checked = withPeriods(PeriodSpans.Unknown)
        (checked.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3))) is Created) shouldBe true
    }

    @Test
    fun `ING-018 a replay of a stored aggregate is answered first and the spans are not asked`() {
        (intake.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3))) is Created) shouldBe true
        val asked = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        val checked = withPeriods(PeriodSpans.Known(listOf(q1, q2)), asked)
        (checked.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3))) is Replayed) shouldBe true
        asked shouldBe emptyList()
    }

    @Test
    fun `ING-018 a lower revision is answered ING-002 before the spans are asked`() {
        intake.execute(window(LocalDate.of(2027, 3, 1), end, revision = 5))
        val asked = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        val checked = withPeriods(PeriodSpans.Known(listOf(q1, q2)), asked)
        checked.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3), revision = 4)) shouldBe
            SourceRevisionLower(5)
        asked shouldBe emptyList()
    }

    @Test
    fun `ING-018 the source is asked once for the entity and the two dates, before the short codes`() {
        val asked = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        val checked = withPeriods(PeriodSpans.Known(listOf(q1)), asked)
        checked.execute(window(LocalDate.of(2027, 3, 1), end))
        asked shouldBe listOf(Triple("ent-1", LocalDate.of(2027, 3, 1), end))
    }

    @Test
    fun `ING-018 a crossing call with a null source is accepted as before`() {
        (intake.execute(window(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3))) is Created) shouldBe true
    }
}
