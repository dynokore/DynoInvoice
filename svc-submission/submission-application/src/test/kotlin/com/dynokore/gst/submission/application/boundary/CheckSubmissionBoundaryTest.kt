package com.dynokore.gst.submission.application.boundary

import com.dynokore.gst.submission.domain.boundary.SubmissionBoundary
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CheckSubmissionBoundaryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val who = Principal("holder", PrincipalKind.HUMAN)
    private val roles = OperationRoleMap { emptyMap() }
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val d3 = UUID.fromString("00000000-0000-0000-0000-0000000000d3")
    private val d4 = UUID.fromString("00000000-0000-0000-0000-0000000000d4")

    private fun v(
        doc: UUID,
        version: Int,
        entity: String,
        state: LifecycleState,
    ) = BoundaryVersion(doc, version, entity, state)

    private val standard =
        listOf(
            v(d1, 1, "E1", LifecycleState.READY),
            v(d1, 2, "E1", LifecycleState.SENT),
            v(d2, 1, "E1", LifecycleState.READY),
            v(d3, 1, "E2", LifecycleState.ACKNOWLEDGED),
        )

    private class Source(
        private val rows: List<BoundaryVersion>,
    ) : BoundaryVersionSource {
        var calls = 0

        override fun versions(
            tenant: TenantId,
            docUuids: Collection<UUID>,
        ): List<BoundaryVersion> {
            calls++
            return rows.filter { it.docUuid in docUuids }
        }
    }

    /**
     * The real replica holds no snapshot, so every decision goes to the fallback; this one records each request and
     * answers per entity (the replica class is final, so a recording fallback is the seam).
     */
    private class Asked(
        private val denied: Set<String> = emptySet(),
        private val failure: RuntimeException? = null,
    ) : AuthorityFallback {
        val requests = mutableListOf<AuthorizationRequest>()

        override fun fetch(
            key: AuthorizationRequest,
            deadline: Deadline,
        ): FallbackResult<AuthorityVerdict> {
            requests += key
            failure?.let { throw it }
            return FallbackResult.Found(
                AuthorityVerdict(key.action == "submission.view-status" && key.entityRef !in denied),
            )
        }
    }

    private fun replicaOf(asked: Asked) = AuthorityReplica(clock, roles, fallback = asked)

    private fun use(
        rows: List<BoundaryVersion>,
        asked: Asked = Asked(),
        max: Int = 100,
    ): Triple<CheckSubmissionBoundary, Source, Asked> {
        val source = Source(rows)
        return Triple(CheckSubmissionBoundary(replicaOf(asked), source, max), source, asked)
    }

    private fun req(vararg docs: UUID) = CheckSubmissionBoundaryRequest(tenant, who, docs.toList())

    @Test
    fun `INV-AC-26428 case 1 both entities allowed gives four checked, two members in order, two decisions`() {
        val (uc, _, asked) = use(standard)
        val result = uc.handle(req(d3, d1, d1, d2, d4), deadline)
        result shouldBe
            CheckSubmissionBoundaryResult.Done(
                4,
                listOf(SubmittedMember(d3, LifecycleState.ACKNOWLEDGED), SubmittedMember(d1, LifecycleState.SENT)),
            )
        (result as CheckSubmissionBoundaryResult.Done).members.size shouldBe 2
        asked.requests.map { it.entityRef } shouldBe listOf("E2", "E1")
        asked.requests.map { it.action }.toSet() shouldBe setOf("submission.view-status")
        asked.requests.map { it.tenant }.toSet() shouldBe setOf(tenant)
        asked.requests.map { it.principal }.toSet() shouldBe setOf(who)
    }

    @Test
    fun `INV-AC-26428 case 2 E1 denied gives Forbidden`() {
        val (uc, _, _) = use(standard, Asked(denied = setOf("E1")))
        uc.handle(req(d3, d1, d1, d2, d4), deadline) shouldBe CheckSubmissionBoundaryResult.Forbidden
    }

    @Test
    fun `INV-AC-26428 case 3 an unavailable replica gives Forbidden`() {
        // A replica with no snapshot and no fallback answers deny with AUTHORITY_UNAVAILABLE.
        val cold = AuthorityReplica(clock, roles)
        val uc = CheckSubmissionBoundary(cold, Source(standard), 100)
        uc.handle(req(d3, d1), deadline) shouldBe CheckSubmissionBoundaryResult.Forbidden
    }

    @Test
    fun `INV-AC-26428 case 4 E2 denied gives Forbidden and E1 is never decided`() {
        val (uc, _, asked) = use(standard, Asked(denied = setOf("E2")))
        uc.handle(req(d3, d1, d1, d2, d4), deadline) shouldBe CheckSubmissionBoundaryResult.Forbidden
        asked.requests.map { it.entityRef } shouldBe listOf("E2")
    }

    @Test
    fun `INV-AC-26428 case 5 a replica that throws propagates the exception`() {
        val boom = IllegalStateException("replica down")
        val (uc, _, _) = use(standard, Asked(failure = boom))
        val thrown = assertThrows(IllegalStateException::class.java) { uc.handle(req(d3, d1), deadline) }
        thrown shouldBe boom
    }

    @Test
    fun `INV-AC-26428 case 6 only unknown Doc UUIDs gives Done with no member and no decision`() {
        val (uc, _, asked) = use(emptyList())
        uc.handle(req(d4, d2, d4), deadline) shouldBe CheckSubmissionBoundaryResult.Done(2, emptyList())
        asked.requests shouldBe emptyList()
    }

    @TestFactory
    fun `INV-AC-26428 case 7 a document in a state outside the submitted set is no member`(): List<DynamicTest> =
        (LifecycleState.entries - SubmissionBoundary.SUBMITTED_STATES).map { state ->
            DynamicTest.dynamicTest("INV-AC-26428 case 7 $state is no member") {
                val (uc, _, _) = use(listOf(v(d1, 1, "E1", state)))
                uc.handle(req(d1), deadline) shouldBe CheckSubmissionBoundaryResult.Done(1, emptyList())
            }
        }

    @TestFactory
    fun `INV-AC-26428 case 8 a single version in a submitted state is a member with that state`(): List<DynamicTest> =
        SubmissionBoundary.SUBMITTED_STATES.map { state ->
            DynamicTest.dynamicTest("INV-AC-26428 case 8 $state is a member") {
                val (uc, _, _) = use(listOf(v(d1, 1, "E1", state)))
                uc.handle(req(d1), deadline) shouldBe
                    CheckSubmissionBoundaryResult.Done(1, listOf(SubmittedMember(d1, state)))
            }
        }

    @Test
    fun `INV-AC-26428 case 9 a Doc UUID repeated three times counts once`() {
        val (uc, _, _) = use(standard)
        uc.handle(req(d1, d1, d1), deadline) shouldBe
            CheckSubmissionBoundaryResult.Done(1, listOf(SubmittedMember(d1, LifecycleState.SENT)))
    }

    @Test
    fun `INV-AC-26428 case 10 the limit counts distinct Doc UUIDs and stops before any read`() {
        val (uc3, _, _) = use(standard, max = 3)
        uc3.handle(req(d1, d2, d3), deadline) shouldBe
            CheckSubmissionBoundaryResult.Done(
                3,
                listOf(SubmittedMember(d1, LifecycleState.SENT), SubmittedMember(d3, LifecycleState.ACKNOWLEDGED)),
            )
        val (uc, source, asked) = use(standard, max = 3)
        uc.handle(req(d1, d2, d3, d4), deadline) shouldBe CheckSubmissionBoundaryResult.TooManyDocuments
        source.calls shouldBe 0
        asked.requests shouldBe emptyList()
        uc.handle(req(d1, d1, d1, d1), deadline) shouldBe
            CheckSubmissionBoundaryResult.Done(1, listOf(SubmittedMember(d1, LifecycleState.SENT)))
    }

    @Test
    fun `INV-AC-26428 case 11 maxDocuments 0 fails construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            CheckSubmissionBoundary(replicaOf(Asked()), Source(emptyList()), 0)
        }
    }

    @Test
    fun `INV-AC-26428 case 12 the member shows the state of the highest submitted version`() {
        val rows =
            listOf(
                v(d1, 2, "E1", LifecycleState.SENT),
                v(d1, 3, "E1", LifecycleState.ACKNOWLEDGED),
                v(d1, 1, "E1", LifecycleState.REJECTED),
                v(d1, 4, "E1", LifecycleState.READY),
            )
        val (uc, _, _) = use(rows)
        uc.handle(req(d1), deadline) shouldBe
            CheckSubmissionBoundaryResult.Done(1, listOf(SubmittedMember(d1, LifecycleState.ACKNOWLEDGED)))
    }
}
