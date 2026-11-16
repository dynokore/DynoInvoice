package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.build.BuildJobFailed
import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import com.dynokore.gst.payload.adapters.packs.JooqMdePackSource
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqCanonicalInputStore
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.application.ActiveMdePack
import com.dynokore.gst.payload.application.MdePackSource
import com.dynokore.gst.payload.application.build.BuildNotReady
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

/**
 * INV-AC-23595 against a real database (P109): the full TPL-18 context, with the production family left aside (not
 * ruled) as the one test bean; the active MDE pack is read from `payload.mde_pack_replica` by the production beans.
 */
class PackContentsBuildDbTest {
    private val actor = "svc:payload-svc"
    private val mapper = ObjectMapper()
    private val readyType = "com.dynokore.gst.payload.PayloadReady.v1"
    private val rejectedType = "com.dynokore.gst.payload.PayloadRejected.v1"

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

    /** Counts the reads of the active pack of the production source. */
    class CountingSource(
        private val delegate: MdePackSource,
        private val reads: AtomicInteger,
    ) : MdePackSource {
        override fun active(): ActiveMdePack? {
            reads.incrementAndGet()
            return delegate.active()
        }
    }

    // not annotated: the component scan of the application must not pick it up; only the test that names it does
    class CountingConfiguration {
        @Bean
        fun countingPostProcessor(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any = if (beanName == "mdePackSource") CountingSource(bean as MdePackSource, READS) else bean
            }
    }

    private class Boundary(
        val ctx: ApplicationContext,
    ) {
        val scope: TenantScope = ctx.getBean(TenantScope::class.java)
        val runner: TransactionRunner = ctx.getBean(TransactionRunner::class.java)
        val queue: WorkQueue = ctx.getBean(WorkQueue::class.java)

        fun handler(doc: UUID) =
            BuildPayloadJobHandler(OneDocQueue(queue, scope, doc), scope, ctx.getBean(BuildPayload::class.java))
    }

