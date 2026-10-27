package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.application.IntakeOutcome.Created
import com.dynokore.gst.aggregation.application.IntakeOutcome.NumberNotBuildable
import com.dynokore.gst.aggregation.application.IntakeOutcome.Replayed
import com.dynokore.gst.aggregation.application.IntakeOutcome.SourceRevisionLower
import com.dynokore.gst.aggregation.domain.AggregateType
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
}
