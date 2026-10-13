package com.dynokore.platform.tenancyjooq

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/**
 * The mutation check of INV-T-4133: with tenancy-jooq's listeners removed from the DSLContext (the layer gone, RLS
 * off on the test tables), every isolation test fails, so the isolation tests prove the layer and not the data.
 */
class MutationTest {
    @Test
    fun `removing the VisitListener makes every cross-tenant test fail`() {
        Mutation.withoutTenancyListeners = true
        val results =
            try {
                EngineTestKit
                    .engine("junit-jupiter")
                    .selectors(selectClass(IsolationTest::class.java))
                    .execute()
                    .testEvents()
            } finally {
                Mutation.withoutTenancyListeners = false
            }
        val started = results.started().count()
        assertTrue(started >= 3 * 20, "the isolation tests ran on every image: $started")
        val passed = results.succeeded().list().map { it.testDescriptor.displayName }
        assertEquals(emptyList<String>(), passed, "every isolation test fails without the layer")
        assertEquals(started, results.failed().count())
        assertEquals(0, results.succeeded().count())
    }
}
