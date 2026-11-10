package com.dynokore.gst.submission.bootstrap

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
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
 * INV-AC-19609 (INV-REQ-584; INV-T-13569 pinned through platform-libs-bom 0.193.0): the AuthorityReplica of the
 * service-runtime this repository resolves decides a MACHINE request locally, from the type of the principal written
 * `machine.` plus the type, and only where the operation-to-roles map lists that role for the action. The replica is
 * built through its public API with a fixed clock; the machine principal comes from the test-fixtures factory of
 * service-runtime-api (INV-T-19621).
 */
class MachineTypePinTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId.of("0a000000-0000-0000-0000-00000000000a")
    private val roles =
        OperationRoleMap { action ->
            if (action == "approve") mapOf("machine.filing-workflow" to CellAllowance.PERMITTED) else emptyMap()
        }
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

    /** A replica that holds the principal's coverage and is current (synchronised, head observed). */
    private fun current(): AuthorityReplica =
        AuthorityReplica(clock, roles).also {
            it.onSnapshotComplete(0)
            it.accept(AuthorityChanged(tenant, "other", 1, null))
            it.observeHead(1)
        }

    /** A replica that never synchronised: it can obtain no answer (degraded). */
    private fun degraded(): AuthorityReplica = AuthorityReplica(clock, roles)

    private fun machine(type: String): Principal =
        MachinePrincipalFixtures.machine("m1", tenant, MachineEntityCoverage.Entity("E-001"), type)

    private fun decide(
        replica: AuthorityReplica,
        type: String,
        action: String,
    ) = replica.decide(
        AuthorizationRequest(tenant, machine(type), "E-001", action),
        Deadline.after(Duration.ofSeconds(30), clock),
    )

    @Test
    fun `INV-AC-19609 the principal is a MACHINE principal of the type given`() {
        val principal = machine("filing-workflow")
        principal.kind shouldBe PrincipalKind.valueOf("MACHINE")
        principal.machineType shouldBe "filing-workflow"
    }

    @Test
    fun `INV-AC-19609 a listed type on the listed action is granted locally with the replica current`() {
        decide(current(), "filing-workflow", "approve") shouldBe granted
    }

    @Test
    fun `INV-AC-19609 a reporting principal on approve is NO_GRANT current and degraded`() {
        decide(current(), "reporting", "approve") shouldBe noGrant
        decide(degraded(), "reporting", "approve") shouldBe noGrant
    }

    @Test
    fun `INV-AC-19609 a filing-workflow principal on an unlisted action is NO_GRANT current and degraded`() {
        decide(current(), "filing-workflow", "delete") shouldBe noGrant
        decide(degraded(), "filing-workflow", "delete") shouldBe noGrant
    }

    @Test
    fun `INV-AC-19609 a type that is not listed never gets MACHINE_FLOW_CONTINUES`() {
        for (replica in listOf(current(), degraded())) {
            decide(replica, "reporting", "approve").reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
            decide(replica, "filing-workflow", "delete").reason shouldNotBe DecisionReason.MACHINE_FLOW_CONTINUES
        }
    }

    @Test
    fun `INV-AC-19609 a type written with other case or a trailing space is NO_GRANT`() {
        for (type in listOf("Filing-Workflow", "filing-workflow ", " filing-workflow")) {
            decide(current(), type, "approve") shouldBe noGrant
            decide(degraded(), type, "approve") shouldBe noGrant
        }
    }
}
