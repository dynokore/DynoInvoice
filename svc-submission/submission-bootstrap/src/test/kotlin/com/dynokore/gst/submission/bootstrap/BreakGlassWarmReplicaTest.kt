package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.syncedge.BreakGlassDoubles
import com.dynokore.gst.submission.syncedge.BreakGlassTestBeans
import com.dynokore.gst.submission.syncedge.ContractDocument
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/**
 * INV-AC-22036: the context of `BreakGlassRestrictionTest`, started the way that test starts it, has an authority
 * replica that completed its snapshot, so it decides locally instead of degraded.
 */
class BreakGlassWarmReplicaTest {
    private val loader = javaClass.classLoader

    private fun serve(
        dir: Path,
        block: (AuthorityReplica) -> Unit,
    ) {
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
        SubmissionContext
            .startServlet(dir, listOf(BreakGlassTestBeans::class.java), properties, loader)
            .use { block(it.getBean(AuthorityReplica::class.java)) }
    }

    @Test
    fun `INV-AC-22036 the replica lag is within the bound after the start`(
        @TempDir dir: Path,
    ) {
        serve(dir) { replica ->
            val before = BreakGlassDoubles.now.get()
            try {
                BreakGlassDoubles.now.set(before.plus(replica.lagBound).plusSeconds(CLOCK_STEP_SECONDS))
                replica.lag().isWithin(replica.lagBound) shouldBe true
            } finally {
                BreakGlassDoubles.now.set(before)
            }
        }
    }

    @Test
    fun `INV-AC-22036 a human without a grant is decided locally with no grant`(
        @TempDir dir: Path,
    ) {
        serve(dir) { replica ->
            val request =
                AuthorizationRequest(
                    TenantId.of(BreakGlassTestBeans.TENANT_T),
                    Principal("nobody", PrincipalKind.HUMAN),
                    "e1",
                    "submission.replay",
                )
            val decision = replica.decide(request, Deadline.after(Duration.ofSeconds(5), BREAK_GLASS_CLOCK))
            Triple(decision.allowed, decision.source, decision.reason) shouldBe
                Triple(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        }
    }

    private companion object {
        const val ISSUER = "https://access.test/"
        const val CLOCK_STEP_SECONDS = 60L
        const val AUDIENCE = "gst-public-api"
        val BREAK_GLASS_CLOCK: java.time.Clock =
            java.time.Clock.fixed(BreakGlassTestBeans.BEFORE_E, java.time.ZoneOffset.UTC)
    }
}
