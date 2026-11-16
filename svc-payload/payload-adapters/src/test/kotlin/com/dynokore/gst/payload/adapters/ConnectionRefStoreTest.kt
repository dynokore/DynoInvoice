package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.events.SubmissionTypeDecidedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.application.build.BuildJobs
import com.dynokore.gst.payload.application.build.BuildInputs
import com.dynokore.gst.payload.application.build.BuildTrigger
import com.dynokore.gst.payload.application.build.TypeDecisions
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

/** INV-AC-23663: the submission type decision stores the optional `connectionRef` as delivered (real database). */
class ConnectionRefStoreTest {
    private val tenant = UUID.randomUUID()
    private val doc = UUID.randomUUID()

    private fun <T> inTx(block: (SubmissionTypeDecidedHandler) -> T): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                val dsl = DSL.using(c, SQLDialect.POSTGRES)
                val jobs = JooqBuildJobs(dsl, mockk(), { tenant })
                val trigger = BuildTrigger(BuildInputs { _, _ -> false }, TypeDecisions { _, _ -> false }, BuildJobs { _, _ -> false })
                block(SubmissionTypeDecidedHandler(JooqTypeDecisionStore(dsl, jobs) { tenant }, trigger))
            } finally {
                c.commit()
            }
        }

    private fun event(data: String) =
        Envelope(
            id = EventId("ev-1"),
            source = "/gst/submission-svc",
            type = "com.dynokore.gst.submission.SubmissionTypeDecided.v1",
            subject = doc.toString(),
            time = Instant.parse("2026-10-20T10:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E-1"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 1, 15),
            docSequence = 1,
            instanceId = "submission-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun decided(
        member: String = "",
        version: Int = 1,
        type: String = "TYPE_2",
    ) = event("""{"docUuid":"$doc","version":$version,"docsequence":1,"submissionType":"$type"$member}""")

    private fun stored(version: Int = 1): Pair<String, String?>? =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select submission_type, connection_ref from payload.submission_type_decision " +
                        "where tenant_id = ? and doc_uuid = ? and version = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setObject(2, doc)
                    p.setInt(3, version)
                    p.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getString(2) else null }
                }
        }

    @Test
    fun `INV-AC-23663 connectionRef is stored with the decision`() {
        inTx { it.handle(decided(""","connectionRef":"CONN-7"""")) } shouldBe HandleOutcome.APPLIED
        stored() shouldBe ("TYPE_2" to "CONN-7")
    }

    @Test
    fun `INV-AC-23663 a decision without connectionRef stores null`() {
        inTx { it.handle(decided()) } shouldBe HandleOutcome.APPLIED
        stored() shouldBe ("TYPE_2" to null)
        inTx { it.handle(decided(""","connectionRef":null""", version = 2)) } shouldBe HandleOutcome.APPLIED
        stored(2) shouldBe ("TYPE_2" to null)
    }

    @Test
    fun `INV-AC-23663 a malformed connectionRef is a contract violation`() {
        listOf(""","connectionRef":""""", ""","connectionRef":7""", ""","connectionRef":"${"x".repeat(41)}"""", ""","connectionRef":["a"]""")
            .forEach { member -> shouldThrow<ContractViolation> { inTx { it.handle(decided(member)) } } }
        stored() shouldBe null
        inTx { it.handle(decided(""","connectionRef":"${"x".repeat(40)}"""")) } shouldBe HandleOutcome.APPLIED
        stored() shouldBe ("TYPE_2" to "x".repeat(40))
    }

    @Test
    fun `INV-AC-23663 a redelivery keeps the first values`() {
        inTx { it.handle(decided(""","connectionRef":"CONN-1"""")) } shouldBe HandleOutcome.APPLIED
        inTx { it.handle(decided(""","connectionRef":"CONN-2"""", type = "TYPE_3")) } shouldBe HandleOutcome.IGNORED
        stored() shouldBe ("TYPE_2" to "CONN-1")
    }
}
