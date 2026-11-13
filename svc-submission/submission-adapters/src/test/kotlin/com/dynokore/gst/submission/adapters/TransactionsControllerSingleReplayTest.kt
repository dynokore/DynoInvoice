package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionRefusal
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.replay.ReplayActions
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplayRequeue
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.ReplayVersionLookup
import com.dynokore.gst.submission.application.replay.ReplayVersionView
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.ReplayResult
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.SubmissionErrorCode
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
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.MethodParameter
import org.springframework.http.ResponseEntity
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class TransactionsControllerSingleReplayTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    private class FakeLookup(
        private val view: ReplayVersionView?,
    ) : ReplayVersionLookup {
        var reads = 0

        override fun find(
            tenant: TenantId,
            docUuid: String,
            version: Int,
        ): ReplayVersionView? {
            reads++
            return view
        }
    }

    private class FakeRequeue(
        private val outcome: () -> RequeueOutcome,
    ) : ReplayRequeue {
        var calls = 0

        override fun requeue(
            tenant: TenantId,
            docUuid: String,
            version: Int,
        ): RequeueOutcome {
            calls++
            return outcome()
        }
    }

    private class Fixture(
        val controller: TransactionsController,
        val lookup: FakeLookup,
        val requeue: FakeRequeue,
    )

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

    private fun user(
        name: String = "holder",
        tenant: TenantId? = this.tenant,
    ) = EndUserPrincipal(name, tenant, if (tenant == null) emptySet() else setOf(EntityRef("e1")), true)

    private fun fixture(
        caller: EndUserPrincipal?,
        state: LifecycleState? = LifecycleState.DEAD_LETTERED,
        holder: String? = "holder",
        outcome: () -> RequeueOutcome = { RequeueOutcome.REQUEUED },
    ): Fixture {
        val lookup = FakeLookup(state?.let { ReplayVersionView("e1", it) })
        val requeue = FakeRequeue(outcome)
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        val replica = replica(holder)
        val controller =
            TransactionsController(
                ReplayBySelection(replica, { _, _ -> emptyList() }, requeue),
                ReplaySingleVersion(replica, lookup, requeue),
                clock,
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
                SubmissionListingHandler(
                    ListSubmissions(
                        replica,
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
        return Fixture(controller, lookup, requeue)
    }

    private fun call(
        f: Fixture,
        version: Int = 1,
    ): ResponseEntity<ReplayResult> =
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
            f.controller.replaySubmissions(doc, version)
        }

    /** The status and the problem `code` member the advice renders for the refusal the call throws. */
    private fun refusal(block: () -> Unit): Triple<Int, String, String> {
        val response =
            when (val thrown = runCatching(block).exceptionOrNull()) {
                is ComplianceRefusal -> TransactionsRefusalAdvice().refuse(thrown)
                is SubmissionRefusal -> TransactionsRefusalAdvice().refuse(thrown)
                else -> throw AssertionError("expected a refusal, got $thrown")
            }
        val body = response.body!!
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(body)!!.groupValues[1]
        return Triple(response.statusCode.value(), code, body)
    }

    /** The string value of a top-level member of the problem body. */
    private fun member(
        body: String,
        name: String,
    ) = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")
        .find(body)
        ?.groupValues
        ?.get(1)
        .orEmpty()

    private fun refused(
        f: Fixture,
        version: Int = 1,
    ) = refusal { call(f, version) }.let { it.first to it.second }

    @Test
    fun `INV-AC-20606 a requeued version answers 200 QUEUED with the instant of the clock`() {
        val body = call(fixture(user())).also { it.statusCode.value() shouldBe 200 }.body!!
        body.docUuid shouldBe doc
        body.version shouldBe 1
        body.state shouldBe ReplayResult.State.QUEUED
        body.replayedAt shouldBe OffsetDateTime.ofInstant(clock.instant(), clock.zone)
    }

    @Test
    fun `INV-AC-20606 an already queued version answers 200 QUEUED with the instant of the clock`() {
        val f = fixture(user(), state = LifecycleState.QUEUED, outcome = { RequeueOutcome.ALREADY_QUEUED })
        val response = call(f, 3)
        response.statusCode.value() shouldBe 200
        response.body!!.version shouldBe 3
        response.body!!.state shouldBe ReplayResult.State.QUEUED
        response.body!!.replayedAt shouldBe OffsetDateTime.ofInstant(clock.instant(), clock.zone)
    }

    @Test
    fun `INV-AC-20606 an in-flight conflict answers 409 SUB-008`() {
        val f = fixture(user(), outcome = { RequeueOutcome.IN_FLIGHT_CONFLICT })
        refused(f) shouldBe (409 to "SUB-008")
        SubmissionErrorCode.IN_FLIGHT_BREACH.code shouldBe InFlight.SUB_008
    }

    @Test
    fun `INV-AC-20606 a version the tenant does not have answers 404 SRT-005`() {
        refused(fixture(user(), state = null)) shouldBe (404 to "SRT-005")
    }

    @Test
    fun `INV-AC-20606 a SENT version answers 409 SUB-025 never SRT-008 or SUB-0250`() {
        val f = fixture(user(), state = LifecycleState.SENT)
        val (status, code, body) = refusal { call(f) }
        status shouldBe 409
        code shouldBe "SUB-025"
        (code == "SRT-008" || code == "SUB-0250") shouldBe false
        body.contains("\"severity\":\"BLOCK\"") shouldBe true
        f.requeue.calls shouldBe 0
    }

    @Test
    fun `INV-AC-20606 a caller the replica refuses answers 403 SRT-004`() {
        val f = fixture(user("stranger"))
        refused(f) shouldBe (403 to "SRT-004")
        f.requeue.calls shouldBe 0
    }

    @Test
    fun `INV-AC-20606 a caller with no verified tenant or no binding answers 401 SRT-003`() {
        refused(fixture(user(tenant = null))) shouldBe (401 to "SRT-003")
        refused(fixture(null)) shouldBe (401 to "SRT-003")
    }

    @Test
    fun `INV-AC-20606 a version below 1 answers 400 SRT-001 and no lookup is read`() {
        val f = fixture(user())
        listOf(0, -1).forEach { refused(f, it) shouldBe (400 to "SRT-001") }
        f.lookup.reads shouldBe 0
    }

    @Test
    fun `INV-AC-20606 a malformed Doc UUID answers 400 SRT-001 and no lookup is read`() {
        val f = fixture(user())
        val mismatch =
            MethodArgumentTypeMismatchException(
                "not-a-uuid",
                UUID::class.java,
                "docUuid",
                MethodParameter(
                    TransactionsController::class.java.getMethod(
                        "replaySubmissions",
                        UUID::class.java,
                        Int::class.java,
                    ),
                    0,
                ),
                null,
            )
        val response = TransactionsRefusalAdvice().refuse(mismatch)
        response.statusCode.value() shouldBe 400
        member(response.body!!, "code") shouldBe "SRT-001"
        f.lookup.reads shouldBe 0
        f.requeue.calls shouldBe 0
    }

    @Test
    fun `INV-AC-20606 the 409 answers carry the API-20 envelope with the content type and a correlation id`() {
        listOf(
            fixture(user(), outcome = { RequeueOutcome.IN_FLIGHT_CONFLICT }) to "SUB-008",
            fixture(user(), state = LifecycleState.SENT) to "SUB-025",
        ).forEach { (f, expected) ->
            val thrown = runCatching { call(f) }.exceptionOrNull() as SubmissionRefusal
            val response = TransactionsRefusalAdvice().refuse(thrown)
            response.statusCode.value() shouldBe 409
            response.headers.getFirst("Content-Type") shouldBe Problems.CONTENT_TYPE
            val body = response.body!!
            member(body, "code") shouldBe expected
            member(body, "severity") shouldBe "BLOCK"
            Regex("\"status\"\\s*:\\s*409").containsMatchIn(body) shouldBe true
            member(body, "correlationId").isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `INV-AC-20606 a use case that throws answers 500 SRT-010 with no detail`() {
        val f = fixture(user(), outcome = { error("state changed between lookup and requeue") })
        val (status, code, body) = refusal { call(f) }
        status shouldBe 500
        code shouldBe "SRT-010"
        body.contains("state changed") shouldBe false
    }

    @Test
    fun `INV-AC-20606 the replica decides on the actor id so the holder passes and a stranger is refused`() {
        val f = fixture(user("holder"))
        call(f).statusCode.value() shouldBe 200
        f.requeue.calls shouldBe 1
    }

    @Test
    fun `INV-AC-20606 any other operation of TransactionsApi still answers 501`() {
        fixture(user())
            .controller
            .getTransaction(doc)
            .statusCode
            .value() shouldBe 501
    }
}
