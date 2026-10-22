package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.http.ApiRefusal
import com.dynokore.gst.transport.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.transport.adapters.http.DespatchController
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AttemptEgress.Companion.ATTEMPT_EGRESS
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.contract.model.SubmitPayloadRequest
import com.dynokore.gst.transport.contract.model.TransportOutcome
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

/**
 * The entity of a submitPayload request at the service boundary (INV-Q-8172, API-24; INV-AC-846, INV-AC-7857): the
 * controller of the full TPL-18 context, called with the Authorization header of a registered end-user principal. No
 * test-only bypass: the principal comes through the same `EndUserPrincipalResolver` port the runtime uses.
 */
class SubmitPayloadEntityBoundaryTest {
    @BeforeEach
    fun resetSimulator() {
        ApSimulator.reset()
    }

    private fun sandbox() = arrayOf("transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}")

    private class Answer(
        val status: Int,
        val body: String,
        val outcome: TransportOutcome?,
        val doc: UUID,
    )

    private fun Harness.call(
        authorization: String?,
        entity: String,
        docUuid: UUID,
        version: Int = 1,
        authority: AuthorityReplica? = null,
        resolver: Boolean = true,
    ): Answer {
        val provider = DefaultListableBeanFactory()
        if (resolver) provider.registerSingleton("principals", TestPrincipals.resolver)
        authority?.let { provider.registerSingleton("authorityReplica", it) }
        val controller =
            DespatchController(
                submit,
                provider.getBeanProvider(EndUserPrincipalResolver::class.java),
                scope,
                provider.getBeanProvider(AuthorityReplica::class.java),
                { authorization },
                Clock.systemUTC(),
            )
        return try {
            val response =
                controller.submitPayload(
                    UUID.randomUUID(),
                    SubmitPayloadRequest(docUuid, version, "<Invoice/>".toByteArray(), entity),
                )
            Answer(response.statusCode.value(), "", response.body, docUuid)
        } catch (refusal: ApiRefusal) {
            val problem = ApiRefusalAdvice().refuse(refusal)
            Answer(problem.statusCode.value(), problem.body.orEmpty(), null, docUuid)
        }
    }

    /** What the simulator received for the Doc UUID (a late request of an earlier test is not ours). */
    private fun received(doc: UUID): Int = ApSimulator.submissions().count { doc.toString() in it.toString() }

    /** The attempt rows and egress records of the tenant, as counts. */
    private fun Harness.counts(tenant: UUID): Pair<Int, Int> =
        asTenant(tenant) { dsl.fetchCount(SUBMISSION_ATTEMPT) to dsl.fetchCount(ATTEMPT_EGRESS) }

