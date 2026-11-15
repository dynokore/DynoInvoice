package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.events.OutboxPayloadReadyPublisher
import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-22859 against a real database (P109): the full TPL-18 context over the `payload_runtime` login. The
 * publisher stages one `PayloadReady` outbox row in the caller's transaction.
 */
class PayloadReadyOutboxDbTest {
    private val actor = "svc:payload-svc"
    private val mapper = ObjectMapper()
    private val bytes = "<Invoice>café</Invoice>".toByteArray(Charsets.UTF_8)

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val publish: PublishPayloadReady = context.getBean(PublishPayloadReady::class.java)
        val ctx: ApplicationContext = context
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        PayloadContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun facts(
        doc: UUID,
        aggregateId: String?,
    ) = PayloadReadyFacts(
        entityRef = "E-001",
        docUuid = doc,
        version = 1,
        submissionType = "1A",
        specificationVersion = "PINT-SG-1",
        payloadBytes = bytes,
        payloadSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        aggregateId = aggregateId,
    )

    private class Row(
        val docSequence: Long,
        val data: JsonNode,
    )

    /** The PayloadReady rows of one tenant, read as the database owner with an explicit tenant filter. */
    private fun rows(tenant: UUID): Map<String, Row> =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select doc_uuid, docsequence, data::text from payload.outbox where tenant_id = ? and type = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setString(2, OutboxPayloadReadyPublisher.EVENT_TYPE)
                    p.executeQuery().use { r ->
                        buildMap {
                            while (r.next()) {
                                put(
                                    r.getObject(1).toString(),
                                    Row(r.getLong(2), mapper.readTree(r.getString(3))),
                                )
                            }
                        }
                    }
                }
        }

    @Test
    fun `INV-AC-22859 the event type is catalogued on its topic and the wiring supplies the publisher`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        PayloadEvents.topicByType[OutboxPayloadReadyPublisher.EVENT_TYPE] shouldBe "gst.payload.payload-ready.v1"
        b.ctx.getBean("payloadReadyPublisher").javaClass shouldBe OutboxPayloadReadyPublisher::class.java
    }

    @Test
    fun `INV-AC-22859 the row carries the seven members with base64 bytes and aggregateId only when set`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val d = UUID.randomUUID()
        val e = UUID.randomUUID()
        b.scope.bind(TenantId(tenant), actor) {
            b.runner.inTransaction {
                b.publish.publish(facts(d, "AGG-2026-0001"))
                b.publish.publish(facts(e, null))
            }
        }
        val rows = rows(tenant)
        val rd = rows.getValue(d.toString())
        rd.data["docUuid"].asString() shouldBe d.toString()
        rd.data["version"].asInt() shouldBe 1
        rd.data["docsequence"].asLong() shouldBe rd.docSequence
        rd.data["submissionType"].asString() shouldBe "1A"
        rd.data["specificationVersion"].asString() shouldBe "PINT-SG-1"
        rd.data["payloadBytes"].asString() shouldBe Base64.getEncoder().encodeToString(bytes)
        rd.data["payloadSha256"].asString() shouldBe facts(d, null).payloadSha256
        rd.data["aggregateId"].asString() shouldBe "AGG-2026-0001"
        val re = rows.getValue(e.toString())
        re.data.has("aggregateId") shouldBe false
        re.data["docsequence"].asLong() shouldBe re.docSequence
    }

    @Test
    fun `INV-AC-22859 a publish that fails after the stage leaves no row and another tenant reads none`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        shouldThrow<IllegalStateException> {
            b.scope.bind(TenantId(tenant), actor) {
                b.runner.inTransaction {
                    b.publish.publish(facts(UUID.randomUUID(), "AGG-2026-0002"))
                    error("fails after the stage")
                }
            }
        }
        rows(tenant).size shouldBe 0
        val committed = UUID.randomUUID()
        b.scope.bind(TenantId(tenant), actor) { b.runner.inTransaction { b.publish.publish(facts(committed, null)) } }
        rows(tenant).size shouldBe 1
        rows(UUID.randomUUID()).size shouldBe 0
    }

    private fun schemaErrors(row: JsonNode): List<String> {
        val dir = checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" }
        val file = File(dir, "schemas/payload-ready-payload.schema.json")
        check(file.isFile) { "the published schema is missing: $file" }
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry
            .getSchema(file.readText())
            .validate(row.toString(), com.networknt.schema.InputFormat.JSON)
            .map { it.message }
    }

    @Test
    fun `INV-AC-22859 the stored row validates against the published schema and fails without payloadSha256`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val d = UUID.randomUUID()
        b.scope.bind(TenantId(tenant), actor) {
            b.runner.inTransaction { b.publish.publish(facts(d, "AGG-2026-0001")) }
        }
        val data = rows(tenant).getValue(d.toString()).data
        schemaErrors(data).shouldBeEmpty()
        val without = data.deepCopy() as ObjectNode
        without.remove("payloadSha256")
        schemaErrors(without).shouldNotBeEmpty()
    }
}
