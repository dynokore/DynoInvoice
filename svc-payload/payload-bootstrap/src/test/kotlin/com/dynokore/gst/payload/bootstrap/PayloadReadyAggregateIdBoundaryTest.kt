package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.build.ActivePackRead
import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import com.dynokore.gst.payload.adapters.events.OutboxPayloadReadyPublisher
import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.adapters.build.JooqBuildInputsReader
import com.dynokore.gst.payload.adapters.build.JooqBuildOutcomes
import com.dynokore.gst.payload.adapters.build.JooqTypeDecisionReader
import com.dynokore.gst.payload.adapters.build.OutboxPayloadRejectedPublisher
import com.dynokore.gst.payload.adapters.build.PintSgPayloadProjector
import com.dynokore.gst.payload.application.build.BuildInputsReader
import com.dynokore.gst.payload.application.build.BuildOutcomes
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.PayloadProjector
import com.dynokore.gst.payload.application.build.PayloadRejectedPublisher
import com.dynokore.gst.payload.application.build.TypeDecisionReader
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-22810 at the service boundary on a real database (P109): the `aggregateId` of a consumed
 * `CanonicalVersionCreated` event is stored by the production consumer and reaches the `PayloadReady` outbox row built
 * by the `BuildPayload` bean; a version without it publishes no such member. The two inbox consumers, the build use
 * case and its ports are the beans of the TPL-18 context. Only the event-log read is a list in memory, and the family
 * and the active pack (not ruled, see the MR gaps) are test beans, as in `BuildPayloadJobDbTest`.
 */
class PayloadReadyAggregateIdBoundaryTest {
    private companion object {
        const val PACK = """{"aggregateDefaults":[{"aggregateType":"POS","businessTermId":"IBT-022","literal":"POS"}]}"""
    }

    private val mapper = ObjectMapper()
    private val envelopes = EnvelopeJson()
    private val readyType = OutboxPayloadReadyPublisher.EVENT_TYPE

    /** One log for both topics; offsets start above any offset another test stored for the same consumer name. */
    private class Log : EventLogReader {
        private var next = System.currentTimeMillis() * 1000
        private val records = mutableListOf<Pair<String, LogRecord>>()

        fun add(
            topic: String,
            envelopeJson: String,
        ) {
            records += topic to LogRecord(next++, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> =
            records
                .filter { it.first == topic && it.second.offset > afterOffset }
                .map { it.second }
                .take(limit)
    }

    /** Claims only the items of the given documents; a job another test left queued is completed unrun. */
    private class DocsQueue(
        private val delegate: WorkQueue,
        private val scope: TenantScope,
        private val docs: Set<String>,
    ) : WorkQueue by delegate {
        override fun claimNext(): ClaimedItem? {
            while (true) {
                val item = delegate.claimNext() ?: return null
                if (item.partitionKey in docs) return item
                scope.bindWorkload(item.tenant) { scope.transactions.inTransaction { delegate.complete(item) } }
            }
        }
    }

    private fun run(
        dir: Path,
        block: (ApplicationContext, Log, TenantId) -> Unit,
    ) {
        val log = Log()
        PayloadContext
            .online(dir)
            .withAllowBeanDefinitionOverriding(true)
            .withBean(
                "specificationFamilySource",
                SpecificationFamilySource::class.java,
                { SpecificationFamilySource { "PINT_SG" } },
            ).withBean(
                ActivePackOffNetworkPrefix::class.java,
                { ActivePackOffNetworkPrefix { ActivePackRead("test-pack", MdePackOffNetworkPrefix(PACK), PACK) } },
            ).withBean(
                "eventLogReaderOfTheTest",
                EventLogReader::class.java,
                { log },
                BeanDefinitionCustomizer { it.isPrimary = true },
            ).run { ctx -> block(ctx, log, TenantId(UUID.randomUUID())) }
    }

    private fun document(aggregateType: String?): String {
        val member = aggregateType?.let { """"aggregateType":{"value":"$it"},""" }.orEmpty()
        return """
        {$member"documentTypeCode":{"value":"380","classification":"NONE"},
         "parties":{"value":{
           "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A"},
           "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A"}},"classification":"IDENTIFYING"},
         "documentNumber":{"value":"INV-2026-000123","classification":"NONE"},
         "documentDate":{"value":"2026-03-20","classification":"NONE"},
         "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A","supplierGstn":"M2-1234567-8"},
           "classification":"IDENTIFYING"}}
        """.trimIndent()
    }

    private fun envelope(
        tenant: TenantId,
        id: Long,
        doc: UUID,
        type: String,
        data: String,
    ) = envelopes.write(
        Envelope(
            id = EventId("ev-$id"),
            source = "/gst/test",
            type = type,
            subject = doc.toString(),
            time = Instant.parse("2026-10-20T10:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E-001"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 1, 15),
            docSequence = id,
            instanceId = "test-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        ),
    )

    private fun created(
        tenant: TenantId,
        id: Long,
        doc: UUID,
        aggregateId: String?,
    ): String {
        val aggregate = aggregateId?.let { ""","aggregateId":"$it"""" }.orEmpty()
        val routing = ""","routingFacts":{"direction":"SALE","counterpartyOnNetwork":true}"""
        return envelope(
            tenant,
            id,
            doc,
            SubmissionTypeDecidedConsumerConfiguration.VERSION_FACTS_TYPE,
            """{"docUuid":"$doc","version":1,"canonicalDocument":${document(if (aggregateId == null) null else "POS")}$routing$aggregate}""",
        )
    }

    private fun decided(
        tenant: TenantId,
        id: Long,
        doc: UUID,
    ) = envelope(
        tenant,
        id,
        doc,
        SubmissionTypeDecidedConsumerConfiguration.TYPE,
        """{"docUuid":"$doc","version":1,"docsequence":3,"submissionType":"TYPE_2"}""",
    )

    private fun row(
        tenant: TenantId,
        doc: UUID,
    ): JsonNode =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select data::text from payload.outbox where tenant_id = ? and doc_uuid = ? and type = ?",
                ).use { p ->
                    p.setObject(1, tenant.value)
                    p.setObject(2, doc)
                    p.setString(3, readyType)
                    p.executeQuery().use { r ->
                        check(r.next()) { "no PayloadReady row for $doc" }
                        mapper.readTree(r.getString(1)).also { check(!r.next()) { "two rows for $doc" } }
                    }
                }
        }

    private val applied1 = PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)

