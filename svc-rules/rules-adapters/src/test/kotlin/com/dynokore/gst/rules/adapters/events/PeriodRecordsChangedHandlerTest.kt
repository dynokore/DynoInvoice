package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.JooqPeriodRecordsStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PeriodRecordsChangedHandlerTest {
    private val statements = mutableListOf<String>()
    private var upsertRows = 1

    private val dsl =
        DSL.using(
            MockConnection(
                MockDataProvider { ctx ->
                    statements += ctx.sql()
                    val rows =
                        if (ctx.sql().startsWith(
                                "insert into \"rules\".\"entity_period_set\"",
                            )
                        ) {
                            upsertRows
                        } else {
                            1
                        }
                    arrayOf(MockResult(rows))
                },
            ),
            SQLDialect.POSTGRES,
        )
    private val handler =
        PeriodRecordsChangedHandler(
            JooqPeriodRecordsStore(dsl),
            Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC),
        )

    private fun envelope(data: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = PeriodRecordsChangedEvents.TYPE,
            subject = "ENT-1",
            time = Instant.parse("2026-11-16T00:00:00Z"),
            tenant = TenantId(UUID.randomUUID()),
            entity = EntityRef("ENT-1"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("c"),
            causation = CausationId("k"),
            dataJson = data,
        )

    private fun period(
        ref: String = "2026-01",
        start: String = "\"2026-01-01\"",
        end: String = "\"2026-01-31\"",
    ) = """{"periodRef":"$ref","periodStart":$start,"periodEnd":$end}"""

    private fun payload(
        entity: String = "\"ENT-1\"",
        version: String = "1",
        periods: String = "[${period()}]",
    ) = """{"entityRef":$entity,"version":$version,"periods":$periods}"""

    @Test
    fun `INV-AC-20223 a valid event replaces the set and a lower or equal version changes nothing`() {
        val two =
            payload(version = "2", periods = "[${period()},${period("2026-02", "\"2026-02-01\"", "\"2026-02-28\"")}]")
        handler.handle(envelope(two)) shouldBe HandleOutcome.APPLIED
        // upsert, delete of the old set, two inserts
        statements.size shouldBe 4
        statements.clear()
        upsertRows = 0
        handler.handle(envelope(payload(version = "2"))) shouldBe HandleOutcome.IGNORED
        statements.size shouldBe 1 // only the guarded upsert ran: no delete, no insert
        // a member the handler does not read is ignored
        upsertRows = 1
        handler.handle(envelope(payload(version = "3").dropLast(1) + ""","extra":{"a":1}}""")) shouldBe
            HandleOutcome.APPLIED
    }

    @Test
    fun `INV-AC-20223 malformed payloads are contract violations and store nothing`() {
        val long65 = "x".repeat(65)
        val many = (1..601).joinToString(",", "[", "]") { period("P$it") }
        val cases =
            mapOf(
                "not JSON" to "{not json",
                "not an object" to "[]",
                "no entityRef" to """{"version":1,"periods":[${period()}]}""",
                "blank entityRef" to payload(entity = "\" \""),
                "entityRef over 64" to payload(entity = "\"$long65\""),
                "entityRef not a string" to payload(entity = "5"),
                "version missing" to """{"entityRef":"E","periods":[${period()}]}""",
                "version a string" to payload(version = "\"1\""),
                "version a decimal" to payload(version = "1.5"),
                "version zero" to payload(version = "0"),
                "periods missing" to """{"entityRef":"E","version":1}""",
                "periods not an array" to payload(periods = "{}"),
                "periods empty" to payload(periods = "[]"),
                "periods over 600" to payload(periods = many),
                "item not an object" to payload(periods = "[\"x\"]"),
                "periodRef missing" to payload(periods = """[{"periodStart":"2026-01-01","periodEnd":"2026-01-31"}]"""),
                "periodRef blank" to payload(periods = "[${period(" ")}]"),
                "periodRef over 64" to payload(periods = "[${period(long65)}]"),
                "periodRef not a string" to
                    payload(periods = """[{"periodRef":5,"periodStart":"2026-01-01","periodEnd":"2026-01-31"}]"""),
                "periodStart missing" to payload(periods = """[{"periodRef":"P","periodEnd":"2026-01-31"}]"""),
                "periodEnd missing" to payload(periods = """[{"periodRef":"P","periodStart":"2026-01-01"}]"""),
                "periodStart not a date" to payload(periods = "[${period(start = "\"2026-13-01\"")}]"),
                "periodEnd not a date" to payload(periods = "[${period(end = "\"31/01/2026\"")}]"),
                "periodEnd before periodStart" to
                    payload(periods = "[${period(start = "\"2026-02-01\"", end = "\"2026-01-31\"")}]"),
                "periodRef repeated" to payload(periods = "[${period()},${period()}]"),
            )
        for ((name, data) in cases) {
            statements.clear()
            val failure = runCatching { handler.handle(envelope(data)) }.exceptionOrNull()
            (failure != null && failure.javaClass == ContractViolation::class.java) shouldBe true
            if (statements.isNotEmpty()) error("$name: the store was called: $statements")
        }
        shouldThrow<ContractViolation> { handler.handle(envelope("{}")) }
    }

    @Test
    fun `INV-AC-20223 the catalogue entry names the published type and topic and the producer tenancy-svc`() {
        PeriodRecordsChangedEvents.TYPE shouldBe "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
        PeriodRecordsChangedEvents.TOPIC shouldBe "gst.tenancy.period-records-changed.v1"
        val entry = PeriodRecordsChangedEvents.entry()
        entry.eventType shouldBe PeriodRecordsChangedEvents.TYPE
        entry.topic shouldBe PeriodRecordsChangedEvents.TOPIC
        entry.producers shouldBe setOf("tenancy-svc")
        entry.contentTopic shouldBe false
    }
}
