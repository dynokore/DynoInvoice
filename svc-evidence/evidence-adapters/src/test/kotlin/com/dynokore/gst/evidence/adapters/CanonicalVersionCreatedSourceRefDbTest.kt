package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** INV-AC-24167: the sourceDocumentRef of each canonical version is stored once per tenant, document and version. */
class CanonicalVersionCreatedSourceRefDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val d1 = UUID.randomUUID()
    private val d3 = UUID.randomUUID()
    private val d5 = UUID.randomUUID()

    private fun <T> tx(
        tenant: UUID,
        block: (DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            block(DSL.using(c, SQLDialect.POSTGRES)).also { c.commit() }
        }

    private fun handle(
        tenant: UUID,
        eventId: String,
        doc: UUID,
        version: Int,
        ref: String?,
    ) = tx(tenant) { dsl ->
        val member = ref?.let { ""","sourceDocumentRef":{"value":$it,"classification":"NONE"}""" }.orEmpty()
        val data =
            """{"docUuid":"$doc","version":$version,"prescribedPeriodRef":"2026-Q1","transactionDate":"2026-01-15",""" +
                """"traceReference":"tr","canonicalDocument":{"documentNumber":{"value":"INV-1"}$member}}"""
        CanonicalVersionCreatedHandler(ChainWiring.locatorRecorder(dsl)).handle(
            Envelope(
                id = EventId(eventId),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                subject = "s",
                time = Instant.parse("2026-01-15T00:00:00Z"),
                tenant = TenantId(tenant),
                entity = EventEntityRef("ENT-1"),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = version.toLong(),
                instanceId = "i-0",
                correlation = CorrelationId("c"),
                causation = CausationId("c"),
                dataJson = data,
            ),
        )
    }

    /** (doc, version, ref) rows visible to [tenant] through the runtime login (row-level security applies). */
    private fun rows(tenant: UUID): List<Triple<UUID, Int, String?>> =
        tx(tenant) { dsl ->
            dsl
                .resultQuery("select doc_uuid, version, source_document_ref from evidence.document_locator")
                .fetch()
                .map { Triple(it.get(0, UUID::class.java), it.get(1, Int::class.java), it.get(2, String::class.java)) }
        }

    private fun run() {
        handle(t1, "e1", d1, 1, "\"dms://t1/a\"")
        handle(t1, "e2", d1, 2, null)
        handle(t2, "e3", d3, 1, "\"dms://t2/c\"")
        handle(t1, "e1", d1, 1, "\"dms://t1/a\"")
    }

    private fun refused(sql: String): SQLException =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t1)
            val failure = runCatching { c.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            c.rollback()
            failure as SQLException
        }

    @Test
    fun `INV-AC-24167 E1 stores the reference and E2 without the member stores null`() {
        run()
        val r = rows(t1)
        r.single { it.first == d1 && it.second == 1 }.third shouldBe "dms://t1/a"
        r.single { it.first == d1 && it.second == 2 }.third shouldBe null
    }

    @Test
    fun `INV-AC-24167 find returns the stored reference of the version and null for E2`() {
        run()
        tx(t1) { JooqLocatorStore(it).find(TenantId(t1), d1, 1)?.sourceDocumentRef } shouldBe "dms://t1/a"
        tx(t1) { JooqLocatorStore(it).find(TenantId(t1), d1, 2)?.sourceDocumentRef } shouldBe null
        tx(t1) { JooqLocatorStore(it).find(TenantId(t1), d3, 1) } shouldBe null
    }

    @Test
    fun `INV-AC-24167 E4 equal to E1 leaves exactly one locator for the version`() {
        run()
        rows(t1).count { it.first == d1 && it.second == 1 } shouldBe 1
    }

    @Test
    fun `INV-AC-24167 reading under T2 returns the T2 row and no row of T1`() {
        run()
        rows(t2) shouldBe listOf(Triple(d3, 1, "dms://t2/c"))
    }

    @Test
    fun `INV-AC-24167 E5 with a numeric value is a ContractViolation and stores no locator`() {
        run()
        shouldThrow<ContractViolation> { handle(t1, "e5", d5, 1, "7") }
        rows(t1).none { it.first == d5 } shouldBe true
    }

    @Test
    fun `INV-AC-24167 a blank value and a value over 512 characters are refused and 512 is accepted`() {
        shouldThrow<ContractViolation> { handle(t1, "b1", d5, 1, "\"  \"") }
        shouldThrow<ContractViolation> { handle(t1, "b2", d5, 1, "\"" + "x".repeat(513) + "\"") }
        rows(t1).none { it.first == d5 } shouldBe true
        handle(t1, "b3", d5, 1, "\"" + "x".repeat(512) + "\"")
        rows(t1).single { it.first == d5 }.third!!.length shouldBe 512
    }

    @Test
    fun `INV-AC-24167 the runtime login cannot update or delete a locator row`() {
        run()
        refused("update evidence.document_locator set source_document_ref = 'x'").sqlState shouldBe "42501"
        refused("delete from evidence.document_locator").sqlState shouldBe "42501"
    }

    @Test
    fun `INV-AC-24167 the check refuses a blank or oversize value written directly`() {
        EvidenceDb.superuser().use { c ->
            for (v in listOf("' '", "'" + "x".repeat(513) + "'")) {
                val failure =
                    runCatching {
                        c.createStatement().use {
                            it.execute(
                                "insert into evidence.document_locator (tenant_id, doc_uuid, version, entity_ref, " +
                                    "period_ref, transaction_date, trace_reference, source_event_id, recorded_at, " +
                                    "source_document_ref) values ('$t1', '${UUID.randomUUID()}', 1, 'E', 'P', " +
                                    "'2026-01-15', 't', '${UUID.randomUUID()}', now(), $v)",
                            )
                        }
                    }.exceptionOrNull()
                (failure as SQLException).sqlState shouldBe "23514"
            }
        }
    }
}
