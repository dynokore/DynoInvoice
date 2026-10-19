package com.dynokore.gst.access.bootstrap

import com.lemonappdev.konsist.api.Konsist
import gst.build.konsist.ArchitectureRules
import gst.build.konsist.MigrationLint
import gst.build.konsist.RawSqlPlacement
import gst.build.konsist.SqlGuards
import org.junit.jupiter.api.Test
import java.io.File

/**
 * TPL-14 architecture tests over the whole repository (INV-T-4934): the layer rule (a Spring or jOOQ import in
 * access-domain fails, a java.sql or javax.sql import passes: owner answer INV-Q-5331 (D)), the database-access rule (jOOQ
 * only: no JdbcTemplate, no raw SQL string, no statement call, no connection taken from a DataSource: INV-T-4201 and
 * INV-Q-5327 (D)), the SQL guards and the migration lint (a tenant column and a row policy on every table).
 * Nothing is suppressed.
 */
class ArchitectureTest {
    private val repository: File = File("..").absoluteFile.normalize()

    @Test
    fun `the module layers hold and the domain and application layers are framework-free`() {
        // the generated contract sits under build/, which scopeFromProject skips; the Contract layer needs a file to exist
        val contract = Konsist.scopeFromDirectory("access-contract/build/generated/contract-server/src/main/kotlin")
        ArchitectureRules.assertServiceModuleDirection("com.dynokore.gst.access", Konsist.scopeFromProject() + contract)
    }

    @Test
    fun `database access is jOOQ only in every module and source set`() {
        RawSqlPlacement.assertPlacement(Konsist.scopeFromProject())
    }

    @Test
    fun `no raw jOOQ SQL, no interpolated SQL and no session setting outside tenancy-kernel`() {
        SqlGuards.assertAll(Konsist.scopeFromProduction())
    }

    @Test
    fun `every migration table has a tenant column and a policy and the definer rules hold`() {
        MigrationLint.assertMigrations(repository, Konsist.scopeFromProduction())
    }
}
