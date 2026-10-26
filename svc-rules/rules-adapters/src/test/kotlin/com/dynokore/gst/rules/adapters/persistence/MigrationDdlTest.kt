package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.rulepackkernel.PackRegistryMigration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * A migration is a SQL file, never code that runs JDBC (CS-18), and the outbox tables of outbox-and-queue are a
 * versioned migration set applied into the service's own schema (TPL-12). `V2__outbox.sql` is the module's generated
 * DDL
 * for schema `rules`, committed as text; this test fails when the committed file differs from what the pinned
 * outbox-and-queue generates, so a library bump cannot leave a stale migration. A V file is never rewritten (Flyway
 * checksums): the blank class the old generator wrote raw is compared as the `\uXXXX` escape the new one writes, and a
 * generator change is a new forward migration (INV-T-10867).
 */
class MigrationDdlTest {
    private val file = File("src/main/resources/db/migration/V2__outbox.sql")

    /** The ten blank code points the old generator wrote raw and the new one writes as `\uXXXX` (INV-T-10836). */
    private fun escapeBlankClass(text: String): String =
        BLANK_CODE_POINTS.fold(text) { acc, cp ->
            acc.replace(String(Character.toChars(cp)), "\\u%04X".format(Locale.ROOT, cp))
        }

    private fun expected(): String =
        HEADER +
            OutboxSchema.ddl(schema = "rules", runtimeRole = "rules_runtime", sysRole = "rules_sys").trimEnd() +
            "\n"

    @Test
    fun `V2 outbox migration is the DDL the pinned outbox-and-queue generates`() {
        escapeBlankClass(file.readText(Charsets.UTF_8)) shouldBe expected()
        file.readText(Charsets.UTF_8).count { it.code == FEFF_CODE } shouldBe 3
    }

    private val artefactFile = File("src/main/resources/db/migration/V3__pack_artefact.sql")

    private fun expectedArtefact(): String =
        ARTEFACT_HEADER +
            PackRegistryMigration.ddl("rules", "pack_artefact", "rules_runtime", "rules_sys").trimEnd() +
            "\n"

    /** INV-T-7525: the holder copy table is the kernel's DDL taken verbatim, so a kernel bump cannot leave it stale. */
    @Test
    fun `V3 pack artefact migration is the DDL the pinned rulepack-kernel generates`() {
        escapeBlankClass(artefactFile.readText(Charsets.UTF_8)) shouldBe expectedArtefact()
        artefactFile.readText(Charsets.UTF_8).count { it.code == FEFF_CODE } shouldBe 8
    }

    private val grantFile = File("src/main/resources/db/migration/V4__pack_artefact_grants.sql")

    /** Comments removed, the single statement trimmed: what V4 says, compared as a string
     *  with the one allowed grant. */
    private fun grantMatches(text: String): Boolean =
        text
            .lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .trim() == GRANT

    @Test
    fun `INV-AC-2182 V4 holder copy grant is exactly SELECT and INSERT to rules_runtime`() {
        grantMatches(grantFile.readText()) shouldBe true
    }

    @Test
    fun `INV-AC-2182 V4 comparison rejects an UPDATE grant and the same grant for rules_sys`() {
        grantMatches("GRANT SELECT, INSERT, UPDATE ON rules.pack_artefact TO rules_runtime;") shouldBe false
        grantMatches("GRANT SELECT, INSERT ON rules.pack_artefact TO rules_sys;") shouldBe false
        grantMatches("-- c\n$GRANT\nGRANT DELETE ON rules.pack_artefact TO rules_runtime;") shouldBe false
        grantMatches("-- c\n$GRANT\n") shouldBe true
    }

    private companion object {
        const val FEFF_CODE = 0xFEFF
        val BLANK_CODE_POINTS =
            listOf(0x00A0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, FEFF_CODE)
        const val GRANT = "GRANT SELECT, INSERT ON rules.pack_artefact TO rules_runtime;"
        const val ARTEFACT_HEADER =
            "-- Generated: PackRegistryMigration.ddl(\"rules\", \"pack_artefact\", \"rules_runtime\", " +
                "\"rules_sys\") of\n" +
                "-- rulepack-kernel (V3__pack_artefact.sql, TPL-12, INV-T-7525), committed as text;\n" +
                "-- MigrationDdlTest fails when it differs. The holder copy of the verified packs (INV-REQ-1195),\n" +
                "-- beside the metadata tables of V1 and replacing none of them (INV-Q-7438). Run as rules_migrate;\n" +
                "-- PRECONDITION: rules.bound_tenant() and rules.bound_actor() exist (tenancy-kernel BindingKeyMigration).\n"

        const val HEADER =
            "-- V2__outbox.sql (TPL-12; INV-T-4965). Generated: OutboxSchema.ddl(\"rules\", \"rules_runtime\",\n" +
                "-- \"rules_sys\") of outbox-and-queue, committed as text; MigrationDdlTest fails when it differs.\n" +
                "-- Events leave only through this outbox in the command's transaction (ARC-05).\n" +
                "-- Run as rules_migrate;\n" +
                "-- PRECONDITION: rules.bound_tenant() and\n" +
                "-- rules.bound_actor() exist (tenancy-kernel BindingKeyMigration, owner question INV-Q-6580).\n"
    }
}