    @Test
    fun `INV-AC-846 two entities of one tenant submit through their own adapter and the attempt records the entity`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.assign(tenant, "E-002", h.secondary)
        val caller = TestPrincipals.register(tenant, setOf("E-001", "E-002"))
        val one = h.call(caller, "E-001", UUID.randomUUID())
        val two = h.call(caller, "E-002", UUID.randomUUID())
        one.status shouldBe 202
        two.status shouldBe 202
        h.attemptLog(tenant).map { it.second }.toSet() shouldBe setOf(h.primary.value, h.secondary.value)
        h.attemptLog(tenant).forEach { it.third shouldBe 1 }
        val entities =
            h.asTenant(tenant) {
                h.dsl
                    .select(SUBMISSION_ATTEMPT.ENTITY_REF, SUBMISSION_ATTEMPT.ADAPTER_ID)
                    .from(SUBMISSION_ATTEMPT)
                    .fetch()
                    .map { it.value1() to it.value2() }
                    .toSet()
            }
        entities shouldBe setOf("E-001" to h.primary.value, "E-002" to h.secondary.value)
    }

    @Test
    fun `INV-AC-7857 the response carries the receipt of the AP simulator, a repeat the original outcome`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"))
        val doc = UUID.randomUUID()
        val first = h.call(caller, "E-001", doc)
        first.status shouldBe 202
        first.outcome!!.outcome shouldBe TransportOutcome.Outcome.ACCEPTED
        first.outcome.acknowledgement!!.reference shouldBe
            ApSimulator
                .submissions()
                .single { doc.toString() in it.toString() }
                .get("apSubmissionId")
                .asString()
        val before = h.counts(tenant)
        val repeat = h.call(caller, "E-001", doc)
        repeat.outcome shouldBe first.outcome
        h.counts(tenant) shouldBe before
        received(doc) shouldBe 1
    }

    private fun Harness.assertRefusedWithNothingRecorded(
        tenant: UUID,
        answer: Answer,
        status: Int,
        code: String,
    ) {
        answer.status shouldBe status
        answer.body shouldContain code
        counts(tenant) shouldBe (0 to 0)
        received(answer.doc) shouldBe 0
    }

    @Test
    fun `INV-Q-8172 an entity of the same tenant outside the principal's set is 403 SRT-004 and records nothing`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.assign(tenant, "E-002", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"))
        h.assertRefusedWithNothingRecorded(tenant, h.call(caller, "E-002", UUID.randomUUID()), 403, "SRT-004")
    }

    @Test
    fun `INV-Q-8172 an entity of another tenant is 403 SRT-004 with no attempt and no egress in either tenant`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        val other = UUID.randomUUID()
        h.assign(other, "E-900", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"))
        h.assertRefusedWithNothingRecorded(tenant, h.call(caller, "E-900", UUID.randomUUID()), 403, "SRT-004")
        h.counts(other) shouldBe (0 to 0)
    }

    @Test
    fun `INV-Q-8172 the value of an entity in the set with a trailing blank is a different entity and is 403`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.assign(tenant, "E-001 ", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"))
        h.assertRefusedWithNothingRecorded(tenant, h.call(caller, "E-001 ", UUID.randomUUID()), 403, "SRT-004")
    }

    @Test
    fun `INV-Q-8172 the check is never skipped while the entity set is not current`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"), entitySetCurrent = false)
        // no authority decision can be had: not answered by the set, not by a replica that never received a version
        h.assertRefusedWithNothingRecorded(tenant, h.call(caller, "E-001", UUID.randomUUID()), 403, "SRT-004")
        val fresh = AuthorityReplica(Clock.systemUTC())
        h.assertRefusedWithNothingRecorded(
            tenant,
            h.call(caller, "E-001", UUID.randomUUID(), authority = fresh),
            403,
            "SRT-004",
        )
    }

    @Test
    fun `INV-Q-8172 a request without a principal is 401 and nothing is recorded`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.assertRefusedWithNothingRecorded(tenant, h.call(null, "E-001", UUID.randomUUID()), 401, "SRT-003")
        h.assertRefusedWithNothingRecorded(tenant, h.call("Bearer nobody", "E-001", UUID.randomUUID()), 401, "SRT-003")
        // a service with no resolver bound (no identity provider) is closed, not open
        val caller = TestPrincipals.register(tenant, setOf("E-001"))
        h.assertRefusedWithNothingRecorded(
            tenant,
            h.call(caller, "E-001", UUID.randomUUID(), resolver = false),
            401,
            "SRT-003",
        )
    }

    @Test
    fun `INV-Q-8172 a Doc UUID recorded under one entity is 404 SRT-005 under another entity of the set`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.assign(tenant, "E-002", h.secondary)
        val caller = TestPrincipals.register(tenant, setOf("E-001", "E-002"))
        val doc = UUID.randomUUID()
        val first = h.call(caller, "E-001", doc)
        first.status shouldBe 202
        val before = h.counts(tenant)
        val calls = received(doc)
        val other = h.call(caller, "E-002", doc)
        other.status shouldBe 404
        other.body shouldContain "SRT-005"
        h.call(caller, "E-002", doc, version = 2).status shouldBe 404
        h.counts(tenant) shouldBe before
        received(doc) shouldBe calls
        h.call(caller, "E-001", doc).outcome shouldBe first.outcome
    }

    /** An authority replica whose fallback records each request it is asked and answers [answer]. */
    private class Recording(
        answer: FallbackResult<AuthorityVerdict>,
    ) {
        val requests = mutableListOf<AuthorizationRequest>()
        val replica =
            AuthorityReplica(
                Clock.systemUTC(),
                fallback = { request, _ ->
                    requests += request
                    answer
                },
            )
    }

    @Test
    fun `INV-Q-8335 while the set is not current an allowing decision lets the request go on with the four fields`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"), entitySetCurrent = false)
        val authority = Recording(FallbackResult.Found(AuthorityVerdict(true)))
        val answer = h.call(caller, "E-001", UUID.randomUUID(), authority = authority.replica)
        answer.status shouldBe 202
        val request = authority.requests.single()
        request.tenant shouldBe TenantId(tenant)
        request.principal shouldBe Principal(TestPrincipals.actorOf(caller), PrincipalKind.HUMAN)
        request.entityRef shouldBe "E-001"
        request.action shouldBe DespatchController.FILING_WORKFLOW
    }

    @Test
    fun `INV-Q-8335 while the set is not current a denying decision is 403 SRT-004 and records nothing`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"), entitySetCurrent = false)
        val authority = Recording(FallbackResult.Found(AuthorityVerdict(false)))
        h.assertRefusedWithNothingRecorded(
            tenant,
            h.call(caller, "E-001", UUID.randomUUID(), authority = authority.replica),
            403,
            "SRT-004",
        )
        authority.requests.size shouldBe 1
    }

    @Test
    fun `INV-Q-8335 while the set is not current no answer from the decision is 403 SRT-004 and records nothing`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"), entitySetCurrent = false)
        val authority = Recording(FallbackResult.Unavailable)
        h.assertRefusedWithNothingRecorded(
            tenant,
            h.call(caller, "E-001", UUID.randomUUID(), authority = authority.replica),
            403,
            "SRT-004",
        )
        authority.requests.size shouldBe 1
    }

    @Test
    fun `INV-Q-8335 with the set not current and no authority replica bound it is 403 SRT-004 and records nothing`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val caller = TestPrincipals.register(tenant, setOf("E-001"), entitySetCurrent = false)
        h.assertRefusedWithNothingRecorded(tenant, h.call(caller, "E-001", UUID.randomUUID()), 403, "SRT-004")
    }
}
