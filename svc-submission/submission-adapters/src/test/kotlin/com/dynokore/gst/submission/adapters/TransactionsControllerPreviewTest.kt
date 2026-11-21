package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionExportController
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionReconciliationController
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.application.listing.ExportSubmissions
import com.dynokore.gst.submission.application.listing.GetSubmissionReconciliation
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.gst.submission.application.replay.PreviewReplayScope
import com.dynokore.gst.submission.application.replay.ReplayActions
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplayCandidateSource
import com.dynokore.gst.submission.application.replay.ReplayRequeue
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.ReplayScopeRequest
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.gst.submission.domain.replay.ReplayCandidate
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Facts store of the previews: no entry, so an amount is unavailable (the callers that never preview). */
internal val NO_FACTS =
    object : RegisterListFactsStore {
        override fun store(
            tenant: TenantId,
            docUuid: String,
            facts: ListFacts,
        ): Boolean = throw UnsupportedOperationException()

        override fun facts(
            tenant: TenantId,
            docUuid: String,
        ): ListFacts? = null
    }

class TransactionsControllerPreviewTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val mapper = jacksonObjectMapper()
    private val docs = (1..5).map { "3fa85f64-5717-4562-b3fc-2c963f66af0$it" }

    private fun candidate(
        doc: String,
        state: LifecycleState = LifecycleState.DEAD_LETTERED,
        entity: String = "e1",
    ) = ReplayCandidate(
        doc,
        1,
        entity,
        "2026-Q1",
        state,
        "SUB-001",
        LocalDate.of(2026, 11, 30),
        Instant.parse("2026-10-01T00:00:00Z"),
    )

    private class FakeSource(
        val all: List<ReplayCandidate>,
    ) : ReplayCandidateSource {
        var calls = 0

        override fun candidates(
            tenant: TenantId,
            entityRef: String,
        ): List<ReplayCandidate> {
            calls++
            return all
        }
    }

    private class FakeRequeue(
        private val conflicts: Set<String>,
    ) : ReplayRequeue {
        val calls = mutableListOf<String>()

        override fun requeue(
            tenant: TenantId,
            docUuid: String,
            version: Int,
        ): RequeueOutcome {
            calls.add(docUuid)
            return if (docUuid in conflicts) RequeueOutcome.IN_FLIGHT_CONFLICT else RequeueOutcome.REQUEUED
        }
    }

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** One entity-scoped grant of the role the catalogue lists for the replay key (read, not named). */
    private fun replica(holder: String?) =
        AuthorityReplica(clock, roleMap).also { replica ->
            replica.onSnapshotComplete(0)
            if (holder != null) {
                val role =
                    roleMap
                        .rolesFor(ReplayActions.REPLAY)
                        .keys
                        .sorted()
                        .first()
                val grant = RoleGrant(role, GrantScope.ENTITY, "e1", clock.instant().minusSeconds(60))
                val record = AuthorityRecord.RoleGrantRecord("$role-e1", RoleGrantState.ACTIVE, grant)
                replica.accept(AuthorityChanged(tenant, holder, 1, record))
            }
        }

    private val emptySource =
        object : SubmissionRowSource {
            override fun rows(
                tenant: TenantId,
                entityRef: String,
                periodRef: String,
            ) = SourceRead(emptyList(), 0)
        }

    private class Fixture(
        val controller: TransactionsController,
        val source: FakeSource,
        val requeue: FakeRequeue,
    )

    @Suppress("LongMethod") // the composition of the controller delegates
    private fun fixture(
        caller: EndUserPrincipal?,
        candidates: List<ReplayCandidate>,
        amounts: Map<String, String> = emptyMap(),
        conflicts: Set<String> = emptySet(),
        holder: String? = "holder",
    ): Fixture {
        val source = FakeSource(candidates)
        val requeue = FakeRequeue(conflicts)
        val factsStore =
            object : RegisterListFactsStore {
                override fun store(
                    tenant: TenantId,
                    docUuid: String,
                    facts: ListFacts,
                ): Boolean = throw UnsupportedOperationException()

                override fun facts(
                    tenant: TenantId,
                    docUuid: String,
                ): ListFacts? =
                    amounts[docUuid]?.let {
                        ListFacts("N", "T", Direction.SALE, "C", "UEN", BigDecimal(it), BigDecimal("0.00"), emptySet())
                    }
            }
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        val controller =
            TransactionsController(
                ReplayBySelection(replica(holder), source, requeue),
                ReplaySingleVersion(replica(holder), { _, _, _ -> null }, requeue),
                clock,
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
                SubmissionListingHandler(ListSubmissions(replica(holder), emptySource), clock),
                SubmissionSummaryController(SubmissionSummaryQuery(replica(holder), emptySource), clock),
                SubmissionReconciliationController(GetSubmissionReconciliation(replica(holder), emptySource), clock),
                SubmissionExportController(
                    ExportSubmissions(replica(holder), emptySource, EntityGstns { _, _ -> "M90000002B" }, clock),
                ),
                PreviewReplayScope(
                    replica(holder),
                    source,
                    { _, doc, _ -> doc in conflicts },
                    factsStore,
                ),
            )
        return Fixture(controller, source, requeue)
    }

    private fun user(entities: Set<String> = setOf("e1")) =
        EndUserPrincipal("holder", tenant, entities.map { EntityRef(it) }.toSet(), true)

    private fun preview(
        f: Fixture,
        entityRef: String = "e1",
        periodRef: String? = null,
    ): ResponseEntity<*> =
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
            f.controller.previewReplayScope(entityRef, periodRef, null, null)
        }

    /** Status and JSON body; a refusal is rendered by the advice, as the framework does. */
    private fun outcome(block: () -> ResponseEntity<*>): Pair<Int, String> {
        val thrown = runCatching(block)
        val refusal = thrown.exceptionOrNull()
        if (refusal != null) {
            val refused = refusal as? ComplianceRefusal ?: throw AssertionError("unexpected $refusal")
            val response = TransactionsRefusalAdvice().refuse(refused)
            return response.statusCode.value() to response.body!!
        }
        val response = thrown.getOrThrow()
        return response.statusCode.value() to mapper.writeValueAsString(response.body)
    }

    @Test
    @DisplayName(
        "INV-AC-14235 the replay preview answers the count and total of the dead-lettered versions " +
            "and changes no state",
    )
    fun `INV-AC-14235 the replay preview answers the count and total of the dead-lettered versions, no state change`() {
        val f =
            fixture(
                user(),
                listOf(candidate(docs[0]), candidate(docs[1])),
                amounts = mapOf(docs[0] to "40.00", docs[1] to "60.00"),
            )
        val before = f.source.all.map { it.docUuid to it.state }
        val (status, body) = outcome { preview(f, "e1", "2026-Q1") }
        status shouldBe 200
        mapper.readTree(body).let {
            it["documentCount"].asInt() shouldBe 2
            it["totalAmount"].isString shouldBe true
            it["totalAmount"].asString() shouldBe "100.00"
        }
        f.source.all.map { it.docUuid to it.state } shouldBe before
        f.requeue.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-14235 a caller whose decision is deny gets 403 SRT-004 and no count`() {
        val f = fixture(user(), listOf(candidate(docs[0])), amounts = mapOf(docs[0] to "40.00"), holder = null)
        val (status, body) = outcome { preview(f) }
        status shouldBe 403
        body shouldContain "SRT-004"
        body shouldNotContain "documentCount"
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-14235 the replay preview count equals the versions that replaySubmissionsByScope would re-enqueue`() {
        val candidates =
            listOf(
                candidate(docs[0]),
                candidate(docs[1]),
                candidate(docs[2], LifecycleState.SENT_UNCONFIRMED),
                candidate(docs[3]),
                candidate(docs[4]),
            )
        val amounts = docs.associateWith { "10.00" }
        val conflicts = setOf(docs[4])
        val (status, body) = outcome { preview(fixture(user(), candidates, amounts, conflicts)) }
        status shouldBe 200
        val replayed =
            fixture(user(), candidates, amounts, conflicts)
                .let {
                    CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
                        it.controller.replaySubmissionsByScope("e1", ReplayScopeRequest())
                    }
                }.body!!
                .replayed.size
        replayed shouldBe 3
        mapper.readTree(body)["documentCount"].asInt() shouldBe replayed
    }

    @Test
    fun `INV-AC-14235 an entity outside the caller's set is refused as replaySubmissionsByScope refuses it`() {
        val f = fixture(user(entities = setOf("e9")), listOf(candidate(docs[0])), amounts = mapOf(docs[0] to "40.00"))
        val (status, body) = outcome { preview(f) }
        status shouldBe 404
        body shouldContain "SRT-005"
        body shouldNotContain "documentCount"
        f.source.calls shouldBe 0
    }
}
