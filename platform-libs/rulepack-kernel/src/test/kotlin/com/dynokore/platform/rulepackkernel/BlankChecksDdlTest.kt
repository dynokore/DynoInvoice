package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.BlankText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rendered pack-registry DDL survives Flyway's BOM stripping unchanged (INV-Q-10832, INV-T-10839). */
class BlankChecksDdlTest {
    @Test
    fun `the pack registry DDL holds no U+FEFF and spells the character as its six-character escape`() {
        val ddl =
            PackRegistryMigration.ddl(
                schema = "rules",
                table = "rule_pack_version",
                runtimeRole = "rules_app",
                sysRole = "rules_sys",
            )
        // Flyway's BomStrippingReader drops every U+FEFF from a script; the text must survive that unchanged.
        assertEquals(ddl, ddl.replace("\uFEFF", ""), "the DDL holds a raw U+FEFF")
        assertTrue(BlankText.SQL_BLANK in ddl, "the shared blank definition is absent")
        assertTrue("\\uFEFF" in ddl, "the six-character escape is absent")
    }
}
