package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.build.ActivePackRead
import com.dynokore.gst.payload.adapters.build.BuildJobFailed
import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqCanonicalInputStore
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.application.build.BuildInputsReader
import com.dynokore.gst.payload.application.build.BuildNotReady
import com.dynokore.gst.payload.application.build.BuildOutcomes
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.FamilyRequest
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.application.build.TypeDecisionReader
import com.dynokore.gst.payload.domain.CustomizationId
import com.dynokore.gst.payload.domain.CustomizationIdResult
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
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
        withFamily: Boolean = true,
        withPack: Boolean = true,
        block: Boundary.(TenantId) -> Unit,
    ) {
        var runner = PayloadContext.online(dir).withAllowBeanDefinitionOverriding(true)
        if (withFamily) {
            runner =
                runner.withBean(
                    "specificationFamilySource",
                    SpecificationFamilySource::class.java,
                    { SpecificationFamilySource { _ -> "PINT_SG" } },
                )
        }
        if (withPack) {
            runner =
                runner.withBean(
                    ActivePackOffNetworkPrefix::class.java,
                    {
                        ActivePackOffNetworkPrefix {
                            ActivePackRead(
                                "DB-TEST",
                                com.dynokore.gst.payload.adapters.projection
                                    .MdePackOffNetworkPrefix("{}"),
                                "{}",
                            )
                        }
                    },
                )
        }
        runner.run { ctx -> Boundary(ctx).block(TenantId(UUID.randomUUID())) }
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
    @Suppress("LongParameterList") // the test seed names each stored fact
    private fun Boundary.seed(
        tenant: TenantId,
        doc: UUID,
        decision: String?,
        direction: String? = "SALE",
        connectionRef: String? = null,
        transactionDate: LocalDate? = null,
    ) = tx(tenant) {
        ctx.getBean(JooqVersionFactsStore::class.java).record(doc, 1, VersionFacts("E-001", null))
        ctx.getBean(JooqCanonicalInputStore::class.java).store(doc, 1, document(), direction, true, transactionDate)
        if (decision != null) ctx.getBean(JooqTypeDecisionStore::class.java).store(doc, 1, decision, connectionRef)
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
    fun `INV-AC-23663 both family reads use the same request`(
        @TempDir dir: Path,
    ) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<FamilyRequest>())
        val runner =
            PayloadContext
                .online(dir)
                .withAllowBeanDefinitionOverriding(true)
                .withBean(
                    "specificationFamilySource",
                    SpecificationFamilySource::class.java,
                    { SpecificationFamilySource { request -> "PINT_SG".also { requests.add(request) } } },
                ).withBean(
                    ActivePackOffNetworkPrefix::class.java,
                    {
                        ActivePackOffNetworkPrefix {
                            ActivePackRead(
                                "DB-TEST",
                                com.dynokore.gst.payload.adapters.projection
                                    .MdePackOffNetworkPrefix("{}"),
                                "{}",
                            )
                        }
                    },
                )
        runner.run { ctx ->
            val boundary = Boundary(ctx)
            val tenant = TenantId(UUID.randomUUID())
            val doc = UUID.randomUUID()
            val date = LocalDate.of(2026, 3, 20)
            boundary.seed(tenant, doc, "TYPE_2", connectionRef = "CONN-5", transactionDate = date)
            boundary.handler(doc).runOnce() shouldBe true
            events(tenant.value, doc, readyType).size shouldBe 1
            requests.size shouldBe 2
            requests.toSet() shouldBe setOf(FamilyRequest("E-001", "CONN-5", date))
        }
    }

    /** The production family source over a policy reader: SG_PEPPOL_BIS for CONN-A from the election date on. */
    private fun policyContext(
        dir: Path,
        election: LocalDate,
    ) = PayloadContext
        .online(dir)
        .withAllowBeanDefinitionOverriding(true)
        .withBean(
            PolicyReader::class.java,
            {
                object : PolicyReader {
                    override fun resolve(
                        key: VariationPointKey,
                        scope: PolicyScope,
                        at: LocalDate,
                    ): ResolvedSetting {
                        val elected = scope.ref(ScopeLevel.AP_CONNECTION)?.id == "CONN-A" && !at.isBefore(election)
                        return ResolvedSetting(
                            key,
                            at,
                            listOf(SelectedOption(if (elected) "SG_PEPPOL_BIS" else "PINT_SG")),
                            PolicySource.CatalogueDefault,
                        )
                    }
                }
            },
        ).withBean(
            ActivePackOffNetworkPrefix::class.java,
            {
                ActivePackOffNetworkPrefix {
                    ActivePackRead(
                        "DB-TEST",
                        com.dynokore.gst.payload.adapters.projection
                            .MdePackOffNetworkPrefix("{}"),
                        "{}",
                    )
                }
            },
        )

    private fun customizationIdOf(ready: JsonNode): String {
        val xml = String(Base64.getDecoder().decode(ready["payloadBytes"].asString()), Charsets.UTF_8)
        return Regex("<(?:\\w+:)?CustomizationID>([^<]*)<").find(xml)!!.groupValues[1]
    }

    private fun expectedCustomization(family: SpecificationFamily) =
        (CustomizationId.expected(family, SubmissionType.TYPE_2) as CustomizationIdResult.Selected).value

    @Test
    fun `INV-AC-23664 the document dated d is built in SG_PEPPOL_BIS and the one dated the day before in PINT_SG`(
        @TempDir dir: Path,
    ) {
        val election = LocalDate.of(2026, 3, 20)
        policyContext(dir, election).run { ctx ->
            val boundary = Boundary(ctx)
            val tenant = TenantId(UUID.randomUUID())
            val onDay = UUID.randomUUID()
            val dayBefore = UUID.randomUUID()
            boundary.seed(tenant, onDay, "TYPE_2", connectionRef = "CONN-A", transactionDate = election)
            boundary.seed(
                tenant,
                dayBefore,
                "TYPE_2",
                connectionRef = "CONN-A",
                transactionDate = election.minusDays(1),
            )
            boundary.handler(onDay).runOnce() shouldBe true
            boundary.handler(dayBefore).runOnce() shouldBe true
            val bis = events(tenant.value, onDay, readyType).single()
            val pint = events(tenant.value, dayBefore, readyType).single()
            bis["specificationVersion"].asString() shouldBe SpecificationFamily.SG_PEPPOL_BIS.name
            pint["specificationVersion"].asString() shouldBe SpecificationFamily.PINT_SG.name
            customizationIdOf(bis) shouldBe expectedCustomization(SpecificationFamily.SG_PEPPOL_BIS)
            customizationIdOf(pint) shouldBe expectedCustomization(SpecificationFamily.PINT_SG)
            events(tenant.value, onDay, rejectedType).size shouldBe 0
            events(tenant.value, dayBefore, rejectedType).size shouldBe 0
        }
    }

    @Test
    fun `INV-AC-23664 PayloadReady specificationVersion equals the family of the projector`(
        @TempDir dir: Path,
    ) {
        val election = LocalDate.of(2026, 3, 20)
        policyContext(dir, election).run { ctx ->
            val boundary = Boundary(ctx)
            val tenant = TenantId(UUID.randomUUID())
            val doc = UUID.randomUUID()
            boundary.seed(tenant, doc, "TYPE_2", connectionRef = "CONN-A", transactionDate = election)
            boundary.handler(doc).runOnce() shouldBe true
            val source = ctx.getBean(SpecificationFamilySource::class.java)
            val family = boundary.tx(tenant) { source.familyOf(FamilyRequest("E-001", "CONN-A", election)) }
            events(tenant.value, doc, readyType).single()["specificationVersion"].asString() shouldBe family
        }
    }

    @Test
    fun `INV-AC-23664 a version with no connectionRef ends in a retry and publishes nothing`(
        @TempDir dir: Path,
    ) {
        policyContext(dir, LocalDate.of(2026, 3, 20)).run { ctx ->
            val boundary = Boundary(ctx)
            val tenant = TenantId(UUID.randomUUID())
            val doc = UUID.randomUUID()
            boundary.seed(tenant, doc, "TYPE_2", transactionDate = LocalDate.of(2026, 3, 20))
            shouldThrow<BuildJobFailed> {
                boundary
                    .handler(
                        doc,
                    ).runOnce()
            }.cause.shouldBeInstanceOf<IllegalStateException>()
            events(tenant.value, doc, readyType).size shouldBe 0
            events(tenant.value, doc, rejectedType).size shouldBe 0
            outcomes(tenant.value, doc) shouldBe 0
        }
    }

    @Test
    fun `INV-AC-23006 missing decision is retried`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val doc = UUID.randomUUID()
        seed(tenant, doc, null)
        val handler = handler(doc)
        shouldThrow<BuildJobFailed> { handler.runOnce() }.cause.shouldBeInstanceOf<BuildNotReady>()
        events(tenant.value, doc, readyType).size shouldBe 0
        events(tenant.value, doc, rejectedType).size shouldBe 0
        outcomes(tenant.value, doc) shouldBe 0
        // the job was released: it is claimed again, and once the decision is stored it builds
        shouldThrow<BuildJobFailed> { handler.runOnce() }.cause.shouldBeInstanceOf<BuildNotReady>()
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

    @Test
    fun `INV-AC-23006 no active pack is retried and stages nothing`(
        @TempDir dir: Path,
    ) = run(dir, withPack = false) { tenant ->
        val doc = UUID.randomUUID()
        seed(tenant, doc, "TYPE_2")
        val handler = handler(doc)
        shouldThrow<BuildJobFailed> { handler.runOnce() }.cause.shouldBeInstanceOf<BuildNotReady>()
        events(tenant.value, doc, readyType).size shouldBe 0
        events(tenant.value, doc, rejectedType).size shouldBe 0
        outcomes(tenant.value, doc) shouldBe 0
    }

    @Test
    fun `INV-AC-23006 production family source fails closed and stages nothing`(
        @TempDir dir: Path,
    ) = run(dir, withFamily = false) { tenant ->
        val doc = UUID.randomUUID()
        seed(tenant, doc, "TYPE_2")
        shouldThrow<BuildJobFailed> { handler(doc).runOnce() }.cause.shouldBeInstanceOf<IllegalStateException>()
        events(tenant.value, doc, readyType).size shouldBe 0
        events(tenant.value, doc, rejectedType).size shouldBe 0
        outcomes(tenant.value, doc) shouldBe 0
    }

    @Test
    fun `INV-AC-23582 handler failure carries the partition document UUID`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val doc = UUID.randomUUID()
        val other = UUID.randomUUID()
        // the payload names another document than the partition: refused, and the failure names the partition
        tx(tenant) {
            queue.enqueue(
                com.dynokore.platform.outboxandqueue.QueueEntry(
                    doc.toString(),
                    com.dynokore.platform.outboxandqueue.Lane.LIVE,
                    """{"docUuid":"$other","version":1}""",
                ),
            )
        }
        val failure = shouldThrow<BuildJobFailed> { handler(doc).runOnce() }
        failure.docUuid shouldBe doc.toString()
        failure.cause.shouldBeInstanceOf<IllegalStateException>()
        // released after the failure: the same item is claimed again
        shouldThrow<BuildJobFailed> { handler(doc).runOnce() }.docUuid shouldBe doc.toString()
    }
}
