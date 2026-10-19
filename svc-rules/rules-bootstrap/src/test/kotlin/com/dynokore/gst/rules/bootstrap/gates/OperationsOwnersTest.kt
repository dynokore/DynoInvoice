package com.dynokore.gst.rules.bootstrap.gates

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of [OperationOwners]; the repository's own table is checked by [OperationsGate]. */
class OperationsOwnersTest {
    private fun findings(table: String?) = OperationOwners.findings(table)

    private val table = "kind\tid\ttask\n" + OperationOwners.expected.joinToString("\n") + "\n"

    @Test
    fun `the expected table passes`() {
        findings(table).shouldBeEmpty()
    }

    @Test
    fun `activateMappingPack with INV-T-4977 fails`() {
        findings(table.replace("activateMappingPack\tINV-T-4972", "activateMappingPack\tINV-T-4977"))
            .joinToString() shouldContain "activateMappingPack"
    }

    @Test
    fun `dryRun with the look-alike INV-T-49740 fails`() {
        findings(table.replace("dryRun\tINV-T-4974", "dryRun\tINV-T-49740")).joinToString() shouldContain "dryRun"
    }

    @Test
    fun `a twentieth operation row fails`() {
        findings(table + "operation\textraThing\tINV-T-4966\n").joinToString() shouldContain "extraThing"
    }

    @Test
    fun `the example channel with a task other than none fails`() {
        findings(table.replace("example-happened.v1\tnone", "example-happened.v1\tINV-T-4976"))
            .joinToString() shouldContain "example-happened"
    }

    @Test
    fun `a missing row and a missing table fail`() {
        findings(table.replace("operation\tgetDryRun\tINV-T-4974\n", "")).joinToString() shouldContain "getDryRun"
        findings(null).joinToString() shouldContain "missing"
    }
}
