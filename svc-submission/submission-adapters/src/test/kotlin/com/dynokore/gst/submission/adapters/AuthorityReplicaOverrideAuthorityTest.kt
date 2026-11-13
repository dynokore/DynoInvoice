package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.gate.AuthorityReplicaOverrideAuthority
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The adapter of `OverrideAuthority` over `AuthorityReplica.decide` (INV-AC-21137, INV-Q-10027). */
class AuthorityReplicaOverrideAuthorityTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class Recorder(
        private val answer: AuthorityDecision,
    ) {
        val requests = mutableListOf<AuthorizationRequest>()
        val deadlines = mutableListOf<Deadline>()

        fun decide(
            request: AuthorizationRequest,
            deadline: Deadline,
        ): AuthorityDecision {
            requests += request
            deadlines += deadline
            return answer
        }
    }

    private fun holds(
        allowed: Boolean,
        source: DecisionSource,
    ): Pair<Boolean, Recorder> {
        val recorder = Recorder(AuthorityDecision(allowed, source, DecisionReason.GRANTED))
        val result =
            CurrentDeadline.callWith(deadline) {
                AuthorityReplicaOverrideAuthority(recorder::decide, tenants).holds("u1", "validation.override", "E1")
            }
        return result to recorder
    }

    @Test
    fun `INV-AC-21137 decide gets the bound tenant, the human principal, the entity and the permission`() {
        val (_, recorder) = holds(true, DecisionSource.LOCAL)
        recorder.requests.size shouldBe 1
        val request = recorder.requests.single()
        request.tenant shouldBe tenant
        request.principal.id shouldBe "u1"
        request.principal.kind shouldBe PrincipalKind.HUMAN
        request.entityRef shouldBe "E1"
        request.action shouldBe "validation.override"
        request.operation shouldBe null
        recorder.deadlines.single() shouldBe deadline
    }

    @Test
    fun `INV-AC-21137 an allowed LOCAL decision holds`() {
        holds(true, DecisionSource.LOCAL).first shouldBe true
    }

    @Test
    fun `INV-AC-21137 an allowed FALLBACK decision holds`() {
        holds(true, DecisionSource.FALLBACK).first shouldBe true
    }

    @Test
    fun `INV-AC-21137 an allowed DEGRADED decision does not hold`() {
        holds(true, DecisionSource.DEGRADED).first shouldBe false
    }

    @Test
    fun `INV-AC-21137 a decision that does not allow does not hold`() {
        holds(false, DecisionSource.LOCAL).first shouldBe false
        holds(false, DecisionSource.FALLBACK).first shouldBe false
    }

    @Test
    fun `INV-AC-21137 with no deadline bound the call is an error and decide is not called`() {
        val recorder = Recorder(AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED))
        assertThrows<IllegalStateException> {
            AuthorityReplicaOverrideAuthority(recorder::decide, tenants).holds("u1", "validation.override", "E1")
        }
        recorder.requests.size shouldBe 0
    }
}