    private fun members(row: JsonNode): Set<String> = row.propertyNames().toSet()

    @Test
    fun `INV-AC-22810 PayloadReady carries the aggregateId of the consumed event and none when the event had none`(
        @TempDir dir: Path,
    ) = run(dir) { ctx, log, tenant ->
        val d = UUID.randomUUID()
        val e = UUID.randomUUID()
        val versionFacts = ctx.getBean("versionFactsConsumer", JdbcInboxConsumer::class.java)
        val typeDecisions = ctx.getBean("submissionTypeDecidedConsumer", JdbcInboxConsumer::class.java)
        val canonicalTopic = VersionFactsConsumerConfiguration.TOPIC
        val decidedTopic = SubmissionTypeDecidedConsumerConfiguration.TOPIC
        log.add(canonicalTopic, created(tenant, 1, d, "AGG-2026-0001"))
        versionFacts.pollOnce() shouldBe applied1
        log.add(canonicalTopic, created(tenant, 2, e, null))
        versionFacts.pollOnce() shouldBe applied1
        log.add(decidedTopic, decided(tenant, 3, d))
        typeDecisions.pollOnce() shouldBe applied1
        log.add(decidedTopic, decided(tenant, 4, e))
        typeDecisions.pollOnce() shouldBe applied1

        // composition: every port of the build use case is the production bean; the test supplies only two beans
        ctx.getBean(BuildInputsReader::class.java).javaClass shouldBe JooqBuildInputsReader::class.java
        ctx.getBean(TypeDecisionReader::class.java).javaClass shouldBe JooqTypeDecisionReader::class.java
        ctx.getBean(BuildOutcomes::class.java).javaClass shouldBe JooqBuildOutcomes::class.java
        ctx.getBean(PayloadProjector::class.java).javaClass shouldBe PintSgPayloadProjector::class.java
        ctx.getBean(PayloadRejectedPublisher::class.java).javaClass shouldBe OutboxPayloadRejectedPublisher::class.java

        val scope = ctx.getBean(TenantScope::class.java)
        val handler =
            BuildPayloadJobHandler(
                DocsQueue(ctx.getBean(WorkQueue::class.java), scope, setOf(d.toString(), e.toString())),
                scope,
                ctx.getBean(BuildPayload::class.java),
            )
        handler.runOnce() shouldBe true
        handler.runOnce() shouldBe true

        val rd = row(tenant, d)
        val re = row(tenant, e)
        rd["aggregateId"].asString() shouldBe "AGG-2026-0001"
        re.has("aggregateId") shouldBe false
        members(re) shouldBe members(rd) - "aggregateId"
        members(rd) shouldBe
            setOf(
                "docUuid",
                "version",
                "docsequence",
                "submissionType",
                "specificationVersion",
                "payloadBytes",
                "payloadSha256",
                "aggregateId",
            )
        for (r in listOf(rd, re)) {
            val bytes = Base64.getDecoder().decode(r["payloadBytes"].asString())
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) shouldBe
                r["payloadSha256"].asString()
        }
        // D and E hold the same document except aggregateType: the bytes differ only by the one POS note (IBT-022)
        val note = Regex("<(?:\\w+:)?Note(?:\\s[^>]*)?>POS</(?:\\w+:)?Note>")
        val xmlD = String(Base64.getDecoder().decode(rd["payloadBytes"].asString()), Charsets.UTF_8)
        val xmlE = String(Base64.getDecoder().decode(re["payloadBytes"].asString()), Charsets.UTF_8)
        note.findAll(xmlD).count() shouldBe 1
        note.findAll(xmlE).count() shouldBe 0
        note.replace(xmlD, "") shouldBe xmlE
    }
}
