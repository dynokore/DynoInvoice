package com.dynokore.platform.contracttests.moduleapi

import com.dynokore.platform.configresolver.ApprovalRule
import com.dynokore.platform.configresolver.ConfigHarness
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.RecordedResolution
import com.dynokore.platform.configresolver.ResolutionRecorder
import com.dynokore.platform.configresolver.ResolutionSubject
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.TestCatalogue
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.resolve
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID

/**
 * config-resolver's module-API consumer contract test (the ModuleApiConsumerContractTest pattern; INV-T-2877 rev 5,
 * ruling INV-Q-3925): a fake consumer written the way an owning service's decision use case is written (CS-28,
 * TPL-03) — constructor-injected against only the `config-resolver-api` ports [PolicyReader] and
 * [ResolutionRecorder] — run against the real [com.dynokore.platform.configresolver.JdbcPolicyReader] and
 * [com.dynokore.platform.configresolver.JdbcResolutionRecorder] on PostgreSQL 18 (config-resolver's own harness).
 * If resolution precedence, the typed enum read, first-write-wins recording or the refusal of an unregistered key
 * ever drifts from what a service is written to expect, this fails.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigResolverModuleApiConsumerContractTest {
    /** Written as a service's decision use case: reads its policy at the transaction date and records it. */
    private class FakeConsumerUseCase(
        private val reader: PolicyReader,
        private val recorder: ResolutionRecorder,
    ) {
        fun approvalRule(
            scope: PolicyScope,
            at: LocalDate,
        ): ApprovalRule = reader.resolve<ApprovalRule>(TestCatalogue.ENTITY_POLICY, scope, at).value

        fun decide(
            subject: ResolutionSubject,
            scope: PolicyScope,
            at: LocalDate,
        ): RecordedResolution = recorder.record(subject, reader.resolve(TestCatalogue.ENTITY_POLICY, scope, at), scope)
    }

    private val harness = ConfigHarness("postgres:18")
    private val useCase = FakeConsumerUseCase(harness.reader, harness.recorder)
    private val march = LocalDate.of(2026, 3, 15)

    @AfterAll
    fun stop() = harness.close()

    private fun <T> inTenant(
        tenant: TenantId,
        block: () -> T,
    ): T = harness.asTenant(tenant) { harness.app.inTransaction(block) }

    private fun setEntity(
        tenant: TenantId,
        option: String,
        from: LocalDate,
    ) = harness.setTenant(
        tenant,
        TestCatalogue.ENTITY_POLICY,
        ScopeRef(ScopeLevel.ENTITY, "E1"),
        listOf(SelectedOption(option)),
        from,
        entity = "E1",
    )

    @Test
    fun `with nothing set the catalogue default is read as the service's enum`() {
        val tenant = TenantId(UUID.randomUUID())
        inTenant(tenant) { useCase.approvalRule(PolicyScope.entity(tenant, "E1"), march) } shouldBe
            ApprovalRule.TWO_PERSON
    }

    @Test
    fun `an entity override wins, is recorded with its source, and a later change never alters the record`() {
        val tenant = TenantId(UUID.randomUUID())
        val scope = PolicyScope.entity(tenant, "E1")
        val subject = ResolutionSubject("submission", "doc-1/v1")
        val first = setEntity(tenant, "FOUR_EYES_PLUS", march.minusMonths(1))

        val recorded = inTenant(tenant) { useCase.decide(subject, scope, march) }
        recorded.selected shouldBe listOf(SelectedOption("FOUR_EYES_PLUS"))
        recorded.source shouldBe PolicySource.Override(ScopeLevel.ENTITY, "E1", first)

        setEntity(tenant, "TWO_PERSON", march.minusDays(1))
        inTenant(tenant) { useCase.decide(subject, scope, march) } shouldBe recorded
    }

    @Test
    fun `a key the catalogue does not register is refused, never defaulted`() {
        val tenant = TenantId(UUID.randomUUID())
        assertThrows(UnknownVariationPoint::class.java) {
            inTenant(tenant) { harness.reader.resolve(TestCatalogue.UNKNOWN, PolicyScope.entity(tenant, "E1"), march) }
        }
    }
}
