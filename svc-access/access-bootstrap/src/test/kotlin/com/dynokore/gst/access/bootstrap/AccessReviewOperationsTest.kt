package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** INV-T-15798: the operation table names this task and `done` for the two access-review operations, by equality. */
class AccessReviewOperationsTest {
    private val table: List<String> get() = File("../operations.tsv").readLines()

    private fun findings(lines: List<String>): List<String> {
        val rows = OperationsGate.parse(lines, mutableListOf()).associateBy { it.kind to it.name }
        return listOf("runAccessReview", "closeAccessReview").flatMap { name ->
            val row = rows["operation" to name]
            listOfNotNull(
                if (row?.task == "INV-T-15798") null else "operation $name: task is ${row?.task}, not INV-T-15798",
                if (row?.state == "done") null else "operation $name: state is ${row?.state}, not done",
            )
        }
    }

    @Test
    fun `the rows runAccessReview and closeAccessReview carry INV-T-15798 and done`() {
        assertThat(findings(table)).isEmpty()
    }

    @Test
    fun `a row with another task, a look-alike task, an open state or no row fails`() {
        listOf("INV-T-4941", "UNASSIGNED", "INV-T-157980", "inv-t-15798", "INV-T-15798 ").forEach { other ->
            val changed = table.map { it.replace("\trunAccessReview\tINV-T-15798\t", "\trunAccessReview\t$other\t") }
            assertThat(findings(changed)).containsExactly("operation runAccessReview: task is $other, not INV-T-15798")
        }
        val open =
            table.map {
                it.replace(
                    "\tcloseAccessReview\tINV-T-15798\tdone",
                    "\tcloseAccessReview\tINV-T-15798\topen",
                )
            }
        assertThat(findings(open)).containsExactly("operation closeAccessReview: state is open, not done")
        assertThat(findings(table.filterNot { it.startsWith("operation\trunAccessReview\t") })).hasSize(2)
    }
}
