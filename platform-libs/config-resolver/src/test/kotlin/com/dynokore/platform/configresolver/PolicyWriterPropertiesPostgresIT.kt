package com.dynokore.platform.configresolver

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * INV-T-4517 (coordinator ruling INV-Q-5117): [JdbcPolicyWriter] exposes its audit collaborator and its delegation
 * probe as public read-only properties, which return the constructor arguments by identity; `delegation` is null
 * when none was given. service-runtime's wiring tests (INV-T-4790) read them instead of a private field.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyWriterPropertiesPostgresIT {
    private val h = WriteHarness("postgres:18")

    @AfterAll
    fun stop() = h.close()

    @Test
    fun `audit and delegation return the constructor arguments by identity`() {
        val audit = h.audit()
        val probe = DelegationProbe { _, _ -> false }
        val writer =
            JdbcPolicyWriter(
                dsl = h.dsl,
                catalogue = WriteCatalogue.catalogue,
                form = DeploymentForm.SAAS,
                schema = WriteHarness.SCHEMA,
                audit = audit,
                outbox = null,
                clock = WriteHarness.CLOCK,
                delegation = probe,
            )
        assertSame(audit, writer.audit)
        assertSame(probe, writer.delegation)
    }

    @Test
    fun `delegation is null when none was given`() {
        val audit = h.audit()
        val writer =
            JdbcPolicyWriter(
                dsl = h.dsl,
                catalogue = WriteCatalogue.catalogue,
                form = DeploymentForm.SAAS,
                schema = WriteHarness.SCHEMA,
                audit = audit,
                outbox = null,
                clock = WriteHarness.CLOCK,
            )
        assertSame(audit, writer.audit)
        assertNull(writer.delegation)
    }
}
