package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.platform.outboxandqueue.OutboxSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A migration is a SQL file, never code that runs JDBC (CS-18), and the outbox tables of outbox-and-queue are a
 * versioned migration set applied into the service's own schema (TPL-12). `V2__outbox.sql` is the module's generated DDL
 * for schema `rules`, committed as text; this test fails when the committed file differs from what the pinned
 * outbox-and-queue generates, so a library bump cannot leave a stale migration. Regenerate with
 * `-Dgst.regenerate=true` and review the diff.
 */
class MigrationDdlTest {
    private val file = File("src/main/resources/db/migration/V2__outbox.sql")

    private fun expected(): String =
        HEADER + OutboxSchema.ddl(schema = "rules", runtimeRole = "rules_runtime", sysRole = "rules_sys").trimEnd() + "\n"

    @Test
    fun `V2 outbox migration is the DDL the pinned outbox-and-queue generates`() {
        if (System.getProperty("gst.regenerate") == "true") file.writeText(expected())
        file.readText() shouldBe expected()
    }

    private companion object {
        const val HEADER =
            "-- V2__outbox.sql (TPL-12; INV-T-4965). Generated: OutboxSchema.ddl(\"rules\", \"rules_runtime\", \"rules_sys\") of\n" +
                "-- outbox-and-queue, committed as text; MigrationDdlTest fails when it differs. Events leave only through this\n" +
                "-- outbox in the command's transaction (ARC-05). Run as rules_migrate; PRECONDITION: rules.bound_tenant() and\n" +
                "-- rules.bound_actor() exist (tenancy-kernel BindingKeyMigration, owner question INV-Q-6580).\n"
    }
}
