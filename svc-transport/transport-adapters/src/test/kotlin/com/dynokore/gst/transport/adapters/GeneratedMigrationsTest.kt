package com.dynokore.gst.transport.adapters

import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The library migrations are Flyway SQL files (TPL-12, CS-19) that embed the DDL the L1 modules publish. After its
 * leading `--` lines each file is that text, compared trimmed: this test fails when a platform-libs release changes the
 * DDL and the file is not regenerated (`-Dtransport.regenerate=true` rewrites the text under the existing header).
 */
class GeneratedMigrationsTest {
    @Test
    fun `V2 holds the job tables DDL of JobSchema for the transport schema`() {
        check("V2__create_job_tables.sql", JobSchema.ddl(SCHEMA, RUNTIME, SYS))
    }

    @Test
    fun `V3 holds the idempotency DDL of IdempotencySchema for the transport schema`() {
        check("V3__create_idempotency.sql", IdempotencySchema.ddl(SCHEMA, RUNTIME, SYS))
    }

    private fun check(
        file: String,
        ddl: String,
    ) {
        val path = directory.resolve(file)
        if (System.getProperty("transport.regenerate") == "true") {
            Files.writeString(path, header(path) + ddl.trim() + "\n")
        }
        body(path) shouldBe ddl.trim()
    }

    private fun lines(path: Path): List<String> = Files.readString(path).lines()

    private fun header(path: Path): String = lines(path).takeWhile { it.startsWith("--") }.joinToString("") { "$it\n" }

    private fun body(path: Path): String = lines(path).dropWhile { it.startsWith("--") }.joinToString("\n").trim()

    private val directory: Path =
        Path.of(System.getProperty("transport.migrations", "src/main/resources/db/migration"))

    private companion object {
        const val SCHEMA = "transport"
        const val RUNTIME = "transport_runtime"
        const val SYS = "transport_sys"
    }
}
