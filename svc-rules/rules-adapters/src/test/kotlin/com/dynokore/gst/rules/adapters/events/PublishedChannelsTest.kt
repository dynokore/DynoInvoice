package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.platform.outboxandqueue.EmissionGuard
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * A test per published channel of the rules AsyncAPI document validates the mapped payload against the payload schema
 * the document names (INV-T-4965; API-43, STK-45), and the emission guard refuses what is not catalogued for rules
 * (INV-AC-1869).
 */
class PublishedChannelsTest {
    private val mapper = PackEventMapper()

    private fun channelOf(type: String): String =
        ContractFiles.channelAddresses.keys.single { ContractFiles.message(it).first == type }

    @Test
    fun `channel rulesRulePackStaged carries a payload that validates against its schema`() {
        val event =
            mapper.map(
                PackEvent.RulePackStaged(
                    "scope-pack",
                    "SCOPE",
                    "2026.4",
                    LocalDate.of(2026, 4, 1),
                    "1.0.0",
                    mapOf("packType" to "SCOPE"),
                    mapOf("content/scope.json" to "sha256:ab"),
                    "c2ln",
                ),
            )
        event.type shouldBe ContractFiles.message("rulesRulePackStaged").first
        ContractFiles.errors("rulesRulePackStaged", event.dataJson).shouldBeEmpty()
    }

    @Test
    fun `channel rulesRulePackActivated carries a payload that validates against its schema`() {
        val event = mapper.map(PackEvent.RulePackActivated("scope-pack", "2026.4", LocalDate.of(2026, 4, 1)))
        event.type shouldBe ContractFiles.message("rulesRulePackActivated").first
        ContractFiles.errors("rulesRulePackActivated", event.dataJson).shouldBeEmpty()
    }

    @Test
    fun `channel rulesPackCurrencyChanged carries a payload that validates against its schema`() {
        val raised = mapper.map(PackEvent.PackCurrencyChanged("DEP-001", raised = true, "scope-pack", "2026.4", 12, 3))
        val cleared = mapper.map(PackEvent.PackCurrencyChanged("DEP-001", raised = false))
        raised.type shouldBe ContractFiles.message("rulesPackCurrencyChanged").first
        ContractFiles.errors("rulesPackCurrencyChanged", raised.dataJson).shouldBeEmpty()
        ContractFiles.errors("rulesPackCurrencyChanged", cleared.dataJson).shouldBeEmpty()
    }

    @Test
    fun `channel rulesExampleHappened is the contract placeholder, validated on its own schema and never emitted`() {
        ContractFiles.errors("rulesExampleHappened", """{"entityRef":"E-001"}""").shouldBeEmpty()
        RulesEventCatalogue.TOPICS.keys.contains(ContractFiles.message("rulesExampleHappened").first) shouldBe false
    }

    @Test
    fun `a payload that breaks its schema is reported, so the checks above can fail`() {
        ContractFiles.errors("rulesRulePackActivated", """{"packIdentifier":"p"}""").shouldNotBeEmpty()
        ContractFiles
            .errors(
                "rulesPackCurrencyChanged",
                """{"depCode":"DEP-001","status":"raised"}""",
            ).shouldNotBeEmpty()
    }

    @Test
    fun `every channel of the document has a test above and every catalogued event is a channel of it`() {
        ContractFiles.channelAddresses.keys.toSet() shouldBe
            setOf("rulesRulePackStaged", "rulesRulePackActivated", "rulesPackCurrencyChanged", "rulesExampleHappened")
        RulesEventCatalogue.TOPICS.forEach { (type, topic) ->
            val channel = channelOf(type)
            ContractFiles.channelAddresses.getValue(channel) shouldBe topic
        }
    }

    @Test
    fun `the emission guard refuses an uncatalogued type and a type catalogued for another producer`() {
        val guard = EmissionGuard(RulesEventCatalogue.catalogue(), RulesEventCatalogue.SERVICE)
        RulesEventCatalogue.TOPICS.keys.forEach { guard.require(it) }
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.rules.NotCatalogued.v1") }
        shouldThrow<EmissionNotPermitted> { guard.require(ContractFiles.message("rulesExampleHappened").first) }
        shouldThrow<EmissionNotPermitted> {
            EmissionGuard(RulesEventCatalogue.catalogue(), "payload-svc").require(RulesEventCatalogue.RULE_PACK_STAGED)
        }
    }
}
