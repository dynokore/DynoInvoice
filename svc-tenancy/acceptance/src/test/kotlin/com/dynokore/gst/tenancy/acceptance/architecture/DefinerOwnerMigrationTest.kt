package com.dynokore.gst.tenancy.acceptance.architecture

import gst.build.konsist.MigrationLint
import org.junit.jupiter.api.Test

/** INV-AC-19640: each SECURITY DEFINER function of a migration is followed in its file by OWNER TO tenancy_sys. */
class DefinerOwnerMigrationTest {
    @Test
    fun `INV-AC-19640 the migrations of tenancy-adapters hold the definer-owner lint`() {
        MigrationLint.assertMigrations(Repository.adaptersDir(), Repository.production())
    }
}
