package com.dynokore.gst.controlPlane.adapters.migrations

import com.dynokore.platform.auditlog.AuditSchema
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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

    private fun check(
        file: String,
        ddl: String,
    ) {
        val path = directory.resolve(file)
        body(Files.readString(path)) shouldBe ddl.trim()
    }

    @Test
    fun `the outbox migration is the DDL outbox-and-queue generates for the control-plane schema`() {
        check("V202610210810__create_outbox.sql", OutboxSchema.ddl(SCHEMA, RUNTIME, SYS))
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
    }

    private companion object {
        const val SCHEMA = "controlplane"
        const val RUNTIME = "controlplane_runtime"
        const val SYS = "controlplane_sys"
    }
}
