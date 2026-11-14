package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The two entity node consumers against a real database (P109, INV-AC-22283): the library `JdbcInboxConsumer` drives
 * the real handler beans over `submission.entity_node`, and `EntityGstns` resolves the stored GSTN.
 */
class EntityNodeConsumersDbTest {
    private val json = EnvelopeJson()

    private class ListReader(
        val records: MutableList<LogRecord> = mutableListOf(),
    ) : EventLogReader {
        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private inner class Kit(
        val context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val gstns: EntityGstns = context.getBean(EntityGstns::class.java)
        val structureHandler: EventHandler =
            context.getBean("organisationStructureChangedHandler", EventHandler::class.java)
        val profileHandler: EventHandler =
            context.getBean("entityNodeProfileChangedHandler", EventHandler::class.java)
        val tenant: UUID = UUID.randomUUID()
        val other: UUID = UUID.randomUUID()
        private val structureLog = ListReader()
        private val profileLog = ListReader()
        private var offset = 0L

        private fun consumer(
            subscription: ConsumerSubscription,
            reader: ListReader,
            handler: EventHandler,
        ) = JdbcInboxConsumer(
            ConsumerSubscription("t22286-" + UUID.randomUUID(), subscription.topic, subscription.schema),
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables("submission"), tenants, runner),
            handler,
            RebuildRequiredSink { },
        )

        val structureConsumer = consumer(EntityNodeConsumers.structureSubscription(), structureLog, structureHandler)
        val profileConsumer = consumer(EntityNodeConsumers.profileSubscription(), profileLog, profileHandler)

        fun structure(
            version: Long,
            gstn: String,
        ) = deliver(
            structureLog,
            SubmissionEvents.ORGANISATION_STRUCTURE_CHANGED_TYPE,
            """{"nodeType":"ENTITY","nodeRef":"ENT-1","changeType":"UPDATED","node":${node(version, gstn)}}""",
        )

        fun profile(
            version: Long,
            gstn: String,
        ) = deliver(
            profileLog,
            SubmissionEvents.ENTITY_PROFILE_CHANGED_TYPE,
            """{"entityRef":"ENT-1","changedAttributes":[],"effectiveDate":"2026-07-01","node":${node(
                version,
                gstn,
            )}}""",
        )

        private fun node(
            version: Long,
            gstn: String,
        ) = """{"kind":"ENTITY","parentRef":"GRP-1","gstn":"$gstn","structureVersion":$version}"""

        private fun deliver(
            reader: ListReader,
            type: String,
            data: String,
        ) {
            val envelope =
                Envelope(
                    id = EventId(UUID.randomUUID().toString()),
                    source = "/gst/tenancy-svc",
                    type = type,
                    subject = "ENT-1",
                    time = Instant.parse("2026-11-12T08:00:00Z"),
                    tenant = TenantId(tenant),
                    entity = EntityRef("ENT-1"),
                    docUuid = DocUuid(UUID.randomUUID()),
                    transactionDate = LocalDate.parse("2026-03-20"),
                    docSequence = null,
                    instanceId = "tenancy-svc-0",
                    correlation = CorrelationId("corr-1"),
                    causation = CausationId("cause-1"),
                    dataJson = data,
                )
            reader.records += LogRecord(++offset, json.write(envelope))
        }

        fun gstnOf(id: UUID = tenant): String? =
            tenants.bind(TenantId(id), "svc:submission-svc") { gstns.gstnOf(TenantId(id), "ENT-1") }
    }

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).block() }
    }

    @Test
    fun `INV-AC-22283 version 4141 wins over 4140 and a later 4140 changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        structure(4140, "M90000002B")
        structureConsumer.pollOnce().applied shouldBe 1
        gstnOf() shouldBe "M90000002B"
        profile(4141, "M90000009Z")
        profileConsumer.pollOnce().applied shouldBe 1
        gstnOf() shouldBe "M90000009Z"
        structure(4140, "M90000002B")
        structureConsumer.pollOnce().ignored shouldBe 1
        gstnOf() shouldBe "M90000009Z"
        gstnOf(other) shouldBe null
    }

    @Test
    fun `INV-AC-22283 the order of the two topics does not matter`(
        @TempDir dir: Path,
    ) = run(dir) {
        profile(4141, "M90000009Z")
        profileConsumer.pollOnce().applied shouldBe 1
        structure(4140, "M90000002B")
        structureConsumer.pollOnce().ignored shouldBe 1
        gstnOf() shouldBe "M90000009Z"
    }

    @Test
    fun `INV-AC-22283 a catalogue listing the type on another topic or not at all makes each consumer refuse to build`(
        @TempDir dir: Path,
    ) = run(dir) {
        val config = EntityNodeConsumers()

        fun structureBuild(catalogue: EventCatalogue) =
            config.organisationStructureChangedConsumer(
                catalogue,
                ListReader(),
                scope,
                structureHandler,
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )

        fun profileBuild(catalogue: EventCatalogue) =
            config.entityNodeProfileChangedConsumer(
                catalogue,
                ListReader(),
                scope,
                profileHandler,
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
        structureBuild(catalogue)
        profileBuild(catalogue)

        fun moved(type: String) =
            EventCatalogue(
                catalogue.entries().filter { it.eventType != type } +
                    CatalogueEntry(
                        type,
                        "gst.tenancy.other.v1",
                        setOf("tenancy-svc"),
                        contentTopic = false,
                        retainedWhileResolvable = false,
                    ),
            )

        fun without(type: String) = EventCatalogue(catalogue.entries().filter { it.eventType != type })
        val s = SubmissionEvents.ORGANISATION_STRUCTURE_CHANGED_TYPE
        val p = SubmissionEvents.ENTITY_PROFILE_CHANGED_TYPE
        shouldThrow<IllegalStateException> { structureBuild(moved(s)) }
        shouldThrow<IllegalStateException> { structureBuild(without(s)) }
        shouldThrow<IllegalStateException> { profileBuild(moved(p)) }
        shouldThrow<IllegalStateException> { profileBuild(without(p)) }
    }
}
