package com.dynokore.gst.consoleBff.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `db/provision.sql` creates the schema owned by the migration role; no script grants CREATE on the database. */
class ProvisionScriptTest {
    private val provision = checkNotNull(javaClass.getResource("/db/provision.sql")).readText()
    private val roles = checkNotNull(javaClass.getResource("/db/roles.sql")).readText()

    @Test
    fun `INV-AC-14457 provision creates the schema console_bff owned by console_bff_migrate`() {
        provision.contains("create schema console_bff authorization ${ConsoleDb.MIGRATE};") shouldBe true
    }

    @Test
    fun `INV-AC-14457 roles never grant create on the database`() {
        Regex("grant\\s+create\\s+on\\s+database", RegexOption.IGNORE_CASE).containsMatchIn(roles) shouldBe false
        Regex("grant\\s+create\\s+on\\s+database", RegexOption.IGNORE_CASE).containsMatchIn(provision) shouldBe false
    }
}
