package com.dynokore.gst.submission.adapters

import gst.build.konsist.MigrationLint
import gst.build.konsist.RawSqlPlacement
import gst.build.konsist.SqlGuards
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
