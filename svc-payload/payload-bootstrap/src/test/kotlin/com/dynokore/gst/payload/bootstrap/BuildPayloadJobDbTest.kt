package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqCanonicalInputStore
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.application.build.BuildInputsReader
import com.dynokore.gst.payload.application.build.BuildNotReady
import com.dynokore.gst.payload.application.build.BuildOutcomes
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.application.build.TypeDecisionReader
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-23006 against a real database (P109): the full TPL-18 context over the `payload_runtime` login, the claimed
 * job of `payload.work_queue` and the outbox rows read as the database owner. Only the family and the active pack
 * (not ruled, see the MR gaps) are test beans.
 */
class BuildPayloadJobDbTest {
    private val actor = "svc:payload-svc"
    private val mapper = ObjectMapper()

    /**
     * Claims only the items of one document: a job another test left queued in the shared test database is completed
     * unrun, so it never hides this test's job.
     */
    private class OneDocQueue(
        private val delegate: WorkQueue,
        private val scope: TenantScope,
        private val doc: UUID,
    ) : WorkQueue by delegate {
        override fun claimNext(): ClaimedItem? {
            while (true) {
                val item = delegate.claimNext() ?: return null
                if (item.partitionKey == doc.toString()) return item
                scope.bindWorkload(item.tenant) { scope.transactions.inTransaction { delegate.complete(item) } }
            }
        }
    }

