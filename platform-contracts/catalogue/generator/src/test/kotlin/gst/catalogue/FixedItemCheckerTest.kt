package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FixedItemCheckerTest {
    private val realFile = File("../variation-points/fixed-items.tsv")
    private val items = FixedItemChecker.load(realFile)

    private fun row(id: String, title: String) = ParsedVariationPoint(
        id = id, requirement = "INV-REQ-1", revision = "r", title = title, vpClass = VpClass.POLICY,
        classModifiers = emptyList(), scope = emptyList(), owner = "o", setPermission = "p",
        mustBeElected = false, default = null, options = emptyList(), bounds = null,
        status = VpStatus.AVAILABLE, statusNote = null, governs = emptyList(), formDependence = null,
    )

    private fun table(dir: File, vararg rows: String): File =
        File(dir, "t.tsv").also { f -> f.writeText((listOf("id\ttitle") + rows).joinToString("\n") + "\n") }

    private fun nine() = (1..9).map { "FIXED-%02d\ttitle $it".format(it) }

    @Test
    fun `INV-AC-18009 the real file loads nine items`() {
        assertEquals((1..9).map { "FIXED-%02d".format(it) }, items.map { it.id })
    }

    @Test
    fun `INV-AC-18009 the real parsed catalogue offers no fixed item`() {
        val rows = VariationPointYamlParser.parse(
            File("../variation-points/variation-points.yaml"), File("../variation-points/pending-fields.yaml"),
        )
        assertEquals(emptyList<String>(), FixedItemChecker.check(items, rows))
    }

    @Test
    fun `INV-AC-18009 a row titled exactly like a fixed item is reported`() {
        val v = FixedItemChecker.check(items, listOf(row("VP-X", "Customer-written rules or code")))
        assertEquals(1, v.size)
        assertTrue(v[0].contains("VP-X") && v[0].contains("FIXED-07"), v[0])
    }

    @Test
    fun `INV-AC-18009 a row with a fixed id is reported`() {
        val v = FixedItemChecker.check(items, listOf(row("FIXED-03", "something else")))
        assertEquals(1, v.size)
        assertTrue(v[0].contains("FIXED-03"), v[0])
    }

    @Test
    fun `INV-AC-18009 look-alike titles pass`() {
        val rows = listOf(
            row("VP-A", "customer-written rules or code in a sandbox"),
            row("VP-B", "Customer written rules"),
        )
        assertEquals(emptyList<String>(), FixedItemChecker.check(items, rows))
    }

    @Test
    fun `INV-AC-18009 load throws for eight rows`(@TempDir dir: File) {
        val e = assertThrows<IllegalStateException> { FixedItemChecker.load(table(dir, *nine().drop(1).toTypedArray())) }
        assertTrue(e.message!!.contains("t.tsv"))
    }

    @Test
    fun `INV-AC-18009 load throws for a duplicate id`(@TempDir dir: File) {
        val rows = nine().toMutableList().also { it[8] = "FIXED-01\tdup" }
        assertThrows<IllegalStateException> { FixedItemChecker.load(table(dir, *rows.toTypedArray())) }
    }

    @Test
    fun `INV-AC-18009 load throws for the id FIXED-10`(@TempDir dir: File) {
        val rows = nine().toMutableList().also { it[8] = "FIXED-10\tten" }
        assertThrows<IllegalStateException> { FixedItemChecker.load(table(dir, *rows.toTypedArray())) }
    }

    @Test
    fun `INV-AC-18009 load throws for a blank title`(@TempDir dir: File) {
        val rows = nine().toMutableList().also { it[4] = "FIXED-05\t  " }
        assertThrows<IllegalStateException> { FixedItemChecker.load(table(dir, *rows.toTypedArray())) }
    }

    @Test
    fun `INV-AC-18009 load throws for a missing file`(@TempDir dir: File) {
        val e = assertThrows<IllegalStateException> { FixedItemChecker.load(File(dir, "absent.tsv")) }
        assertTrue(e.message!!.contains("absent.tsv"))
    }

    @Test
    fun `INV-AC-18009 load throws for a wrong header`(@TempDir dir: File) {
        val f = File(dir, "h.tsv").also { it.writeText((listOf("id\tname") + nine()).joinToString("\n") + "\n") }
        assertThrows<IllegalStateException> { FixedItemChecker.load(f) }
    }
}
