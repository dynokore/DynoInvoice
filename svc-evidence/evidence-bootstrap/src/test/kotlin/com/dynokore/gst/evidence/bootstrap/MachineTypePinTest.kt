package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-T-19601, INV-AC-19608, INV-REQ-584: the service-runtime this service resolves decides a machine request locally
 * only where `machine.<type>` is listed for the action. The principals come from the test-fixtures factory.
 */
class MachineTypePinTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val roles =
        OperationRoleMap {
            if (it == "approve") mapOf("machine.filing-workflow" to CellAllowance.PERMITTED) else emptyMap()
        }
    private val unavailable =
        ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

    private fun current() = AuthorityReplica(clock, roles, fallback = unavailable).also { it.onSnapshotComplete(0) }

    private fun degraded() = AuthorityReplica(clock, roles, fallback = unavailable)

    private fun decide(
        replica: AuthorityReplica,
        type: String,
        action: String = "approve",
    ) = replica.decide(
        AuthorizationRequest(
            tenant,
            MachinePrincipalFixtures.machine("p1", tenant, MachineEntityCoverage.Entity("e1"), type),
            "e1",
            action,
        ),
        Deadline.after(Duration.ofSeconds(30), clock),
    )

    @Test
    fun `INV-AC-19608 the listed type is granted on the listed action when the replica is current`() {
        decide(current(), "filing-workflow") shouldBe granted
    }

    @Test
    fun `INV-AC-19608 a type the map does not list for the action is refused, current and degraded`() {
        for (replica in listOf(current(), degraded())) {
            val decision = decide(replica, "reporting")
            decision shouldBe noGrant
            decision.reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
        }
    }

    @Test
    fun `INV-AC-19608 the listed type on an action the map does not list is refused, current and degraded`() {
        for (replica in listOf(current(), degraded())) {
            val decision = decide(replica, "filing-workflow", "other")
            decision shouldBe noGrant
            decision.reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
        }
    }

    @Test
    fun `INV-AC-19608 a type written with another case or a trailing space is refused, current and degraded`() {
        for (replica in listOf(current(), degraded())) {
            for (type in listOf("Filing-Workflow", "filing-workflow ")) decide(replica, type) shouldBe noGrant
        }
    }
}
