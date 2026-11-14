package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.application.HandOnAggregates
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.logging.CorrelationId
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** The higher revision of a recorded source (INV-REQ-434 item 4) at the service boundary. */
class AggregateRevisionBoundaryTest : BoundaryFixture() {
    @Test
    fun `INV-REQ-434 revision 2 over an untransmitted revision 1 supersedes it with the same number`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            val changed = """{"internalTaxCode":"SR-STD","amount":"1700.00"}"""
            val sent = boundary.send(tenant, body(sourceRevision = 2, lines = changed))
            sent.status shouldBe 202
            val two = json(sent)
            two["documentNumber"].asString() shouldBe one["documentNumber"].asString()
            two["sequence"].asLong() shouldBe one["sequence"].asLong()
            query(
                "select superseded_by::text from aggregation.aggregate where tenant_id = ? and source_revision = 1",
                tenant,
            ) { it.getString(1) } shouldBe listOf(two["aggregateId"].asString())
            lines(tenant, two["aggregateId"].asString()) shouldBe listOf("SR-STD=1700.00")
            lines(tenant, one["aggregateId"].asString()) shouldBe listOf("SR-STD=1500.00")
            // a replay of revision 2 answers the stored receipt
            val replay = json(boundary.send(tenant, body(sourceRevision = 2, lines = changed)))
            replay["aggregateId"].asString() shouldBe two["aggregateId"].asString()
            replay["documentNumber"].asString() shouldBe two["documentNumber"].asString()
        }

    @Test
    fun `INV-REQ-434 revision 2 over a transmitted revision 1 leaves it unchanged and adds a supplementary aggregate`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            transmit(tenant)
            val before = rowOf(tenant, 1)
            val two =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 2, lines = """{"internalTaxCode":"SR-STD","amount":"1600.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            rowOf(tenant, 1) shouldBe before
            two["sequence"].asLong() shouldBe one["sequence"].asLong() + 1
            lines(tenant, two["aggregateId"].asString()) shouldBe listOf("SR-STD=100.00")
            // a transmitted supplementary is not amended either: revision 3 is compared with everything transmitted
            transmit(tenant)
            val three =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 3, lines = """{"internalTaxCode":"SR-STD","amount":"1650.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            two["sequence"].asLong() + 1 shouldBe three["sequence"].asLong()
            lines(tenant, three["aggregateId"].asString()) shouldBe listOf("SR-STD=50.00")
        }

    @Test
    fun `INV-REQ-434 revision 2 over a handed-on untransmitted revision 1 leaves it unchanged and adds a supplementary`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            closeWindows(tenant)
            boundary.handOn(tenant).size shouldBe 1
            val before = rowOf(tenant, 1)
            val two =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 2, lines = """{"internalTaxCode":"SR-STD","amount":"1600.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            rowOf(tenant, 1) shouldBe before
            (two["documentNumber"].asString() == one["documentNumber"].asString()) shouldBe false
            two["sequence"].asLong() shouldBe one["sequence"].asLong() + 1
            lines(tenant, two["aggregateId"].asString()) shouldBe listOf("SR-STD=100.00")
            // revision 3 replaces the untransmitted, not handed-on supplementary; its lines are the difference to the
            // original that remains, and the number of the supplementary is kept
            val three =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 3, lines = """{"internalTaxCode":"SR-STD","amount":"1650.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            three["documentNumber"].asString() shouldBe two["documentNumber"].asString()
            lines(tenant, three["aggregateId"].asString()) shouldBe listOf("SR-STD=150.00")
            rowOf(tenant, 1) shouldBe before
        }

    @Test
    fun `INV-REQ-434 a zero difference stores nothing, answers the compared receipt and records the revision`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            transmit(tenant)
            val before = rowOf(tenant, 1)
            val same = boundary.send(tenant, body(sourceRevision = 3))
            same.status shouldBe 202
            val receipt = json(same)
            receipt["aggregateId"].asString() shouldBe one["aggregateId"].asString()
            receipt["documentNumber"].asString() shouldBe one["documentNumber"].asString()
            count(tenant) shouldBe listOf("1")
            rowOf(tenant, 1) shouldBe before
            // a replay of revision 3 answers the same receipt and a revision below the recorded one is refused
            json(boundary.send(tenant, body(sourceRevision = 3))).get("aggregateId").asString() shouldBe
                one["aggregateId"].asString()
            val refused = boundary.send(tenant, body(sourceRevision = 2, lines = line("SR-STD", "1.00")))
            refused.status shouldBe 409
            json(refused).get("code").asString() shouldBe "ING-002"
            count(tenant) shouldBe listOf("1")
        }

    @Test
    fun `INV-REQ-434 revision 3 after a superseded revision 2 follows the same rule against the current aggregate`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            boundary.send(tenant, body(sourceRevision = 2)).status shouldBe 202
            val three =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 3, lines = """{"internalTaxCode":"SR-STD","amount":"900.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            three["documentNumber"].asString() shouldBe one["documentNumber"].asString()
            query(
                "select source_revision::text from aggregation.aggregate where tenant_id = ? and superseded_by is null",
                tenant,
            ) { it.getString(1) } shouldBe listOf("3")
            count(tenant) shouldBe listOf("3")
        }

    @Test
    fun `INV-REQ-434 revision 3 replaces an untransmitted supplementary aggregate and stores only the difference`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()

            fun sr(s: String) =
                """{"internalTaxCode":"SR-STD","amount":"$s"},{"internalTaxCode":"ZR-EXP","amount":"500.00"}"""
            boundary.send(tenant, body(lines = sr("1000.00"))).status shouldBe 202
            transmit(tenant)
            val before = rowOf(tenant, 1)
            val two = json(boundary.send(tenant, body(sourceRevision = 2, lines = sr("1200.00"))))
            lines(tenant, two["aggregateId"].asString()) shouldBe listOf("SR-STD=200.00")
            val three =
                json(
                    boundary.send(tenant, body(sourceRevision = 3, lines = sr("1500.00"))).also {
                        it.status shouldBe
                            202
                    },
                )
            three["documentNumber"].asString() shouldBe two["documentNumber"].asString()
            lines(tenant, three["aggregateId"].asString()) shouldBe listOf("SR-STD=500.00")
            rowOf(tenant, 1) shouldBe before
            query(
                "select l.internal_tax_code || '=' || sum(l.amount::numeric) from aggregation.aggregate_line l " +
                    "join aggregation.aggregate a on a.aggregate_id = l.aggregate_id where a.tenant_id = ? " +
                    "and a.superseded_by is null group by l.internal_tax_code order by 1",
                tenant,
            ) { it.getString(1) } shouldBe listOf("SR-STD=1500.00", "ZR-EXP=500.00")
        }

    @Test
    fun `INV-REQ-434 another tenant's or another entity's aggregate with the same sourceKey is never touched`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val other = boundary.tenant()
            boundary.send(tenant, body(entityRef = "E-002")).status shouldBe 202
            boundary.send(other, body()).status shouldBe 202
            transmit(other)
            val entityBefore = rowOf(tenant, 1)
            val otherBefore = rowOf(other, 1)
            boundary.send(tenant, body()).status shouldBe 202
            boundary.send(tenant, body(sourceRevision = 2)).status shouldBe 202
            query(
                "select row_to_json(a)::text from aggregation.aggregate a where tenant_id = ? and entity_ref = 'E-002'",
                tenant,
            ) { it.getString(1) } shouldBe listOf(entityBefore)
            rowOf(other, 1) shouldBe otherBefore
            count(other) shouldBe listOf("1")
        }
}
