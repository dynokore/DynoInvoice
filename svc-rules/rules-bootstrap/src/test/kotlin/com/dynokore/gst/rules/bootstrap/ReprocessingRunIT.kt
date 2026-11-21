package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.ReprocessingPageRecordedHandler
import com.dynokore.gst.rules.adapters.persistence.JooqReprocessingStore
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.ReprocessPopulation
import com.dynokore.gst.rules.adapters.events.ReprocessingWorker
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.UUID

private const val REQUESTED_TYPE = "com.dynokore.gst.platform.PackReprocessingRequested.v1"
private const val RECORDED_TYPE = "com.dynokore.gst.platform.PackReprocessingPopulationRecorded.v1"

/** One document of a page: Doc UUID, tax code (CHANGE is reclassified by the superseding pack) and acknowledged. */
data class PageDoc(
    val uuid: String = UUID.randomUUID().toString(),
    val taxCode: String,
    val acknowledged: Boolean,
)

/** The reprocessing harness: a correction already made, entities in the structure replica, pages delivered as events. */
open class ReprocessingHarness(
    port: Int,
    replica: AuthorityReplica,
    clock: MovableClock,
    context: ApplicationContext,
    policy: PolicyReplica,
) : CorrectionHarness(port, replica, clock, context, policy) {
    protected val entity = "E" + UUID.randomUUID().toString().take(8)
    protected val label get() = "$bad-rollback"

    protected fun structure() = context.getBean(StructureReplica::class.java)

    protected fun addEntity() {
        structure().accept(
            EntityProfileChanged(tenant, entity, StructureNode(StructureNodeKind.ENTITY, null, false, "E", entity, emptyList(), 1)),
        )
        structure().onSnapshotComplete(1)
    }

    /** The correction made through the served rollbackPack, the entity known, and the run started. */
    protected fun startRun(): String {
        seedBadPack()
        grantRollback("u-roll")
        grant("u-re", roles(com.dynokore.gst.catalogue.DeploymentForm.SAAS, "regulatory-pack.reprocess").first())
        rollback("u-roll").statusCode() shouldBe 200
        addEntity()
        val answer = reprocess("u-re")
        answer.statusCode() shouldBe 202
        answer.body() shouldContain "RUNNING"
        return scalar("select reprocessing_id from rules.reprocessing_run where pack_type = ?", type)!!
    }

    protected fun deliver(
        reprocessingId: String,
        number: Int,
        last: Boolean,
        docs: List<PageDoc>,
        entityRef: String = entity,
    ): HandleOutcome {
        val items =
            docs.joinToString(",") {
                """{"docUuid":"${it.uuid}","supersededVersion":3,"acknowledged":${it.acknowledged},"entityRef":"$entityRef",""" +
                    """"transactionDate":"2026-07-01","internalTaxCode":"${it.taxCode}","documentFunction":"BILL_FOR_PAYMENT",""" +
                    """"taxableValue":"10.00","gstValue":"0.90"}"""
            }
        val data = """{"reprocessingId":"$reprocessingId","entityRef":"$entityRef","pageNumber":$number,"lastPage":$last,"documents":[$items]}"""
        val envelope =
            Envelope(
                EventId(UUID.randomUUID().toString()), "/gst/canonical", RECORDED_TYPE, "reprocessing", Instant.now(), tenant,
                EntityRef(entityRef), null, null, null, "canonical-svc-0",
                CorrelationId(UUID.randomUUID().toString()), CausationId(UUID.randomUUID().toString()), data,
            )
        val handler =
            ReprocessingPageRecordedHandler(
                context.getBean(JooqReprocessingStore::class.java),
                context.getBean(ReprocessPopulation::class.java),
                context.getBean(PackEvaluationPort::class.java),
            )
        return inTenant { handler.handle(envelope) }
    }

    protected fun drain() = context.getBean(ReprocessingWorker::class.java).drain()

    protected fun resubmissionRows(): List<String> =
        superuser { c ->
            c.createStatement().use { st ->
                st.executeQuery("select to_jsonb(r)::text from rules.reprocessing_resubmission r where pack_type = '$type' order by doc_uuid").use {
                    buildList { while (it.next()) add(it.getString(1)) }
                }
            }
        }
}

