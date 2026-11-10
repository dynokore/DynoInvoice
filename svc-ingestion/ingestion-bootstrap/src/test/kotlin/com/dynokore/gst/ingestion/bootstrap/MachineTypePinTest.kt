package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
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
 * INV-T-19600, INV-AC-19607, INV-REQ-584: the service-runtime that svc-ingestion resolves decides a machine request
 * locally. The type must be listed, as `machine.` and the type, for the action in the operation-to-roles map, else
 * NO_GRANT, in both states of the replica (current and degraded). The map and its role names are test data.
 */
class MachineTypePinTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entity = "E-001"
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

    private val roles =
        OperationRoleMap { key ->
            if (key == "approve") mapOf("machine.filing-workflow" to CellAllowance.PERMITTED) else emptyMap()
        }

    private fun replica(current: Boolean): AuthorityReplica {
        val none =
            ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = none)
        // degraded: a replica that has had no feed and gets no answer from the fallback
        if (current) {
            replica.accept(AuthorityChanged(tenant, "other", 1, null))
            replica.observeHead(1)
            replica.onSnapshotComplete(1)
        }
        return replica
    }

    private fun decide(
        type: String,
        action: String,
        current: Boolean,
    ): AuthorityDecision {
        val principal = MachinePrincipalFixtures.machine("m1", tenant, MachineEntityCoverage.Entity(entity), type)
        return replica(current).decide(
            AuthorizationRequest(tenant, principal, entity, action),
            Deadline.after(Duration.ofSeconds(30), clock),
        )
    }

    @Test
    fun `INV-AC-19607 the listed type on the listed action is granted locally by the current replica`() {
        decide("filing-workflow", "approve", current = true) shouldBe granted
    }

    @Test
    fun `INV-AC-19607 a type the map does not list for the action is NO_GRANT, current and degraded`() {
        for (current in listOf(true, false)) {
            decide("reporting", "approve", current) shouldBe noGrant
        }
    }

    @Test
    fun `INV-AC-19607 the listed type on an action the map does not list is NO_GRANT, current and degraded`() {
        for (current in listOf(true, false)) {
            decide("filing-workflow", "delete", current) shouldBe noGrant
        }
    }

    @Test
    fun `INV-AC-19607 a refusal is never MACHINE_FLOW_CONTINUES`() {
        for (current in listOf(true, false)) {
            decide("reporting", "approve", current).reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
            decide("filing-workflow", "delete", current).reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
        }
    }

    @Test
    fun `INV-AC-19607 a type written in another case or with a trailing space is NO_GRANT`() {
        for (type in listOf("Filing-Workflow", "filing-workflow ")) {
            for (current in listOf(true, false)) {
                decide(type, "approve", current) shouldBe noGrant
            }
        }
    }
}
