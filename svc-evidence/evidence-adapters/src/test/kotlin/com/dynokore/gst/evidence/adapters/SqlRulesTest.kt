package com.dynokore.gst.evidence.adapters

import gst.build.konsist.MigrationLint
import gst.build.konsist.RawSqlPlacement
import gst.build.konsist.SqlGuards
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * TPL-14 architecture test of the database rules (INV-T-4201, INV-T-4135, CS-19): the raw-SQL placement, the SQL
 * guards and the migration lint over this module's real sources and migrations.
 */
class SqlRulesTest {
    @Test
    fun `the migrations pass the migration lint`() {
        MigrationLint.assertMigrations(File("."))
    }

    @Test
    fun `the production sources pass the SQL guards`() {
        SqlGuards.assertAll()
    }

    @Test
    fun `the production sources pass the raw SQL placement rules`() {
        RawSqlPlacement.assertPlacement()
    }
}

/** Probes of the migration lint (INV-T-4135): each scratch migration is linted as text and never committed. */
class MigrationLintProbeTest {
    private fun violations(sql: String) = MigrationLint.lint("probe.sql", listOf(1 to sql))

    @Test
    fun `a table without a tenant column is a finding`() {
        violations("create table evidence.probe (id uuid primary key);").isNotEmpty() shouldBe true
    }

    @Test
    fun `a nullable tenant column is a finding`() {
        violations("create table evidence.probe (id uuid primary key, tenant_id uuid);").isNotEmpty() shouldBe true
    }

    @Test
    fun `a table with a not null tenant column passes the column rule`() {
        violations("create table evidence.probe (id uuid primary key, tenant_id uuid not null);") shouldBe emptyList()
    }

    @Test
    fun `a platform scoped table is declared by its comment`() {
        violations(
            "create table evidence.probe (id uuid primary key); " +
                "comment on table evidence.probe is 'platform-scoped: no tenant data';",
        ) shouldBe emptyList()
    }
}
