package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** INV-Q-7449 A: the names of the hand-written sequence equal those of the `CREATE SEQUENCE` of the migration. */
class AggregateSequenceTest {
    private val migration =
        File("src/main/resources/db/migration/V2__aggregate_intake.sql")
            .readLines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")

    @Test
    fun `the DSL sequence names equal those of the CREATE SEQUENCE of V2`() {
        val created = Regex("""(?im)^\s*create\s+sequence\s+(\w+)\.(\w+)\s*;""").findAll(migration).toList()
        created.size shouldBe 1
        val (schema, name) = created.single().destructured
        JooqAggregateStore.SEQUENCE.qualifiedName
            .parts()
            .map { it.first() } shouldBe listOf(schema, name)
        JooqAggregateStore.SCHEMA shouldBe schema
        JooqAggregateStore.SEQUENCE_NAME shouldBe name
    }
}
