package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.build.JooqBuildInputsReader
import com.dynokore.gst.payload.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.payload.adapters.events.SubmissionTypeDecidedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqCanonicalInputStore
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.application.build.BuildInputs
import com.dynokore.gst.payload.application.build.BuildJobs
import com.dynokore.gst.payload.application.build.BuildTrigger
import com.dynokore.gst.payload.application.build.FamilyRequest
import com.dynokore.gst.payload.application.build.TypeDecisions
import com.dynokore.gst.payload.application.version.RecordVersionFacts
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-23663: the canonical input stores the optional `transactionDate`; the build input carries both facts. */
class TransactionDateStoreTest {
    private val tenant = UUID.randomUUID()
    private val doc = UUID.randomUUID()
    private val delivered = LocalDate.of(2026, 3, 20)

    private class Handlers(
        val canonical: CanonicalVersionCreatedHandler,
        val decided: SubmissionTypeDecidedHandler,
        val reader: JooqBuildInputsReader,
    )

    private fun <T> inTx(block: (Handlers) -> T): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                val dsl = DSL.using(c, SQLDialect.POSTGRES)
                val jobs = JooqBuildJobs(dsl, mockk(), { tenant })
                val trigger =
                    BuildTrigger(BuildInputs { _, _ -> false }, TypeDecisions { _, _ -> false }, BuildJobs { _, _ -> false })
                val facts = JooqVersionFactsStore(dsl) { tenant }
                block(
                    Handlers(
                        CanonicalVersionCreatedHandler(RecordVersionFacts(facts), JooqCanonicalInputStore(dsl, jobs) { tenant }, trigger),
                        SubmissionTypeDecidedHandler(JooqTypeDecisionStore(dsl, jobs) { tenant }, trigger),
                        JooqBuildInputsReader(dsl) { tenant },
                    ),
                )
            } finally {
                c.commit()
            }
        }

    private fun event(
        type: String,
        data: String,
    ) = Envelope(
        id = EventId("ev-1"),
        source = "/gst/test",
        type = type,
        subject = doc.toString(),
        time = Instant.parse("2026-10-20T10:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("E-1"),
        docUuid = DocUuid(doc),
        // the envelope date is the delivery date of the test: it must never be stored as the document date
        transactionDate = LocalDate.of(2020, 1, 1),
        docSequence = 1,
        instanceId = "test-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun created(
        member: String = "",
        version: Int = 1,
    ) = event(
        "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
        """{"docUuid":"$doc","version":$version,"canonicalDocument":{"documentNumber":{"value":"N-1"}}$member}""",
    )

    private fun decided(
        member: String = "",
        version: Int = 1,
    ) = event(
        "com.dynokore.gst.submission.SubmissionTypeDecided.v1",
        """{"docUuid":"$doc","version":$version,"docsequence":1,"submissionType":"TYPE_2"$member}""",
    )

    private fun storedDate(version: Int = 1): LocalDate? =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select transaction_date from payload.canonical_input where tenant_id = ? and doc_uuid = ? and version = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setObject(2, doc)
                    p.setInt(3, version)
                    p.executeQuery().use { r ->
                        check(r.next()) { "no canonical input row" }
                        r.getObject(1, LocalDate::class.java)
                    }
                }
        }

    @Test
    fun `INV-AC-23663 transactionDate is stored with the canonical input`() {
        inTx { it.canonical.handle(created(""","transactionDate":"$delivered"""")) } shouldBe HandleOutcome.APPLIED
        storedDate() shouldBe delivered
        inTx { it.canonical.handle(created(version = 2)) } shouldBe HandleOutcome.APPLIED
        storedDate(2) shouldBe null
        inTx { it.canonical.handle(created(""","transactionDate":null""", version = 3)) } shouldBe HandleOutcome.APPLIED
        storedDate(3) shouldBe null
    }

    @Test
    fun `INV-AC-23663 a malformed transactionDate is a contract violation`() {
        listOf(""","transactionDate":"2026-13-40"""", ""","transactionDate":20260320""", ""","transactionDate":"20/03/2026"""")
            .forEach { member -> shouldThrow<ContractViolation> { inTx { it.canonical.handle(created(member)) } } }
    }

    @Test
    fun `INV-AC-23663 a redelivery keeps the first values`() {
        inTx { it.canonical.handle(created(""","transactionDate":"$delivered"""")) } shouldBe HandleOutcome.APPLIED
        inTx { it.canonical.handle(created(""","transactionDate":"2027-01-01"""")) } shouldBe HandleOutcome.IGNORED
        storedDate() shouldBe delivered
        inTx { it.decided.handle(decided(""","connectionRef":"CONN-1"""")) } shouldBe HandleOutcome.APPLIED
        inTx { it.decided.handle(decided(""","connectionRef":"CONN-2"""")) } shouldBe HandleOutcome.IGNORED
        inTx { it.reader.read(doc, 1) }!!.connectionRef shouldBe "CONN-1"
    }

    @Test
    fun `INV-AC-23663 the build input carries connectionRef and transactionDate`() {
        inTx { it.canonical.handle(created(""","transactionDate":"$delivered"""")) }
        inTx { it.decided.handle(decided(""","connectionRef":"CONN-9"""")) }
        val input = inTx { it.reader.read(doc, 1) }!!
        input.connectionRef shouldBe "CONN-9"
        input.transactionDate shouldBe delivered
        input.familyRequest() shouldBe FamilyRequest("E-1", "CONN-9", delivered)
        // a version whose events carried neither member reads null for both, never a default
        inTx { it.canonical.handle(created(version = 2)) }
        inTx { it.decided.handle(decided(version = 2)) }
        val bare = inTx { it.reader.read(doc, 2) }!!
        bare.connectionRef shouldBe null
        bare.transactionDate shouldBe null
    }
}
