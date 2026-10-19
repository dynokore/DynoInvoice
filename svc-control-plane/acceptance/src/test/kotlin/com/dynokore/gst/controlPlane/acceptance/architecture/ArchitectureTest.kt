package com.dynokore.gst.controlPlane.acceptance.architecture

import gst.build.konsist.ArchitectureRules
import gst.build.konsist.MigrationLint
import gst.build.konsist.RawSqlPlacement
import gst.build.konsist.SqlGuards
import org.junit.jupiter.api.Test

/** ARC-03, ARC §2.2, INV-REQ-854 and INV-T-4201 over the real sources (platform-build's shared rules). */
class ArchitectureTest {
    @Test
    fun `layers are one-way and domain and application import no Spring, jOOQ, Jackson, servlet or JPA type`() {
        ArchitectureRules.assertServiceModuleDirection(SERVICE_PACKAGE, Repository.layered())
    }

    @Test
    fun `database access is jOOQ only and raw SQL placement holds`() {
        RawSqlPlacement.assertPlacement(Repository.production())
        SqlGuards.assertAll(Repository.production())
    }

    @Test
    fun `migrations hold the lint, and every table without a tenant column is declared platform-scoped`() {
        MigrationLint.assertMigrations(Repository.adaptersDir(), Repository.production())
    }
}
