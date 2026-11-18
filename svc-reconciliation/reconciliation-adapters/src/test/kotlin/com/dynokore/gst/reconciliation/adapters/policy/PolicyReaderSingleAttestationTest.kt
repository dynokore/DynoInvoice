package com.dynokore.gst.reconciliation.adapters.policy

import com.dynokore.gst.reconciliation.application.workpaper.SoleAttester
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.RelaxationAcknowledgement
import com.dynokore.platform.configresolver.RelaxationId
import com.dynokore.platform.configresolver.RelaxationReader
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-24436 cases a to f: the sole-operator fact is read from VP-40 and the acknowledgement behind it. */
class PolicyReaderSingleAttestationTest {
    private val tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val tenant = tenantId.toString()
    private val on = LocalDate.parse("2026-05-01")
    private val vp40 = VariationPointKey("VP-40")
    private val expectedScope = PolicyScope.entity(TenantId(tenantId), "E1")

    private class Call(
        val key: VariationPointKey,
        val scope: PolicyScope,
        val at: LocalDate,
    )

    private class FakePolicy(
        private val option: String,
        private val failure: RuntimeException? = null,
    ) : PolicyReader {
        val calls = mutableListOf<Call>()

        override fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): ResolvedSetting {
            calls += Call(key, scope, at)
            failure?.let { throw it }
            return ResolvedSetting(
                key,
                at,
                listOf(SelectedOption(option)),
                PolicySource.Override(ScopeLevel.ENTITY, "E1", UUID.randomUUID()),
            )
        }
    }

    private class FakeRelaxations(
        private val acks: List<RelaxationAcknowledgement>,
        private val failure: RuntimeException? = null,
    ) : RelaxationReader {
        val calls = mutableListOf<Call>()

        override fun acknowledgementsInForce(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): List<RelaxationAcknowledgement> {
            calls += Call(key, scope, at)
            failure?.let { throw it }
            return acks
        }
    }

    private fun ack(
        principal: String,
        at: String,
    ) = RelaxationAcknowledgement(
        UUID.randomUUID(),
        RelaxationId(UUID.randomUUID()),
        "tenant.admin",
        principal,
        LocalDate.parse("2026-03-01"),
        Instant.parse(at),
    )

    private val u9 = ack("U9", "2026-03-01T22:30:00Z")
    private val u8 = ack("U8", "2026-03-02T00:00:00Z")
    private val sole = "SINGLE_ATTESTATION_SOLE_OPERATOR"

    private fun assertAsked(calls: List<Call>) {
        calls.size shouldBe 1
        calls[0].key shouldBe vp40
        calls[0].scope.toString() shouldBe expectedScope.toString()
        calls[0].scope.tenant shouldBe TenantId(tenantId)
        calls[0].scope.entity shouldBe "E1"
        calls[0].scope.ref(ScopeLevel.ENTITY)?.id shouldBe expectedScope.ref(ScopeLevel.ENTITY)?.id
        calls[0].at shouldBe on
    }

    @Test
    fun `INV-AC-24436 case a one acknowledgement gives the user ID and the date in the business zone`() {
        val policy = FakePolicy(sole)
        val relaxations = FakeRelaxations(listOf(u9))
        val result = PolicyReaderSingleAttestation(policy, relaxations).soleOperator(tenant, "E1", on)
        // 22:30Z on 2026-03-01 is 06:30 on 2026-03-02 in Asia/Singapore
        result shouldBe SoleAttester("U9", LocalDate.parse("2026-03-02"))
        assertAsked(policy.calls)
        assertAsked(relaxations.calls)
    }

    @Test
    fun `INV-AC-24436 case b two acknowledgements give the earliest by acknowledgedAt`() {
        val result =
            PolicyReaderSingleAttestation(FakePolicy(sole), FakeRelaxations(listOf(u8, u9)))
                .soleOperator(tenant, "E1", on)
        result shouldBe SoleAttester("U9", LocalDate.parse("2026-03-02"))
    }

    @Test
    fun `INV-AC-24436 case b a tie on acknowledgedAt is broken by the recorded order`() {
        val first = ack("UA", "2026-03-01T22:30:00Z")
        val second = ack("UB", "2026-03-01T22:30:00Z")
        PolicyReaderSingleAttestation(FakePolicy(sole), FakeRelaxations(listOf(first, second)))
            .soleOperator(tenant, "E1", on)
            ?.acknowledgedBy shouldBe "UA"
    }

    @Test
    fun `INV-AC-24436 case c the default option gives null and the relaxation reader is not asked`() {
        val relaxations = FakeRelaxations(listOf(u9))
        val result =
            PolicyReaderSingleAttestation(FakePolicy("PREPARER_AND_APPROVER"), relaxations)
                .soleOperator(tenant, "E1", on)
        result shouldBe null
        relaxations.calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-24436 case d an election without an acknowledgement gives null`() {
        val relaxations = FakeRelaxations(emptyList())
        PolicyReaderSingleAttestation(FakePolicy(sole), relaxations).soleOperator(tenant, "E1", on) shouldBe null
        relaxations.calls.size shouldBe 1
    }

    @Test
    fun `INV-AC-24436 case e a policy reader exception propagates`() {
        val failure = IllegalStateException("policy down")
        val relaxations = FakeRelaxations(listOf(u9))
        val thrown =
            shouldThrow<IllegalStateException> {
                PolicyReaderSingleAttestation(FakePolicy(sole, failure), relaxations).soleOperator(tenant, "E1", on)
            }
        (thrown === failure) shouldBe true
        relaxations.calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-24436 case f a relaxation reader exception propagates`() {
        val failure = IllegalArgumentException("relaxation down")
        val thrown =
            shouldThrow<IllegalArgumentException> {
                PolicyReaderSingleAttestation(FakePolicy(sole), FakeRelaxations(emptyList(), failure))
                    .soleOperator(tenant, "E1", on)
            }
        (thrown === failure) shouldBe true
    }

    @Test
    fun `INV-AC-24436 case c a selection of several options is not the sole-operator option`() {
        val multi =
            object : PolicyReader {
                override fun resolve(
                    key: VariationPointKey,
                    scope: PolicyScope,
                    at: LocalDate,
                ) = ResolvedSetting(
                    key,
                    at,
                    listOf(SelectedOption(sole), SelectedOption("PREPARER_AND_APPROVER")),
                    PolicySource.Override(ScopeLevel.ENTITY, "E1", UUID.randomUUID()),
                )
            }
        val relaxations = FakeRelaxations(listOf(u9))
        PolicyReaderSingleAttestation(multi, relaxations).soleOperator(tenant, "E1", on) shouldBe null
        relaxations.calls.size shouldBe 0
    }
}
