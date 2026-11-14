package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.syncedge.BreakGlassDoubles
import com.dynokore.gst.submission.syncedge.BreakGlassTestBeans
import com.dynokore.gst.submission.syncedge.ContractDocument
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/**
 * INV-AC-22077: on the warm replica of the context of `BreakGlassRestrictionTest`, a machine token that covers the
 * entity and whose type the map lists is granted locally; any other machine token is not.
 */
class BreakGlassMachineCoverageTest {
    private val loader = javaClass.classLoader

    private fun decide(
        dir: Path,
        type: String,
        coverage: MachineEntityCoverage,
        tenant: String = BreakGlassTestBeans.TENANT_T,
    ): AuthorityDecision {
        val services =
            ContractDocument
                .operations(ContractDocument.text(loader))
                .flatMap { it.callerFields }
                .filter { it != "any" && !it.startsWith("any-except:") }
                .distinct()
                .sorted()
        val properties =
            services
                .flatMapIndexed { index, service ->
                    listOf(
                        "service-runtime.workload-identity.callers[$index].service=$service",
                        "service-runtime.workload-identity.callers[$index].subject=sa-$service",
                    )
                }.toTypedArray() +
                arrayOf(
                    "service-runtime.event-loops.enabled=false",
                    "service-runtime.machine-identity.issuer=$ISSUER",
                    "service-runtime.machine-identity.audience=$AUDIENCE",
                )
        BreakGlassDoubles.reset(BreakGlassTestBeans.BEFORE_E)
        return SubmissionContext
            .startServlet(dir, listOf(BreakGlassTestBeans::class.java), properties, loader)
            .use { context ->
                val principal = MachinePrincipalFixtures.machine("client-1", TenantId.of(tenant), coverage, type)
                context.getBean(AuthorityReplica::class.java).decide(
                    AuthorizationRequest(TenantId.of(BreakGlassTestBeans.TENANT_T), principal, ENTITY, KEY),
                    Deadline.after(Duration.ofSeconds(DEADLINE_SECONDS), CLOCK),
                )
            }
    }

    @Test
    fun `INV-AC-22077 a reporting token covering the entity of the tenant is granted locally`(
        @TempDir dir: Path,
    ) {
        decide(dir, "reporting", MachineEntityCoverage.Entity(ENTITY)) shouldBe GRANTED
    }

    @Test
    fun `INV-AC-22077 a reporting token with installation coverage is not granted`(
        @TempDir dir: Path,
    ) {
        decide(dir, "reporting", MachineEntityCoverage.Installation) shouldBe DENIED
    }

    @Test
    fun `INV-AC-22077 an ingest token covering the entity is not granted`(
        @TempDir dir: Path,
    ) {
        decide(dir, "ingest", MachineEntityCoverage.Entity(ENTITY)) shouldBe DENIED
    }

    @Test
    fun `INV-AC-22077 a reporting token of another tenant is not granted`(
        @TempDir dir: Path,
    ) {
        decide(dir, "reporting", MachineEntityCoverage.Entity(ENTITY), OTHER_TENANT) shouldBe DENIED
    }

    @Test
    fun `INV-AC-22077 a reporting token covering another entity is not granted`(
        @TempDir dir: Path,
    ) {
        decide(dir, "reporting", MachineEntityCoverage.Entity(OTHER_ENTITY)) shouldBe DENIED
    }

    private companion object {
        const val ISSUER = "https://access.test/"
        const val AUDIENCE = "gst-public-api"
        const val KEY = "machine.submissions.read"
        const val ENTITY = "e1"
        const val OTHER_ENTITY = "e2"
        const val OTHER_TENANT = "0b8f5f0e-52a4-4d0a-9d4e-3f1f6a1c7e12"
        const val DEADLINE_SECONDS = 5L
        val GRANTED = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
        val DENIED = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        val CLOCK: java.time.Clock = java.time.Clock.fixed(BreakGlassTestBeans.BEFORE_E, java.time.ZoneOffset.UTC)
    }
}
