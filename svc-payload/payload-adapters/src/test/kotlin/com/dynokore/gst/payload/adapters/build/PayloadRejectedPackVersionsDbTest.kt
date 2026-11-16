package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.SignedBindings
import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.PackVersionFact
import com.dynokore.gst.payload.application.build.PayloadRejectedFacts
import com.dynokore.gst.payload.application.build.Projection
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * INV-AC-23594: the staged `PayloadRejected` row against the real database as `payload_runtime` with a signed binding.
 * The row `data` is compared to the whole expected JSON by equality.
 */
class PayloadRejectedPackVersionsDbTest {
    private val mapper = ObjectMapper()
    private val tenant = UUID.randomUUID()

    private val catalogue =
        EventCatalogue(
            listOf(
                CatalogueEntry(
                    OutboxPayloadRejectedPublisher.EVENT_TYPE,
                    "gst.payload.payload-rejected.v1",
                    setOf("payload-svc"),
                    contentTopic = false,
                    retainedWhileResolvable = false,
                ),
            ),
        )

    private val tenants =
        object : TenantContext {
            override fun current() = TenantId(tenant)

            override fun currentActor(): String? = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun stage(facts: PayloadRejectedFacts) {
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val writer =
                JdbcOutboxWriter(
                    DSL.using(c, SQLDialect.POSTGRES),
                    "payload",
                    "payload-svc",
                    "test-1",
                    catalogue,
                    Clock.systemUTC(),
                )
            OutboxPayloadRejectedPublisher(writer, tenants).stage(facts)
            c.commit()
        }
    }

    /** The one row of [doc]: its `docsequence` column and its `data`. */
    private fun row(doc: UUID): Pair<Long, JsonNode> =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select docsequence, data::text from payload.outbox " +
                        "where tenant_id = ? and doc_uuid = ? and type = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setObject(2, doc)
                    p.setString(3, OutboxPayloadRejectedPublisher.EVENT_TYPE)
                    p.executeQuery().use { r ->
                        check(r.next()) { "no PayloadRejected row for $doc" }
                        (r.getLong(1) to mapper.readTree(r.getString(2))).also { check(!r.next()) { "two rows" } }
                    }
                }
        }

    @Test
    fun `INV-AC-23594 staged event carries one packVersions item`() {
        val doc = UUID.randomUUID()
        stage(
            PayloadRejectedFacts(
                "E-001",
                doc,
                2,
                null,
                "{\"finding\":\"BLOCK\"}",
                listOf(PackVersionFact("MDE", "MDE-7")),
            ),
        )
        val (sequence, data) = row(doc)
        data shouldBe
            mapper.readTree(
                """{"docUuid":"$doc","version":2,"docsequence":$sequence,"validationTrace":{"finding":"BLOCK"},
                    "packVersions":[{"packType":"MDE","version":"MDE-7"}]}""",
            )
    }

    @Test
    fun `INV-AC-23594 staged event without a pack has no packVersions member`() {
        val none = UUID.randomUUID()
        val empty = UUID.randomUUID()
        val pay010 = UUID.randomUUID()
        stage(PayloadRejectedFacts("E-001", none, 1, null, "{\"finding\":\"BLOCK\"}", null))
        stage(PayloadRejectedFacts("E-001", empty, 1, null, "{\"finding\":\"BLOCK\"}", emptyList()))
        stage(PayloadRejectedFacts("E-001", pay010, 1, "PAY-010", null))
        listOf(none, empty).forEach { doc ->
            val (sequence, data) = row(doc)
            data shouldBe
                mapper.readTree(
                    """{"docUuid":"$doc","version":1,"docsequence":$sequence,"validationTrace":{"finding":"BLOCK"}}""",
                )
        }
        val (sequence, data) = row(pay010)
        data shouldBe
            mapper.readTree(
                """{"docUuid":"$pay010","version":1,"docsequence":$sequence,
                    "validationTrace":{"code":"PAY-010"},"code":"PAY-010"}""",
            )
    }

    @Test
    fun `INV-AC-23594 a blocked projection names the version of its one pack read`() {
        var reads = 0
        val projector =
            PintSgPayloadProjector({ "PINT_SG" }, {
                reads++
                ActivePackRead("MDE-$reads", MdePackOffNetworkPrefix("{}"), "{}")
            })
        val document =
            """{"documentTypeCode":{"value":"999"},"parties":{"value":{
               "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A"},
               "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A"}}}}"""
        val blocked =
            projector.project(
                BuildInput("E-001", document, null, "SALE", true),
                "TYPE_2",
            ) as Projection.Blocked
        reads shouldBe 1
        blocked.packVersion shouldBe "MDE-1"
        val early = projector.project(BuildInput("E-001", document, null, null, true), "TYPE_2") as Projection.Blocked
        early.packVersion shouldBe null
        reads shouldBe 1
    }
}