    private fun run(
        dir: Path,
        counting: Boolean = false,
        block: Boundary.(TenantId) -> Unit,
    ) {
        var runner =
            PayloadContext
                .online(dir)
                .withAllowBeanDefinitionOverriding(true)
                .withBean(
                    "specificationFamilySource",
                    SpecificationFamilySource::class.java,
                    { SpecificationFamilySource { _ -> "PINT_SG" } },
                )
        if (counting) runner = runner.withUserConfiguration(CountingConfiguration::class.java)
        runner.run { ctx -> Boundary(ctx).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun document(typeCode: String) =
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

    /** Stores the facts, the canonical input and the TYPE_2 decision, and enqueues the build job once. */
    private fun Boundary.seed(
        tenant: TenantId,
        doc: UUID,
        typeCode: String = "380",
    ) = tx(tenant) {
        ctx.getBean(JooqVersionFactsStore::class.java).record(doc, 1, VersionFacts("E-001", null))
        val canonical = ctx.getBean(JooqCanonicalInputStore::class.java)
        canonical.store(doc, 1, document(typeCode), "SALE", false, null as LocalDate?)
        ctx.getBean(JooqTypeDecisionStore::class.java).store(doc, 1, "TYPE_2", null)
        ctx.getBean(JooqBuildJobs::class.java).enqueueOnce(doc, 1)
    }

    private fun pack(
        tenant: UUID,
        version: String,
        prefix: String,
        active: Boolean,
    ) = PayloadDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into payload.mde_pack_replica(tenant_id, pack_identifier, pack_version, contents, active) " +
                    "values (?, 'mde-core', ?, ?::jsonb, ?)",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, version)
                it.setString(3, """{"offNetworkEndpoint":{"prefix":"$prefix"}}""")
                it.setBoolean(4, active)
                it.executeUpdate()
            }
    }

    private fun deactivate(tenant: UUID) =
        PayloadDb.superuser().use { c ->
            c.prepareStatement("update payload.mde_pack_replica set active = false where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeUpdate()
            }
        }

    private fun storedContents(
        tenant: UUID,
        version: String,
    ): String =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select contents::text from payload.mde_pack_replica where tenant_id = ? and pack_version = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, version)
                    it.executeQuery().use { r ->
                        r.next()
                        r.getString(1)
                    }
                }
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

    /** The text of the buyer's `cbc:EndpointID` in the one `PayloadReady` event of the document. */
    private fun buyerEndpoint(
        tenant: UUID,
        doc: UUID,
    ): String {
        val ready = events(tenant, doc, readyType)
        ready.size shouldBe 1
        val bytes = Base64.getDecoder().decode(ready[0]["payloadBytes"].asString())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val xml = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val cac = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
        val cbc = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
        val buyer = xml.getElementsByTagNameNS(cac, "AccountingCustomerParty").item(0) as org.w3c.dom.Element
        return buyer.getElementsByTagNameNS(cbc, "EndpointID").item(0).textContent
    }

    @Test
    fun `INV-AC-23595 production source is the jooq source`(
        @TempDir dir: Path,
    ) = run(dir) {
        ctx.getBean(MdePackSource::class.java).shouldBeInstanceOf<JooqMdePackSource>()
    }

    @Test
    fun `INV-AC-23595 prefix of the active version`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        pack(tenant.value, "1.0.0", "OLDPFX", false)
        pack(tenant.value, "2.0.0", "ACTPFX", true)
        val doc = UUID.randomUUID()
        seed(tenant, doc)
        handler(doc).runOnce() shouldBe true
        buyerEndpoint(tenant.value, doc) shouldBe "ACTPFX201888888A"
        val read = tx(tenant) { ctx.getBean(ActivePackOffNetworkPrefix::class.java).active() }!!
        read.version shouldBe "2.0.0"
        read.contents shouldBe storedContents(tenant.value, "2.0.0")
        read.prefix.prefix() shouldBe "ACTPFX"
    }

    @Test
    fun `INV-AC-23595 no active version is retried`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        pack(tenant.value, "1.0.0", "OLDPFX", false)
        val doc = UUID.randomUUID()
        seed(tenant, doc)
        shouldThrow<BuildJobFailed> { handler(doc).runOnce() }.cause.shouldBeInstanceOf<BuildNotReady>()
        events(tenant.value, doc, readyType).size shouldBe 0
        events(tenant.value, doc, rejectedType).size shouldBe 0
        outcomes(tenant.value, doc) shouldBe 0
    }

    @Test
    fun `INV-AC-23595 new active version is read by the next build`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        pack(tenant.value, "1.0.0", "FIRST", true)
        val first = UUID.randomUUID()
        seed(tenant, first)
        handler(first).runOnce() shouldBe true
        buyerEndpoint(tenant.value, first) shouldBe "FIRST201888888A"
        deactivate(tenant.value)
        pack(tenant.value, "2.0.0", "SECOND", true)
        val second = UUID.randomUUID()
        seed(tenant, second)
        handler(second).runOnce() shouldBe true
        buyerEndpoint(tenant.value, second) shouldBe "SECOND201888888A"
    }

    @Test
    fun `INV-AC-23595 blocked build names the active version`(
        @TempDir dir: Path,
    ) = run(dir, counting = true) { tenant ->
        pack(tenant.value, "1.0.0", "OLDPFX", false)
        pack(tenant.value, "3.1.4", "ACTPFX", true)
        val doc = UUID.randomUUID()
        seed(tenant, doc, typeCode = "999") // no builder: a block after the pack read
        ctx.getBean(MdePackSource::class.java).shouldBeInstanceOf<CountingSource>()
        READS.set(0)
        handler(doc).runOnce() shouldBe true
        // one read of the active pack in one build
        READS.get() shouldBe 1
        val rejected = events(tenant.value, doc, rejectedType)
        rejected.size shouldBe 1
        events(tenant.value, doc, readyType).size shouldBe 0
        val packs = rejected[0]["packVersions"]
        packs.size() shouldBe 1
        packs[0]["packType"].asString() shouldBe "MDE"
        packs[0]["version"].asString() shouldBe "3.1.4"
    }

    private companion object {
        val READS = AtomicInteger()
    }
}
