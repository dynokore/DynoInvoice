package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
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
import java.util.UUID

/**
 * INV-T-19603, INV-AC-19610 (INV-REQ-584): the pinned service-runtime decides the machine type locally.
 * The map lists `machine.filing-workflow` for `approve` only; every other type or action is refused with NO_GRANT,
 * current or degraded.
 */
class MachineTypePinTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val roles =
        OperationRoleMap {
            if (it == "approve") mapOf("machine.filing-workflow" to CellAllowance.PERMITTED) else emptyMap()
        }
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

    private fun replica(current: Boolean) =
        AuthorityReplica(clock, roles).also { if (current) it.onSnapshotComplete(0) }

    private fun decide(
        type: String,
        action: String = "approve",
        current: Boolean = true,
    ) = replica(current).decide(
        AuthorizationRequest(
            tenant,
            MachinePrincipalFixtures.machine("p1", tenant, MachineEntityCoverage.Entity("e1"), type),
            "e1",
            action,
        ),
        Deadline.after(Duration.ofSeconds(30), clock),
    )

    @Test
    fun `INV-AC-19610 a filing-workflow machine principal is granted the listed action`() {
        decide("filing-workflow") shouldBe granted
    }

    @Test
    fun `INV-AC-19610 a reporting machine principal is refused the listed action`() {
        decide("reporting") shouldBe noGrant
    }

    @Test
    fun `INV-AC-19610 a filing-workflow machine principal is refused an unlisted action`() {
        decide("filing-workflow", "other") shouldBe noGrant
    }

    @Test
    fun `INV-AC-19610 both refusals hold when the replica can obtain no answer`() {
        decide("reporting", current = false) shouldBe noGrant
        decide("filing-workflow", "other", current = false) shouldBe noGrant
        decide("reporting", current = false).reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
        decide("filing-workflow", "other", current = false).reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
    }

    @Test
    fun `INV-AC-19610 a type written with other case or a trailing space is refused`() {
        decide("Filing-Workflow") shouldBe noGrant
        decide("filing-workflow ") shouldBe noGrant
    }
}