@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, ArmingFixtures::class, CorrectionFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ReprocessingRunIT(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
) : ReprocessingHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrection(registry)
    }

    @Test
    fun `INV-AC-23933 every acknowledged document the superseding pack reclassifies is listed once under its own Doc UUID`() {
        val id = startRun()
        scalar("select count(*) from rules.outbox where type = ? and tenant_id = ?::uuid", REQUESTED_TYPE, tenant.toString()) shouldBe "1"
        val k = List(3) { PageDoc(taxCode = "CHANGE", acknowledged = true) }
        val unchanged = PageDoc(taxCode = "SAME", acknowledged = true)
        val notSent = PageDoc(taxCode = "CHANGE", acknowledged = false)
        deliver(id, 1, false, k + unchanged) shouldBe HandleOutcome.APPLIED
        // a repeated page number, another run and another entity enqueue nothing
        deliver(id, 1, false, k + unchanged) shouldBe HandleOutcome.IGNORED
        deliver("other-$id".take(40), 2, true, listOf(notSent)) shouldBe HandleOutcome.IGNORED
        deliver(id, 2, true, listOf(notSent), entityRef = "other") shouldBe HandleOutcome.IGNORED
        scalar("select count(*) from rules.work_queue where lane = 'REPROCESSING' and payload->>'packType' = ?", type) shouldBe "4"
        deliver(id, 2, true, listOf(notSent)) shouldBe HandleOutcome.APPLIED
        drain()
        val rows = resubmissionRows()
        rows.size shouldBe 3
        val list = inTenant { context.getBean(JooqReprocessingStore::class.java).resubmissions(type, label) }
        list.map { it.docUuid }.toSet() shouldBe k.map { it.uuid }.toSet()
        list.map { it.supersededVersion }.toSet() shouldBe setOf("3")
        rows.toSet().size shouldBe 3
        scalar("select state from rules.reprocessing_run where pack_type = ?", type) shouldBe "COMPLETE"
        // no row is updated or deleted by a second run of the worker, and a repeated add leaves the first row
        drain()
        resubmissionRows() shouldBe rows
        inTenant { context.getBean(JooqReprocessingStore::class.java).add(type, label, list.first()) }
        resubmissionRows() shouldBe rows
        // the HTTP answer for the same run is 202 again, unchanged
        reprocess("u-re").statusCode() shouldBe 202
        // look-alike: a differently named table does not hold the list
        scalar("select count(*) from information_schema.tables where table_schema = 'rules' and table_name = 'reprocessing_resubmissions'") shouldBe "0"
    }

    @Test
    fun `INV-AC-23933 an empty set of documents to resubmit gives an empty list, no row and no error`() {
        val id = startRun()
        deliver(id, 1, true, listOf(PageDoc(taxCode = "SAME", acknowledged = true))) shouldBe HandleOutcome.APPLIED
        drain()
        resubmissionRows() shouldBe emptyList()
        scalar("select state from rules.reprocessing_run where pack_type = ?", type) shouldBe "COMPLETE"
    }

    @Test
    fun `INV-AC-23933 a tenant with no entity and an unsynced replica are 503 SRT-013 and every table is equal`() {
        seedBadPack()
        grantRollback("u-roll")
        grant("u-re", roles(com.dynokore.gst.catalogue.DeploymentForm.SAAS, "regulatory-pack.reprocess").first())
        rollback("u-roll").statusCode() shouldBe 200
        val before = snapshot()
        val none = reprocess("u-re")
        none.statusCode() shouldBe 503
        none.body() shouldContain "SRT-013"
        snapshot() shouldBe before
        addEntity()
        structure().onDisconnected()
        reprocess("u-re").statusCode() shouldBe 503
        snapshot() shouldBe before
        structure().onSnapshotComplete(1)
    }

    @Test
    fun `INV-AC-23933 refused caller on reprocessPopulation leaves every table of schema rules equal`() {
        seedBadPack()
        grantRollback("u-roll")
        grant("u-re", roles(com.dynokore.gst.catalogue.DeploymentForm.SAAS, "regulatory-pack.reprocess").first())
        rollback("u-roll").statusCode() shouldBe 200
        addEntity()
        val before = snapshot()
        val calls = UseCaseCalls.permissionChecks.get()
        val alt = reprocess("u-re", token = com.dynokore.gst.rules.served.workloadToken("console-bff-alt"))
        alt.statusCode() shouldBe 403
        alt.body() shouldContain "SRT-004"
        reprocess("u-re", token = null).statusCode() shouldBe 401
        snapshot() shouldBe before
        UseCaseCalls.permissionChecks.get() shouldBe calls
        reprocess("u-nobody").statusCode() shouldBe 403
        UseCaseCalls.permissionChecks.get() shouldBe calls + 1
        snapshot() shouldBe before
        // the comparison can fail: the named caller with the key writes the run row and one outbox row
        reprocess("u-re").statusCode() shouldBe 202
        (snapshot() == before) shouldBe false
    }

    @Test
    fun `INV-AC-6925 the affected population is reprocessed and the acknowledged documents to resubmit are returned`() {
        val id = startRun()
        deliver(id, 1, true, listOf(PageDoc(taxCode = "CHANGE", acknowledged = true))) shouldBe HandleOutcome.APPLIED
        drain()
        resubmissionRows().size shouldBe 1
    }
}
