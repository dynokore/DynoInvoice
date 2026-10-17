package com.dynokore.platform.serviceruntime.composition

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates

/**
 * [platform] lists the platform auto-configurations as class literals. This test compares the names (no class is
 * loaded) with the `AutoConfiguration.imports` files on the classpath, so a platform class added to the imports
 * without a literal, or a literal the imports lack, fails here (owner ruling INV-Q-4384 (a), coordinator ruling
 * INV-Q-4786).
 */
class PlatformAutoConfigurationListTest {
    private val imported: List<String> =
        ImportCandidates
            .load(AutoConfiguration::class.java, Composition::class.java.classLoader)
            .filter { it.startsWith("com.dynokore.platform.") }

    private val listed: List<String> = platform().map { it.name }

    @Test
    fun `every platform class AutoConfiguration-imports names has a class literal in the list`() {
        (imported - listed.toSet()).shouldBeEmpty()
    }

    @Test
    fun `every class literal in the list is named by AutoConfiguration-imports`() {
        (listed - imported.toSet()).shouldBeEmpty()
    }

    @Test
    fun `neither side names a class twice`() {
        imported.size shouldBe imported.toSet().size
        listed.size shouldBe listed.toSet().size
    }
}
