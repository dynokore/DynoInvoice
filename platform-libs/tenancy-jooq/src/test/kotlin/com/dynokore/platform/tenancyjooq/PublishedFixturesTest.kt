package com.dynokore.platform.tenancyjooq

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.jar.JarFile

/**
 * The published test-fixtures jar holds TenancyProbe and its Spring test auto-configuration only: tenancy-jooq's own
 * harness (TenancyJooqFixture, MockExecution), which the SQL placement policy exempts, is internal and never published
 * to consumers' tests (review of !61).
 */
class PublishedFixturesTest {
    @Test
    fun `the published test fixtures hold no part of tenancy-jooq's own harness`() {
        val jar = System.getProperty("tenancyJooq.testFixturesJar")
        val classes =
            JarFile(jar).use { file ->
                file
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") }
                    .map { it.substringAfterLast('/').substringBefore('$').removeSuffix(".class") }
                    .toSortedSet()
            }
        assertEquals(sortedSetOf("TenancyProbe", "TenancyProbeAutoConfiguration"), classes)
    }
}
