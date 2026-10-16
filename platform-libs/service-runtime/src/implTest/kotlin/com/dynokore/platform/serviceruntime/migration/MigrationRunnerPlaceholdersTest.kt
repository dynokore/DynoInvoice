package com.dynokore.platform.serviceruntime.migration

import com.dynokore.platform.serviceruntime.composition.JobProperties
import com.dynokore.platform.serviceruntime.composition.ModuleProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

/**
 * The job-retention callback adds its two placeholders to the ones Spring Boot has bound already (coordinator ruling
 * INV-Q-5255): Flyway's `placeholders(Map)` replaces the whole map, so a service's own placeholder must survive, and a
 * service placeholder of either reserved name is a start-up error.
 */
class MigrationRunnerPlaceholdersTest {
    private fun customize(
        configuration: FluentConfiguration,
        schema: String? = "ledger",
    ) = MigrationRunnerConfiguration()
        .jobRetentionCallback(
            ModuleProperties(schema = schema),
            JobProperties(retention = Duration.ofDays(2)),
            MockEnvironment(),
        ).customize(configuration)

    @Test
    fun `a service placeholder stays beside the callback's two`() {
        val configuration = Flyway.configure().placeholders(mapOf("svc" to "submission"))
        customize(configuration)
        configuration.placeholders shouldBe
            mapOf("svc" to "submission", "jobSchema" to "ledger", "jobRetentionSeconds" to "172800")
    }

    @Test
    fun `a service placeholder named like one of the callback's two stops start-up`() {
        listOf("jobSchema", "jobRetentionSeconds").forEach { reserved ->
            val configuration = Flyway.configure().placeholders(mapOf(reserved to "x"))
            shouldThrow<IllegalStateException> { customize(configuration) }.message shouldContain reserved
        }
    }

    @Test
    fun `a service without a schema gets no placeholder`() {
        val configuration = Flyway.configure().placeholders(mapOf("svc" to "submission"))
        customize(configuration, schema = null)
        configuration.placeholders shouldBe mapOf("svc" to "submission")
    }

    @Test
    fun `a schema with a quote or a dot stops start-up before Flyway is given any placeholder or location`() {
        listOf("led\"ger", "ledger.evil", "ledger;drop", "Ledger").forEach { schema ->
            val configuration = Flyway.configure().placeholders(mapOf("svc" to "submission"))
            val locations = configuration.locations.toList()
            shouldThrow<IllegalArgumentException> { customize(configuration, schema = schema) }.message shouldContain "schema"
            configuration.placeholders shouldBe mapOf("svc" to "submission")
            configuration.locations.toList() shouldBe locations
        }
    }
}
