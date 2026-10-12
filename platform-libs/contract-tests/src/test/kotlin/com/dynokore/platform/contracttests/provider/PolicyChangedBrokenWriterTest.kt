package com.dynokore.platform.contracttests.provider

import au.com.dius.pact.core.model.ContentType
import au.com.dius.pact.core.model.DefaultPactReader
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.support.Result
import au.com.dius.pact.provider.ResponseComparison
import com.dynokore.platform.contracttests.Contracts
import com.dynokore.platform.contracttests.consumer.PolicyChangedPactTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * Done-when: a deliberately broken contract makes the required job fail. A policy-writer fixture that drops
 * `effectiveDate`, a field service-runtime's PolicyChanged pact reads, fails the same comparison the provider
 * verification runs, while the real writer's event passes it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyChangedBrokenWriterTest {
    private val probe = PolicyWriterProbe()
    private val mapper = ObjectMapper()
    private val interaction =
        DefaultPactReader.loadPact(File("build/pacts/${Contracts.SERVICE_RUNTIME}-${Contracts.CONFIG_RESOLVER}.json"))
            .interactions
            .map { it as V4Interaction.AsynchronousMessage }
            .single { it.description == PolicyChangedPactTest.DESCRIPTION }

    @AfterAll
    fun stop() = probe.close()

    private fun mismatches(message: String): List<String> {
        val body = OptionalBody.body(message.toByteArray(), ContentType.JSON)
        val comparison = ResponseComparison.compareMessage(interaction, body, emptyMap(), emptyMap())
        return when (val result = comparison.bodyMismatches) {
            is Result.Ok -> result.value.mismatches.values.flatten().map { it.description() }
            is Result.Err -> listOf(result.error.description())
        }
    }

    @Test
    fun `the real policy writer's event meets the pact`() {
        mismatches(probe.entityOverride()).shouldBeEmpty()
    }

    @Test
    fun `a policy writer that drops effectiveDate fails the verification`() {
        val broken =
            probe.entityOverride { json ->
                mapper.writeValueAsString((mapper.readTree(json) as ObjectNode).apply { remove("effectiveDate") })
            }
        val found = mismatches(broken)
        found.shouldNotBeEmpty()
        found.joinToString().shouldContain("effectiveDate")
    }
}
