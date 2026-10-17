package com.dynokore.platform.contracttests.moduleapi

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.platform.configresolver.ConfigHarness
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.JdbcPolicyReader
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.resolve
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID

/**
 * gst-catalogue's module-API consumer contract test (INV-T-2877 rev 5, ruling INV-Q-3925): the generated
 * `VariationPoint` catalogue, at the version platform-libs pins (`gstCatalogue` in gradle/libs.versions.toml), as
 * product-runtime binds it ([GstVariationPointCatalogue], INV-Q-3119) and config-resolver resolves through it. The
 * fake consumer is written the way `submission-svc`'s decision use case is: only the `config-resolver-api`
 * [PolicyReader] port and its own option enum (TPL-10), over a real catalogue row it owns (VP-14, INV-REQ-1275) on
 * the real [JdbcPolicyReader] on PostgreSQL 18. If the catalogue's rows, defaults, options or owners ever drift
 * from what a service is written to expect, this fails.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GstCatalogueModuleApiConsumerContractTest {
    /** submission-svc's own enum of VP-14's option keys (TPL-10). */
    private enum class ValidationVerdictMode { ADVISORY, BLOCKING }

    /** Written as a `submission-svc` use case: reads VP-14 at the transaction date as its enum. */
    private class FakeConsumerUseCase(
        private val reader: PolicyReader,
    ) {
        fun verdictMode(
            scope: PolicyScope,
            at: LocalDate,
        ) = reader.resolve<ValidationVerdictMode>(VERDICT_MODE, scope, at)
    }

    private val catalogue: VariationPointCatalogue = GstVariationPointCatalogue("submission-svc")
    private val harness = ConfigHarness("postgres:18")
    private val reader = JdbcPolicyReader(harness.dsl, catalogue, DeploymentForm.SAAS, ConfigHarness.SCHEMA)
    private val useCase = FakeConsumerUseCase(reader)
    private val march = LocalDate.of(2026, 3, 15)

    @AfterAll
    fun stop() = harness.close()

    private fun <T> inTenant(
        tenant: TenantId,
        block: () -> T,
    ): T = harness.asTenant(tenant) { harness.app.inTransaction(block) }

    @Test
    fun `the suite runs against the gst-catalogue version platform-libs pins`() {
        val pinned = System.getProperty("contracttests.gstCatalogueVersion")
        // The class's own resource URL names the jar it was loaded from (jar:file:.../gst-catalogue-<v>.jar!/...).
        val url = requireNotNull(VariationPoint::class.java.getResource("VariationPoint.class")).toString()
        val jar = url.substringBefore("!/").substringAfterLast('/')
        jar shouldEndWith "gst-catalogue-$pinned.jar"
    }

    @Test
    fun `with nothing set the catalogue default of a row the service owns is read as its enum`() {
        val tenant = TenantId(UUID.randomUUID())
        val resolved = inTenant(tenant) { useCase.verdictMode(PolicyScope.entity(tenant, "E1"), march) }
        resolved.value shouldBe ValidationVerdictMode.ADVISORY
        resolved.source shouldBe PolicySource.CatalogueDefault
    }

    @Test
    fun `an entity's election of another catalogue option is read as that enum constant`() {
        val tenant = TenantId(UUID.randomUUID())
        val ref = ScopeRef(ScopeLevel.ENTITY, "E1")
        val id = harness.setTenant(tenant, VERDICT_MODE, ref, listOf(SelectedOption("BLOCKING")), march, entity = "E1")
        val resolved = inTenant(tenant) { useCase.verdictMode(PolicyScope.entity(tenant, "E1"), march) }
        resolved.value shouldBe ValidationVerdictMode.BLOCKING
        resolved.source shouldBe PolicySource.Override(ScopeLevel.ENTITY, "E1", id)
    }

    @Test
    fun `the rows the service owns are listed and each one resolves to a definition`() {
        val owned = catalogue.keys()
        owned shouldContain VERDICT_MODE
        owned.forEach { catalogue.require(it).key shouldBe it }
    }

    @Test
    fun `a key the catalogue does not register is refused, never defaulted`() {
        val tenant = TenantId(UUID.randomUUID())
        assertThrows(UnknownVariationPoint::class.java) {
            inTenant(tenant) { reader.resolve(VariationPointKey("VP-999"), PolicyScope.entity(tenant, "E1"), march) }
        }
    }

    private companion object {
        val VERDICT_MODE = VariationPointKey("VP-14")
    }
}
