package com.dynokore.gst.submission.application.pack

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Cases of INV-AC-20784. The pack events have no producer yet; the tests feed payloads through fake readers. */
class PackMarginHandlersTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class FakeStore : PackMarginStore {
        val calls = mutableListOf<String>()
        val staged = mutableMapOf<Pair<String, String>, PackMargin?>()
        val activations = mutableSetOf<Pair<String, String>>()
        var failOnStage: RuntimeException? = null

        override fun stageCalendar(
            tenant: TenantId,
            packIdentifier: String,
            version: String,
            margin: PackMargin?,
        ): Boolean {
            calls += "stage $tenant $packIdentifier $version $margin"
            failOnStage?.let { throw it }
            val key = packIdentifier to version
            if (key in staged) return false
            staged[key] = margin
            return true
        }

        override fun recordActivation(
            tenant: TenantId,
            packIdentifier: String,
            version: String,
            effectiveFrom: LocalDate,
        ): Boolean {
            calls += "activate $tenant $packIdentifier $version $effectiveFrom"
            return activations.add(packIdentifier to version)
        }
    }

    private val stagedEvents = mutableMapOf<String, PackStagedData>()
    private val activatedEvents = mutableMapOf<String, PackActivatedData>()
    private val store = FakeStore()
    private val staged =
        CalendarPackStagedMarginHandler(
            tenants,
            { stagedEvents[it] ?: throw ContractViolation("missing field") },
            store,
        )
    private val activated =
        PackActivatedHandler(
            tenants,
            { activatedEvents[it] ?: throw ContractViolation("effectiveFrom is not a date") },
            store,
        )

    private fun envelope(key: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/rules-svc",
            type = "com.dynokore.gst.rules.RulePackStaged.v1",
            subject = "pack",
            time = Instant.parse("2026-11-12T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E-001"),
            docUuid = DocUuid(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")),
            transactionDate = LocalDate.parse("2026-11-12"),
            docSequence = 1,
            instanceId = "rules-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = key,
        )

    private fun stage(
        key: String,
        version: String,
        value: Long?,
        unit: String?,
        packType: String = "calendar",
    ): HandleOutcome {
        stagedEvents[key] =
            PackStagedData(
                "P-CAL",
                packType,
                version,
                if (value == null &&
                    unit == null
                ) {
                    null
                } else {
                    RawMargin(value, unit)
                },
            )
        return staged.handle(envelope(key))
    }

    private fun prefix(version: String) = "stage $tenant P-CAL $version "

    @Test
    fun `INV-AC-20784 staged version 1 stores 5 DAYS once and a second delivery changes nothing`() {
        stage("a", "1", 5, "DAYS") shouldBe HandleOutcome.APPLIED
        store.calls shouldBe listOf("${prefix("1")}PackMargin(5 DAYS)")
        stage("b", "1", 9, "DAYS") shouldBe HandleOutcome.IGNORED
        store.calls.size shouldBe 2
        store.staged["P-CAL" to "1"] shouldBe PackMargin.of(5, "DAYS")
    }

    @Test
    fun `INV-AC-20784 other pack types call no store method`() {
        stage("c", "1", 5, "DAYS", packType = "Calendar") shouldBe HandleOutcome.IGNORED
        stage("d", "1", 5, "DAYS", packType = "scope") shouldBe HandleOutcome.IGNORED
        store.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20784 an absent or invalid margin is stored as no margin`() {
        stage("v2", "2", null, null) shouldBe HandleOutcome.APPLIED
        stage("v3", "3", 5, "WEEKS") shouldBe HandleOutcome.APPLIED
        stage("v4", "4", 8785, "HOURS") shouldBe HandleOutcome.APPLIED
        stage("v5", "5", 367, "DAYS") shouldBe HandleOutcome.APPLIED
        store.calls shouldBe (2..5).map { "${prefix("$it")}null" }
    }

    @Test
    fun `INV-AC-20784 boundary margins are stored`() {
        stage("v6", "6", 8784, "HOURS") shouldBe HandleOutcome.APPLIED
        stage("v7", "7", 366, "DAYS") shouldBe HandleOutcome.APPLIED
        stage("v8", "8", 0, "DAYS") shouldBe HandleOutcome.APPLIED
        store.calls shouldBe
            listOf(
                "${prefix("6")}PackMargin(8784 HOURS)",
                "${prefix("7")}PackMargin(366 DAYS)",
                "${prefix("8")}PackMargin(0 DAYS)",
            )
    }

    @Test
    fun `INV-AC-20784 a staged event without packIdentifier is a ContractViolation and calls no store method`() {
        shouldThrow<ContractViolation> { staged.handle(envelope("missing")) }
        store.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20784 every activation is recorded once and a redelivery is ignored`() {
        activatedEvents["x"] = PackActivatedData("P-CAL", "1", LocalDate.of(2026, 1, 1))
        activatedEvents["o"] = PackActivatedData("OTHER", "9", LocalDate.of(2026, 2, 1))
        activated.handle(envelope("x")) shouldBe HandleOutcome.APPLIED
        store.calls shouldBe listOf("activate $tenant P-CAL 1 2026-01-01")
        activated.handle(envelope("x")) shouldBe HandleOutcome.IGNORED
        activated.handle(envelope("o")) shouldBe HandleOutcome.APPLIED
        store.calls.last() shouldBe "activate $tenant OTHER 9 2026-02-01"
    }

    @Test
    fun `INV-AC-20784 an activated event whose date is not a date is a ContractViolation and calls no store method`() {
        shouldThrow<ContractViolation> { activated.handle(envelope("bad")) }
        store.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20784 a store failure reaches the caller as the same exception`() {
        val failure = IllegalStateException("db down")
        store.failOnStage = failure
        stagedEvents["f"] = PackStagedData("P-CAL", "calendar", "1", RawMargin(5, "DAYS"))
        shouldThrow<IllegalStateException> { staged.handle(envelope("f")) } shouldBe failure
    }

    @Test
    fun `a unit written in lower case is stored as no margin`() {
        stage("l", "1", 5, "days") shouldBe HandleOutcome.APPLIED
        store.calls shouldBe listOf("${prefix("1")}null")
    }

    @Test
    fun `the value 366 with HOURS is stored`() {
        stage("h", "1", 366, "HOURS") shouldBe HandleOutcome.APPLIED
        store.calls shouldBe listOf("${prefix("1")}PackMargin(366 HOURS)")
    }
}
