package com.dynokore.platform.tenancyjooq

import org.jooq.Configuration
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.VisitListener
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultExecuteListenerProvider
import org.jooq.impl.DefaultVisitListenerProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TenancyJooq.problem] (coordinator ruling INV-Q-5105): the configuration check service-runtime's guard calls. The six
 * refusal cases moved here from service-runtime's `TenancyJooqWiringTest` test `a configuration needs all of
 * tenancy-jooq's parts and no listener after its statement check`, with the same compared texts; the case that
 * [TenancyJooq.configure]'s own result is accepted stays in both; [TenancyJooq.install] is the same installation on a
 * `DefaultConfiguration`.
 */
class ConfigurationProblemTest {
    private val fixture = Databases.of(Images.PG18)
    private val owned: Configuration = fixture.configuration(TEST_TABLES)
    private val postgres = { DefaultConfiguration().apply { set(SQLDialect.POSTGRES) } }

    private fun refused(configuration: Configuration): String = checkNotNull(TenancyJooq.problem(configuration))

    @Test
    fun `tenancy-jooq's own configure result has no problem`() {
        assertNull(TenancyJooq.problem(owned))
    }

    @Test
    fun `install makes a plain DefaultConfiguration tenancy-jooq's`() {
        val boot = postgres()
        assertTrue(TenancyJooq.problem(boot) != null)
        TenancyJooq.install(boot, TEST_TABLES, fixture.tenants, fixture.transactions)
        assertNull(TenancyJooq.problem(boot))
    }

    @Test
    fun `no execute listener`() {
        val partial = postgres().set(owned.connectionProvider()).set(*owned.visitListenerProviders())
        assertTrue("ExecuteListener" in refused(partial))
    }

    @Test
    fun `no visit listener`() {
        val partial = postgres().set(owned.connectionProvider()).set(*owned.executeListenerProviders())
        assertTrue("VisitListeners" in refused(partial))
    }

    @Test
    fun `no connection provider of tenancy-jooq's`() {
        val partial = postgres().set(*owned.visitListenerProviders()).set(*owned.executeListenerProviders())
        assertTrue("connection provider" in refused(partial))
    }

    @Test
    fun `a foreign VisitListener beside tenancy-jooq's, and one alone, could rewrite the statement`() {
        val foreign = DefaultVisitListenerProvider(object : VisitListener {})
        assertTrue("VisitListeners" in refused(owned.derive().deriveAppending(foreign.provide())))
        val alone =
            postgres().set(owned.connectionProvider()).set(foreign).set(*owned.executeListenerProviders())
        assertTrue("VisitListeners" in refused(alone))
    }

    @Test
    fun `a listener after the statement check could change the statement`() {
        val after = DefaultExecuteListenerProvider(object : ExecuteListener {})
        assertTrue("ExecuteListener" in refused(owned.derive().deriveAppending(after.provide())))
        assertEquals(1, owned.visitListenerProviders().size)
    }
}