    private class Boundary(
        val ctx: ApplicationContext,
    ) {
        val scope: TenantScope = ctx.getBean(TenantScope::class.java)
        val runner: TransactionRunner = ctx.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = ctx.getBean(DSLContext::class.java)
        val queue: WorkQueue = ctx.getBean(WorkQueue::class.java)
        val tenants: TenantContext = ctx.getBean(TenantContext::class.java)

        fun handler(doc: UUID) =
            BuildPayloadJobHandler(OneDocQueue(queue, scope, doc), scope, ctx.getBean(BuildPayload::class.java))
    }

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        PayloadContext
            .online(dir)
            .withAllowBeanDefinitionOverriding(true)
            .withBean(
                "specificationFamilySource",
                SpecificationFamilySource::class.java,
                { SpecificationFamilySource { "PINT_SG" } },
            ).withBean(
                ActivePackOffNetworkPrefix::class.java,
                {
                    ActivePackOffNetworkPrefix {
                        com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix(
                            "{}",
                        )
                    }
                },
            ).run { ctx -> Boundary(ctx).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun document(typeCode: String = "380") =
        """
        {"documentTypeCode":{"value":"$typeCode","classification":"NONE"},
         "parties":{"value":{
           "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A"},
           "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A"}},"classification":"IDENTIFYING"},
         "documentNumber":{"value":"INV-2026-000123","classification":"NONE"},
         "documentDate":{"value":"2026-03-20","classification":"NONE"},
         "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A","supplierGstn":"M2-1234567-8"},
           "classification":"IDENTIFYING"}}
        """.trimIndent()

    /** Stores the facts and the canonical input, optionally the decision, and enqueues the build job once. */
    private fun Boundary.seed(
        tenant: TenantId,
        doc: UUID,
        decision: String?,
        direction: String? = "SALE",
    ) = tx(tenant) {
        ctx.getBean(JooqVersionFactsStore::class.java).record(doc, 1, VersionFacts("E-001", null))
        ctx.getBean(JooqCanonicalInputStore::class.java).store(doc, 1, document(), direction, true)
        if (decision != null) ctx.getBean(JooqTypeDecisionStore::class.java).store(doc, 1, decision)
        ctx.getBean(JooqBuildJobs::class.java).enqueueOnce(doc, 1)
    }

    private fun events(
        tenant: UUID,
        doc: UUID,
        type: String,
    ): List<JsonNode> =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select data::text from payload.outbox where tenant_id = ? and doc_uuid = ? and type = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setObject(2, doc)
                    p.setString(3, type)
                    p.executeQuery().use { r ->
                        buildList {
                            while (r.next()) add(mapper.readTree(r.getString(1)))
                        }
                    }
                }
        }

    private fun outcomes(
        tenant: UUID,
        doc: UUID,
    ): Int =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement("select count(*) from payload.build_outcome where tenant_id = ? and doc_uuid = ?")
                .use { p ->
                    p.setObject(1, tenant)
                    p.setObject(2, doc)
                    p.executeQuery().use { r ->
                        r.next()
                        r.getInt(1)
                    }
                }
        }

    private val readyType = "com.dynokore.gst.payload.PayloadReady.v1"
    private val rejectedType = "com.dynokore.gst.payload.PayloadRejected.v1"

    @Test
    fun `INV-AC-23006 ready then repeat`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val doc = UUID.randomUUID()
        seed(tenant, doc, "TYPE_2")
        val handler = handler(doc)
        handler.runOnce() shouldBe true
        val ready = events(tenant.value, doc, readyType)
        ready.size shouldBe 1
        ready[0]["docUuid"].asString() shouldBe doc.toString()
        ready[0]["version"].asInt() shouldBe 1
        ready[0]["submissionType"].asString() shouldBe "2"
        ready[0]["specificationVersion"].asString() shouldBe "PINT_SG"
        val bytes = Base64.getDecoder().decode(ready[0]["payloadBytes"].asString())
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) shouldBe
            ready[0]["payloadSha256"].asString()
        outcomes(tenant.value, doc) shouldBe 1
        // the same version is enqueued again (a redelivery): the rerun adds no outbox row and no outcome row
        tx(tenant) {
            queue.enqueue(
                com.dynokore.platform.outboxandqueue.QueueEntry(
                    doc.toString(),
                    com.dynokore.platform.outboxandqueue.Lane.LIVE,
                    """{"docUuid":"$doc","version":1}""",
                ),
            )
        }
        handler.runOnce() shouldBe true
        events(tenant.value, doc, readyType).size shouldBe 1
        events(tenant.value, doc, rejectedType).size shouldBe 0
        outcomes(tenant.value, doc) shouldBe 1
        handler.runOnce() shouldBe false
    }

    @Test
    fun `INV-AC-23006 missing decision is retried`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val doc = UUID.randomUUID()
        seed(tenant, doc, null)
        val handler = handler(doc)
        shouldThrow<BuildNotReady> { handler.runOnce() }
        events(tenant.value, doc, readyType).size shouldBe 0
        events(tenant.value, doc, rejectedType).size shouldBe 0
        outcomes(tenant.value, doc) shouldBe 0
        // the job was released: it is claimed again, and once the decision is stored it builds
        shouldThrow<BuildNotReady> { handler.runOnce() }
        tx(tenant) { ctx.getBean(JooqTypeDecisionStore::class.java).store(doc, 1, "TYPE_2") }
        handler.runOnce() shouldBe true
        events(tenant.value, doc, readyType).size shouldBe 1
    }

    @Test
    fun `INV-AC-23006 blocked projection is rejected`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val doc = UUID.randomUUID()
        seed(tenant, doc, "TYPE_2", direction = null)
        handler(doc).runOnce() shouldBe true
        val rejected = events(tenant.value, doc, rejectedType)
        rejected.size shouldBe 1
        rejected[0]["validationTrace"]["reason"].asString() shouldBe "DIRECTION_ABSENT"
        (rejected[0]["docsequence"].asLong() >= 1) shouldBe true
        events(tenant.value, doc, readyType).size shouldBe 0
        outcomes(tenant.value, doc) shouldBe 1
    }

    @Test
    fun `INV-AC-23006 other tenant reads nothing`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val doc = UUID.randomUUID()
        seed(tenant, doc, "TYPE_2")
        val other = TenantId(UUID.randomUUID())
        tx(other) {
            ctx.getBean(BuildInputsReader::class.java).read(doc, 1) shouldBe null
            ctx.getBean(TypeDecisionReader::class.java).typeOf(doc, 1) shouldBe null
            ctx.getBean(BuildOutcomes::class.java).firstTime(doc, 1) shouldBe true // its own row, not the tenant's
        }
        tx(tenant) {
            ctx.getBean(BuildInputsReader::class.java).read(doc, 1)!!.entityRef shouldBe "E-001"
            ctx.getBean(TypeDecisionReader::class.java).typeOf(doc, 1) shouldBe "TYPE_2"
            ctx.getBean(BuildOutcomes::class.java).firstTime(doc, 1) shouldBe true
            ctx.getBean(BuildOutcomes::class.java).firstTime(doc, 1) shouldBe false
        }
        outcomes(tenant.value, doc) shouldBe 1
        outcomes(other.value, doc) shouldBe 1
    }
}
