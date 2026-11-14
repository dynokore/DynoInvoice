package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.replay.ReplayActions
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplayCandidateSource
import com.dynokore.gst.submission.application.replay.ReplayRequeue
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.ReplayScopeRequest
import com.dynokore.gst.submission.contract.model.ReplayScopeResult
import com.dynokore.gst.submission.domain.ordering.LifecycleState
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
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class TransactionsControllerReplayTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val docA = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val docB = "3fa85f64-5717-4562-b3fc-2c963f66afa7"
    private val docC = "3fa85f64-5717-4562-b3fc-2c963f66afa8"
    private val docOther = "3fa85f64-5717-4562-b3fc-2c963f66afa9"

    private fun candidate(
        doc: String,
        failureClass: String?,
        entity: String = "e1",
    ) = ReplayCandidate(
        doc,
        1,
        entity,
        "2026-Q1",
        LifecycleState.DEAD_LETTERED,
        failureClass,
        LocalDate.of(2026, 11, 30),
        Instant.parse("2026-10-01T00:00:00Z"),
    )

    private class FakeSource(
        private val all: List<ReplayCandidate>,
    ) : ReplayCandidateSource {
        var calls = 0

        /** Returns every candidate whatever the entity: only the selector keeps the entity apart. */
        override fun candidates(
            tenant: TenantId,
            entityRef: String,
        ): List<ReplayCandidate> {
            calls++
            return all
        }
    }

    private class FakeRequeue(
        private val conflicts: Set<String> = emptySet(),
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

    private class Fixture(
        val controller: TransactionsController,
        val source: FakeSource,
        val requeue: FakeRequeue,
    )

    private fun fixture(
        caller: EndUserPrincipal?,
        holder: String? = "holder",
        conflicts: Set<String> = emptySet(),
    ): Fixture {
        val source =
            FakeSource(
                listOf(
                    candidate(docA, "SUB-001"),
                    candidate(docB, null),
                    candidate(docC, "SUB-001"),
                    candidate(docOther, "SUB-001", entity = "e2"),
                ),
            )
        val requeue = FakeRequeue(conflicts)
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
                SubmissionListingHandler(
                    ListSubmissions(
                        replica(holder),
                        object : SubmissionRowSource {
                            override fun rows(
                                tenant: TenantId,
                                entityRef: String,
                                periodRef: String,
                            ) = SourceRead(emptyList(), 0)
                        },
                    ),
                ),
                SubmissionSummaryController(
                    SubmissionSummaryQuery(
                        replica(holder),
                        object : SubmissionRowSource {
                            override fun rows(
                                tenant: TenantId,
                                entityRef: String,
                                periodRef: String,
                            ) = SourceRead(emptyList(), 0)
                        },
                    ),
                ),
            )
        return Fixture(controller, source, requeue)
    }

    private fun user(
        name: String = "holder",
        entities: Set<String> = setOf("e1"),
        tenant: TenantId? = this.tenant,
    ) = EndUserPrincipal(name, tenant, entities.map { EntityRef(it) }.toSet(), true)

    private fun call(
        f: Fixture,
        body: ReplayScopeRequest,
        entityRef: String = "e1",
    ): ResponseEntity<ReplayScopeResult> =
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
            f.controller.replaySubmissionsByScope(entityRef, body)
        }

    /** The status and the problem `code` member the API-20 advice renders for the refusal the call throws. */
    private fun refusal(block: () -> Unit): Pair<Int, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refused = thrown as? ComplianceRefusal ?: throw AssertionError("expected ComplianceRefusal, got $thrown")
        val response = TransactionsRefusalAdvice().refuse(refused)
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(response.body!!)!!.groupValues[1]
        return response.statusCode.value() to code
    }

    private fun refused(
        f: Fixture,
        body: ReplayScopeRequest,
        entityRef: String = "e1",
    ) = refusal { call(f, body, entityRef) }

    @Test
    fun `INV-AC-20588 a holder replays only the class SUB-001 of its entity and the counts and reasons are rendered`() {
        val f = fixture(user(), conflicts = setOf(docC))
        val response = call(f, ReplayScopeRequest(failureClass = "SUB-001"))
        response.statusCode.value() shouldBe 200
        val body = response.body!!
        body.entityRef shouldBe "e1"
        body.matchedCount shouldBe 2
        body.truncated shouldBe false
        body.replayed.map { it.docUuid.toString() } shouldBe listOf(docA)
        body.replayed
            .single()
            .state.value shouldBe "QUEUED"
        body.skipped.map { it.docUuid.toString() to it.reasonCode } shouldBe listOf(docC to "SUB-008")
        f.requeue.calls shouldBe listOf(docA, docC)
    }

    @Test
    fun `INV-AC-20588 an empty body replays a and b and never a candidate of another entity`() {
        val f = fixture(user())
        val body = call(f, ReplayScopeRequest()).body!!
        body.replayed.map { it.docUuid.toString() }.toSet() shouldBe setOf(docA, docB, docC)
        body.matchedCount shouldBe 3
        f.requeue.calls.contains(docOther) shouldBe false
    }

    @Test
    fun `INV-AC-20588 a caller the replica refuses gets 403 SRT-004 and nothing is read or requeued`() {
        val f = fixture(user("stranger"))
        refused(f, ReplayScopeRequest()) shouldBe (403 to "SRT-004")
        f.source.calls shouldBe 0
        f.requeue.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20588 malformed and unstorable classes and limits 0 and 501 each answer 400 SRT-001`() {
        val f = fixture(user())
        listOf(
            ReplayScopeRequest(failureClass = "sub-001"),
            ReplayScopeRequest(failureClass = "SUB-0010"),
            ReplayScopeRequest(failureClass = "ZZZ-999"),
            ReplayScopeRequest(failureClass = ""),
            ReplayScopeRequest(periodRef = " "),
            ReplayScopeRequest(limit = 0),
            ReplayScopeRequest(limit = 501),
            ReplayScopeRequest(limit = -1),
        ).forEach { refused(f, it) shouldBe (400 to "SRT-001") }
        f.source.calls shouldBe 0
        f.requeue.calls shouldBe emptyList()
        call(f, ReplayScopeRequest(limit = 500)).statusCode.value() shouldBe 200
        call(f, ReplayScopeRequest(limit = 1)).body!!.truncated shouldBe true
    }

    @Test
    fun `INV-AC-20588 an entity outside the caller's set answers 404 SRT-005 with no read`() {
        val f = fixture(user(entities = setOf("e9")))
        refused(f, ReplayScopeRequest()) shouldBe (404 to "SRT-005")
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-20588 a caller with no verified tenant answers 401 SRT-003 and an unbound caller too`() {
        refused(fixture(user(entities = emptySet(), tenant = null)), ReplayScopeRequest()) shouldBe (401 to "SRT-003")
        refused(fixture(null), ReplayScopeRequest()) shouldBe (401 to "SRT-003")
    }

    @Test
    fun `INV-AC-20588 look-alike codes SRT-0040 and SRT-004 do not stand in for each other`() {
        val denied = refused(fixture(user("stranger")), ReplayScopeRequest())
        (denied.second == "SRT-0040") shouldBe false
        denied.second shouldBe "SRT-004"
        val bad = refused(fixture(user()), ReplayScopeRequest(limit = 0))
        (bad.second == "SRT-004") shouldBe false
        bad.second shouldBe "SRT-001"
    }

    @Test
    fun `INV-AC-20588 the look-alike operation getTransaction still answers 501`() {
        val f = fixture(user())
        val response = f.controller.getTransaction(UUID.fromString(docA))
        response.statusCode.value() shouldBe 501
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-20588 the controller source names no role and holds no permission string`() {
        val source =
            java.nio.file.Files.readString(
                java.nio.file.Path.of(
                    "src/main/kotlin/com/dynokore/gst/submission/adapters/http/TransactionsController.kt",
                ),
            )
        source shouldContain "ReplayBySelection"
        source.contains(ReplayActions.REPLAY) shouldBe false
        val roles = roleMap.rolesFor(ReplayActions.REPLAY).keys
        roles.none { source.contains("\"$it\"") } shouldBe true
    }
}
