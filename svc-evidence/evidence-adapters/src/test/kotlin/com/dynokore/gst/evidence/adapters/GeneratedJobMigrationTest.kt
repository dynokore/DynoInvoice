package com.dynokore.gst.evidence.adapters

import com.dynokore.platform.serviceruntime.jobs.JobSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * INV-Q-12992 A (TPL-12): the job tables migration is exactly what the pinned service-runtime generates for the
 * evidence schema. After its leading `--` lines the file equals, by trimmed text, `JobSchema.ddl`; an upgrade that
 * changes the generated text, or one changed character in the file, fails here until the file is regenerated.
 */
class GeneratedJobMigrationTest {
    private val migration = "V10__evidence_job_tables.sql"

    private fun text(): String =
        checkNotNull(javaClass.getResource("/db/migration/$migration")) { "no migration $migration" }.readText()

    private fun body(text: String): String =
        text
            .lines()
            .dropWhile { it.startsWith("--") }
            .joinToString("\n")
            .trim()

    @Test
    fun `the job tables migration is the DDL service-runtime generates for the evidence schema`() {
        body(text()) shouldBe JobSchema.ddl(EvidenceDb.SCHEMA, EvidenceDb.RUNTIME, EvidenceDb.SYS).trim()
    }

    @Test
    fun `the leading comment names the generator call and the BOM version`() {
        val generated = text().lines().takeWhile { it.startsWith("--") }.filter { it.startsWith("-- Generated:") }
        generated.size shouldBe 1
        generated.single().contains("""JobSchema.ddl("evidence", "evidence_runtime", "evidence_sys")""") shouldBe true
        generated.single().contains("platform-libs-bom 0.137.0") shouldBe true
    }

    @Test
    fun `one changed character in the migration is not the generated DDL`() {
        val changed = body(text()).replaceFirst("create table", "create tablf")
        (changed == JobSchema.ddl(EvidenceDb.SCHEMA, EvidenceDb.RUNTIME, EvidenceDb.SYS).trim()) shouldBe false
    }
}
