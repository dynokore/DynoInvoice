package com.dynokore.gst.canonical.adapters

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.canonical.adapters.events.MasterDataResolvedHandler
import com.dynokore.gst.canonical.application.document.DocumentReleaseStore
import com.dynokore.gst.canonical.application.document.DocumentStates
import com.dynokore.gst.canonical.application.document.HoldableDocument
import com.dynokore.gst.canonical.application.document.ReleaseMasterDataHold
import com.dynokore.gst.canonical.application.document.ReleasedEvent
import com.dynokore.gst.canonical.application.document.ReleasedEventPublisher
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-21250 at the handler: what it reads, what it calls, what it returns, what it refuses. */
class MasterDataResolvedHandlerTest {
    private val d1 = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val d2 = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val d3 = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val d5 = UUID.fromString("00000000-0000-4000-8000-000000000005")

    private class Memory : DocumentReleaseStore {
        val docs = mutableMapOf<UUID, HoldableDocument>()
        var locks = 0
        val released = mutableListOf<UUID>()

        override fun lockForRelease(docUuid: UUID): HoldableDocument? {
            locks++
            return docs[docUuid]
        }

        override fun markReleased(docUuid: UUID) {
            released += docUuid
        }
    }

    private val memory = Memory()
    private val staged = mutableListOf<ReleasedEvent>()
    private val handler =
        MasterDataResolvedHandler(
            ReleaseMasterDataHold(
                memory,
                ReleasedEventPublisher { staged += it },
                object : TransactionRunner {
                    override fun <T> inTransaction(block: () -> T): T = block()
                },
            ),
        )

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(MasterDataResolvedHandler::class.java) as Logger

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun doc(
        id: UUID,
        state: String,
        reasons: List<String>,
    ) = HoldableDocument(id, "E-1", Direction.SALE, LocalDate.of(2026, 3, 20), "2026-Q1", state, reasons)

    private fun seed() {
        memory.docs[d1] = doc(d1, DocumentStates.HELD, listOf("MDM-001"))
        memory.docs[d2] = doc(d2, DocumentStates.HELD, listOf("MDM-001", "ING-016"))
        memory.docs[d3] = doc(d3, DocumentStates.INGESTED, emptyList())
    }

    private fun event(data: String) =
        Envelope(
            id = EventId("ev-1"),
            source = "/gst/masterdata-svc",
            type = "com.dynokore.gst.masterdata.MasterDataResolved.v1",
            subject = "party-1",
            time = Instant.parse("2026-11-12T10:00:00Z"),
            tenant = TenantId(UUID.randomUUID()),
            entity = EntityRef("E-1"),
            docUuid = DocUuid(d1),
            transactionDate = LocalDate.of(2026, 3, 20),
            docSequence = 1,
            instanceId = "masterdata-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun data(docUuids: String?) =
        """{"partyRef":"P-1","resolvedIdentifiers":[]${docUuids?.let { ""","docUuids":$it""" }.orEmpty()}}"""

    @Test
    fun `INV-AC-21250 the named documents are released, counted in one log line, and the outcome is APPLIED`() {
        seed()
        memory.docs[d5] = doc(d5, DocumentStates.HELD, listOf("MDM-001"))
        val ghost = UUID.fromString("00000000-0000-4000-8000-0000000000ff")
        handler.handle(event(data("""["$d1","$d2","$d3","$ghost"]"""))) shouldBe HandleOutcome.APPLIED
        memory.released shouldBe listOf(d1)
        staged.map { it.docUuid to it.releasedHoldReasons } shouldBe listOf(d1 to listOf("MDM-001"))
        appender.list.map { it.formattedMessage } shouldBe
            listOf("master data resolved: released 1, not-held 1, not-master-data 1, not-found 1")
    }

    @Test
    fun `INV-AC-21250 docUuids absent or null gives IGNORED and the use case is not called`() {
        seed()
        handler.handle(event(data(null))) shouldBe HandleOutcome.IGNORED
        handler.handle(event(data("null"))) shouldBe HandleOutcome.IGNORED
        memory.locks shouldBe 0
        memory.released shouldBe emptyList()
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21250 an empty docUuids gives IGNORED and the use case is not called`() {
        seed()
        handler.handle(event(data("[]"))) shouldBe HandleOutcome.IGNORED
        memory.locks shouldBe 0
        memory.released shouldBe emptyList()
        appender.list shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21250 docUuids read from partyRef is not a thing and a held document stays held`() {
        seed()
        handler.handle(event("""{"partyRef":"$d1","resolvedIdentifiers":[]}""")) shouldBe HandleOutcome.IGNORED
        memory.locks shouldBe 0
    }

    @Test
    fun `INV-AC-21250 a document that is not held gives IGNORED`() {
        seed()
        handler.handle(event(data("""["$d3"]"""))) shouldBe HandleOutcome.IGNORED
        memory.released shouldBe emptyList()
        appender.list.map { it.formattedMessage } shouldBe
            listOf("master data resolved: released 0, not-held 1, not-master-data 0, not-found 0")
    }

    @Test
    fun `INV-AC-21250 a malformed docUuids is refused with ContractViolation and nothing is called`() {
        seed()
        val malformed =
            listOf(
                """["not-a-uuid"]""",
                """["$d1","not-a-uuid"]""",
                "\"$d1\"",
                """{"a":"$d1"}""",
                "[1]",
                "[null]",
                "[[]]",
                """["1-1-1-1-1"]""",
                """["$d1 "]""",
                "true",
            )
        malformed.forEach { shape ->
            shouldThrow<ContractViolation> { handler.handle(event(data(shape))) }
        }
        memory.locks shouldBe 0
        memory.released shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21250 data that is not a JSON object is refused with ContractViolation`() {
        listOf("{not json", "[]", "\"x\"", "null", "").forEach { text ->
            shouldThrow<ContractViolation> { handler.handle(event(text)) }
        }
        memory.locks shouldBe 0
    }

    @Test
    fun `INV-AC-21250 a failure of the use case is not caught`() {
        seed()
        val failing =
            MasterDataResolvedHandler(
                ReleaseMasterDataHold(
                    memory,
                    ReleasedEventPublisher { error("stage fails") },
                    object : TransactionRunner {
                        override fun <T> inTransaction(block: () -> T): T = block()
                    },
                ),
            )
        shouldThrow<IllegalStateException> { failing.handle(event(data("""["$d1"]"""))) }
    }
}
