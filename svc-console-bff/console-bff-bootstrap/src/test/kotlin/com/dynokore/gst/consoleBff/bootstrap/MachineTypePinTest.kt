package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * INV-T-19604, INV-REQ-584: the pinned service-runtime decides a MACHINE request from the token type and the
 * operation-to-roles map, locally, whatever the replica state. The map here lists one type for one action; the
 * principals come from the test-fixtures factory and the replica is built through its public API only.
 */
class MachineTypePinTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId.of("0a000000-0000-0000-0000-00000000000a")
    private val entity = "E-001"
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
    private val roles =
        OperationRoleMap { action ->
            if (action == LISTED) mapOf("machine.filing-workflow" to CellAllowance.PERMITTED) else emptyMap()
        }

    private fun replica(current: Boolean): AuthorityReplica {
        val replica =
            AuthorityReplica(clock, roles, fallback = AuthorityFallback { _, _ -> FallbackResult.Unavailable })
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
    fun `INV-AC-19612 the listed type is granted on the listed action when the replica is current`() {
        decide("filing-workflow", LISTED, current = true) shouldBe granted
    }

    @Test
    fun `INV-AC-19612 a type the map does not list for the action is NO_GRANT current and degraded`() {
        for (current in listOf(true, false)) {
            val decision = decide("reporting", LISTED, current)
            decision shouldBe noGrant
            decision.reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
        }
    }

    @Test
    fun `INV-AC-19612 the listed type on an unlisted action is NO_GRANT current and degraded`() {
        for (current in listOf(true, false)) {
            val decision = decide("filing-workflow", UNLISTED, current)
            decision shouldBe noGrant
            decision.reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
        }
    }

    @Test
    fun `INV-AC-19612 a type written with another case or a trailing space is NO_GRANT current and degraded`() {
        for (type in listOf("Filing-Workflow", "filing-workflow ")) {
            for (current in listOf(true, false)) decide(type, LISTED, current) shouldBe noGrant
        }
    }

    private companion object {
        const val LISTED = "approve"
        const val UNLISTED = "delete"
    }
}
