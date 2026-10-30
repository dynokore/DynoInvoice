package com.dynokore.gst.controlPlane.acceptance.gates

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OperationOwnersGateTest {
    private val head = "kind\tid\ttask\n"
    private val pins = head + "operation\tpurgeAuditEntries\tINV-T-3099\nchannel\tc\tINV-T-1\n"

    private fun table(
        task: String = "INV-T-3099",
        kind: String = "operation",
        id: String = "purgeAuditEntries",
    ) = "kind\tid\ttask\tstate\n$kind\t$id\t$task\tpending\nchannel\tc\tINV-T-1\tdone\n"

    private fun gate(
        owners: String?,
        table: String? = table(),
    ) = OperationOwnersGate.check(owners, table)

    private fun messages(
        owners: String?,
        table: String? = table(),
    ) = gate(owners, table).map { it.subject to it.message }

    private val missingPin = "the table row has no pin"
    private val missingRow = "the pin has no table row"

    @Test
    fun `INV-AC-12856 equal triples give no finding`() {
        gate(pins).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-12856 a trailing digit on the table side names both keys`() {
        gate(pins, table("INV-T-30990")) shouldBe
            listOf(
                Finding(
                    OperationOwnersGate.NAME,
                    "purgeAuditEntries",
                    "task INV-T-30990 in operations.tsv differs from INV-T-3099 in operation-owners.tsv",
                ),
            )
    }

    @Test
    fun `INV-AC-12856 a trailing digit on the pin side names both keys`() {
        val owners = pins.replace("INV-T-3099", "INV-T-30990")
        messages(owners) shouldBe
            listOf(
                "purgeAuditEntries" to
                    "task INV-T-3099 in operations.tsv differs from INV-T-30990 in operation-owners.tsv",
            )
    }

    @Test
    fun `INV-AC-12856 a pin task with a trailing space differs`() {
        messages(pins.replace("INV-T-3099", "INV-T-3099 ")) shouldBe
            listOf(
                "purgeAuditEntries" to
                    "task INV-T-3099 in operations.tsv differs from INV-T-3099  in operation-owners.tsv",
            )
    }

    @Test
    fun `INV-AC-12856 a table row without a pin fails`() {
        messages(head + "channel\tc\tINV-T-1\n") shouldBe listOf("purgeAuditEntries" to missingPin)
    }

    @Test
    fun `INV-AC-12856 a pin without a table row fails`() {
        messages(pins + "operation\textra\tINV-T-5\n") shouldBe listOf("extra" to missingRow)
    }

    @Test
    fun `INV-AC-12856 a pin pair listed twice fails`() {
        messages(pins + "operation\tpurgeAuditEntries\tINV-T-3099\n") shouldBe
            listOf("purgeAuditEntries" to "listed twice")
    }

    @Test
    fun `INV-AC-12856 a wrong header fails`() {
        messages(pins.replace("kind\tid\ttask\n", "kind\tid\tstate\n")).single() shouldBe
            ("header" to "expected columns [kind, id, task], found [kind, id, state]")
    }

    @Test
    fun `INV-AC-12856 a pin row with two columns or an empty cell fails`() {
        val message = "a pin row has the three columns [kind, id, task], all filled"
        messages(pins + "operation\tx\n").map { it.second } shouldBe listOf(message)
        messages(pins + "operation\tx\t\n").map { it.second } shouldBe listOf(message)
    }

    @Test
    fun `INV-AC-12856 a missing or blank pin file fails`() {
        gate(null).map { it.message } shouldBe listOf("the pin file is missing or empty")
        gate("  \n").map { it.message } shouldBe listOf("the pin file is missing or empty")
    }

    @Test
    fun `INV-AC-12856 a missing table fails`() {
        gate(pins, null).map { it.message } shouldBe listOf("the table is missing or empty")
    }

    @Test
    fun `INV-AC-12856 the look-alike id fails as no pin and no row`() {
        val owners = pins.replace("purgeAuditEntries", "PurgeAuditEntries")
        messages(owners) shouldBe listOf("purgeAuditEntries" to missingPin, "PurgeAuditEntries" to missingRow)
    }

    @Test
    fun `INV-AC-12856 the look-alike kind fails as no pin and no row`() {
        val owners = pins.replace("operation\t", "Operation\t")
        messages(owners) shouldBe listOf("purgeAuditEntries" to missingPin, "purgeAuditEntries" to missingRow)
    }

    @Test
    fun `INV-AC-12856 a pin of another kind for the same id fails as no pin and no row`() {
        val owners = pins.replace("operation\tpurgeAuditEntries", "channel\tpurgeAuditEntries")
        messages(owners) shouldBe listOf("purgeAuditEntries" to missingPin, "purgeAuditEntries" to missingRow)
    }
}
