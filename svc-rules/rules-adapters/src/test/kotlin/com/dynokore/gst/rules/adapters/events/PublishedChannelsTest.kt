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
        event.type shouldBe ContractFiles.platformType("RulePackStaged")
        RulesEventCatalogue.TOPICS.getValue(event.type) shouldBe ContractFiles.platformAddress("RulePackStaged")
        ContractFiles.platformErrors("rule-pack-staged-payload.schema.json", event.dataJson).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-23119 channel platformRulePackActivated carries a payload that validates against its schema`() {
        val event = mapper.map(PackEvent.RulePackActivated("scope-pack", "2026.4", LocalDate.of(2026, 4, 1)))
        event.type shouldBe ContractFiles.platformType("RulePackActivated")
        event.type shouldBe "com.dynokore.gst.platform.RulePackActivated.v1"
        RulesEventCatalogue.TOPICS.getValue(event.type) shouldBe ContractFiles.platformAddress("RulePackActivated")
        RulesEventCatalogue.TOPICS.getValue(event.type) shouldBe "gst.platform.rule-pack-activated.v1"
        ContractFiles.platformErrors("rule-pack-activated-payload.schema.json", event.dataJson).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-23119 the catalogue entry of the activated event is not retained`() {
        RulesEventCatalogue
            .entries()
            .single { it.eventType == RulesEventCatalogue.RULE_PACK_ACTIVATED }
            .retainedWhileResolvable shouldBe false
    }

    @Test
    fun `INV-AC-23119 the rules-namespace type and a look-alike are not published channels`() {
        val published = ContractFiles.channelAddresses.keys.map { ContractFiles.message(it).first }
        published.contains("com.dynokore.gst.rules.RulePackActivated.v1") shouldBe false
        published.contains("com.dynokore.gst.platform.RulePackActivatedX.v1") shouldBe false
        ContractFiles.channelAddresses.values.contains("gst.rules.rule-pack-activated.v1") shouldBe false
    }

    @Test
    fun `INV-AC-23119 the emission guard accepts the platform type and refuses the old type and the look-alike`() {
        val guard = EmissionGuard(RulesEventCatalogue.catalogue(), RulesEventCatalogue.SERVICE)
        guard.require("com.dynokore.gst.platform.RulePackActivated.v1")
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.rules.RulePackActivated.v1") }
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.platform.RulePackActivatedX.v1") }
    }

    @Test
    fun `channel rulesPackCurrencyChanged carries a payload that validates against its schema`() {
        val raised = mapper.map(PackEvent.PackCurrencyChanged("DEP-001", raised = true, "scope-pack", "2026.4", 12, 3))
        val cleared = mapper.map(PackEvent.PackCurrencyChanged("DEP-001", raised = false))
        raised.type shouldBe ContractFiles.platformType("PackCurrencyChanged")
        RulesEventCatalogue.TOPICS.getValue(raised.type) shouldBe ContractFiles.platformAddress("PackCurrencyChanged")
        val schema = "pack-currency-changed-payload.schema.json"
        ContractFiles.platformErrors(schema, raised.dataJson).shouldBeEmpty()
        ContractFiles.platformErrors(schema, cleared.dataJson).shouldBeEmpty()
    }

    @Test
    fun `channel rulesExampleHappened is the contract placeholder, validated on its own schema and never emitted`() {
        ContractFiles.errors("rulesExampleHappened", """{"entityRef":"E-001"}""").shouldBeEmpty()
        RulesEventCatalogue.TOPICS.keys.contains(ContractFiles.message("rulesExampleHappened").first) shouldBe false
    }

    @Test
    fun `a payload that breaks its schema is reported, so the checks above can fail`() {
        ContractFiles
            .platformErrors("rule-pack-activated-payload.schema.json", """{"packIdentifier":"p","version":"1"}""")
            .shouldNotBeEmpty()
        ContractFiles
            .platformErrors(
                "pack-currency-changed-payload.schema.json",
                """{"depCode":"DEP-001","status":"raised"}""",
            ).shouldNotBeEmpty()
    }

    @Test
    fun `every channel of the document has a test above and every catalogued event is a channel of it`() {
        ContractFiles.channelAddresses.keys.toSet() shouldBe
            setOf("rulesRulePackStaged", "rulesPackCurrencyChanged", "rulesExampleHappened")
        // all three catalogued events are platform events (INV-T-4976 option A; INV-T-23126)
        val platform =
            setOf(
                RulesEventCatalogue.RULE_PACK_STAGED,
                RulesEventCatalogue.RULE_PACK_ACTIVATED,
                RulesEventCatalogue.PACK_CURRENCY_CHANGED,
            )
        RulesEventCatalogue.TOPICS.keys shouldBe platform
        platform shouldBe
            setOf(
                ContractFiles.platformType("RulePackStaged"),
                ContractFiles.platformType("RulePackActivated"),
                ContractFiles.platformType("PackCurrencyChanged"),
            )
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
