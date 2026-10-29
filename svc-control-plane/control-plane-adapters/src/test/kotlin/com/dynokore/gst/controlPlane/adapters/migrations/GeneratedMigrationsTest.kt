package com.dynokore.gst.controlPlane.adapters.migrations

import com.dynokore.platform.auditlog.AuditSchema
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * The migrations that hold platform-generated DDL are exactly what the pinned platform modules generate for the
 * control-plane schema (TPL-12): an upgrade that changes the text fails here until the file is regenerated.
 */
class GeneratedMigrationsTest {
    private val directory: Path = Path.of(System.getProperty("gst.migrations.dir"))

    private fun body(text: String): String =
        text
            .lines()
            .dropWhile { it.startsWith("--") }
            .joinToString("\n")
            .trim()

    /**
     * The generator writes the ten blank code points as \uXXXX; the V files of the old generator hold them raw, and
     * Flyway drops a raw U+FEFF on read (INV-T-10836, INV-Q-12241). The forward migration V202610281000 restores it.
     */
    private fun escaped(text: String): String =
        buildString {
            text.forEach { c ->
                if (c.code in
                    BLANK_CODE_POINTS
                ) {
                    append("\\u").append(
                        c.code
                            .toString(HEX)
                            .uppercase()
                            .padStart(4, '0'),
                    )
                } else {
                    append(c)
                }
            }
        }

    private fun check(
        file: String,
        ddl: String,
    ) {
        val path = directory.resolve(file)
        body(escaped(Files.readString(path))) shouldBe ddl.trim()
    }

    private fun rawFeffLines(file: String): Int =
        Files.readAllLines(directory.resolve(file)).count { it.contains(FEFF) }

    @Test
    fun `the outbox migration is the DDL outbox-and-queue generates for the control-plane schema`() {
        check("V202610210810__create_outbox.sql", OutboxSchema.ddl(SCHEMA, RUNTIME, SYS))
        rawFeffLines("V202610210810__create_outbox.sql") shouldBe 3
    }

    @Test
    fun `the job tables migration is the DDL service-runtime generates for the control-plane schema`() {
        check("V202610210820__create_job_tables.sql", JobSchema.ddl(SCHEMA, RUNTIME, SYS))
    }

    @Test
    fun `the idempotency migration is the DDL service-runtime generates for the control-plane schema`() {
        check("V202610210830__create_idempotency.sql", IdempotencySchema.ddl(SCHEMA, RUNTIME, SYS))
    }

    @Test
    fun `the audit entry migration is the DDL audit-log generates for the control-plane schema`() {
        check("V202610271110__create_audit_entry.sql", AuditSchema.auditEntryDdl(SCHEMA, RUNTIME))
        rawFeffLines("V202610271110__create_audit_entry.sql") shouldBe 4
    }

    @Test
    fun `the audit purge migration is the DDL audit-log generates for the control-plane schema`() {
        check(
            "V202610292340__create_audit_purge.sql",
            AuditSchema.purgeDdl(SCHEMA, RUNTIME, SYS, serviceName()),
        )
    }

    /** The value of `gst.service` in the service's application.yaml, the service argument of the purge DDL. */
    private fun serviceName(): String {
        val yaml =
            Path.of(System.getProperty("gst.repo.root"), "control-plane-bootstrap/src/main/resources/application.yaml")
        val root = Yaml().load<Map<String, Any?>>(Files.readString(yaml))
        val gst = root["gst"] as Map<*, *>
        return gst["service"] as String
    }

    private companion object {
        const val HEX = 16
        const val FEFF = '\uFEFF'

        /** The ten code points of the blank-check class that the generator writes as escapes. */
        val BLANK_CODE_POINTS =
            setOf(0x00A0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
        const val SCHEMA = "controlplane"
        const val RUNTIME = "controlplane_runtime"
        const val SYS = "controlplane_sys"
    }
}
