package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Entitlements
import com.dynokore.gst.controlPlane.domain.commercial.Plan
import com.dynokore.gst.controlPlane.domain.commercial.PlanVersion
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.QualificationOutcome
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CommercialOperationsTest {
    private val request = RequestContext("corr-1", "cause-1")
    private val roles = OperatorRoles { actor, role -> actor == "carol" && role == OperatorRole.COMMERCIAL_ADMIN }
    private val plans = LinkedHashMap<Pair<String, String>, Plan>()
    private val qualifications = ArrayList<Qualification>()
    private val planRepository =
        object : PlanRepository {
            override fun find(
                tenantRef: CommercialRef,
                version: PlanVersion,
            ) = plans[tenantRef.value to version.value]

            override fun save(
                plan: Plan,
                recordedBy: String,
            ) {
                plans[plan.tenantRef.value to plan.version.value] = plan
            }
        }
    private val qualificationRepository =
        object : QualificationRepository {
            override fun find(
                customerRef: CommercialRef,
                installationRef: CommercialRef,
            ) = qualifications.lastOrNull { it.customerRef == customerRef && it.installationRef == installationRef }

            override fun latestFor(installationRef: CommercialRef) =
                qualifications.lastOrNull { it.installationRef == installationRef }

            override fun save(
                qualification: Qualification,
                recordedBy: String,
            ) {
                qualifications += qualification
            }
        }
    private val managePlan = ManagePlan(planRepository, roles)
    private val recordQualification = RecordQualification(qualificationRepository, roles)

    private fun planCommand(
        actor: String = "carol",
        version: String = "2026.1",
        maxEntities: Int = 25,
    ) = ManagePlanCommand(
        ActorId(actor),
        CommercialRef("TEN-7B1004"),
        PlanVersion(version),
        Entitlements(maxEntities, 100000),
        request,
    )

    @Test
    fun `only commercial admin acting as herself manages a plan`() {
        managePlan.permits("carol", planCommand()) shouldBe true
        managePlan.permits("mallory", planCommand("mallory")) shouldBe false
        managePlan.permits("carol", planCommand("mallory")) shouldBe false
    }

    @Test
    fun `a plan version is stored and a repeat returns the stored version`() {
        val first = managePlan.perform(planCommand(), managePlan.load(planCommand()))
        managePlan.priorResult(planCommand(maxEntities = 99), managePlan.load(planCommand())) shouldBe first
        managePlan.priorResult(
            planCommand(version = "2026.2"),
            managePlan.load(planCommand(version = "2026.2")),
        ) shouldBe
            null
        plans.size shouldBe 1
    }

    @Test
    fun `a negative entitlement limit is refused by the domain`() {
        shouldThrow<IllegalArgumentException> { Entitlements(-1, 0) }
        shouldThrow<IllegalArgumentException> { PlanVersion("a".repeat(17)) }
    }

    @Test
    fun `a qualification outcome is recorded and the same outcome again returns it`() {
        val command =
            RecordQualificationCommand(
                ActorId("carol"),
                CommercialRef("CUST-0091"),
                CommercialRef("INST-0091"),
                QualificationOutcome.QUALIFIED,
                request,
            )
        recordQualification.permits("carol", command) shouldBe true
        recordQualification.permits("bob", command) shouldBe false
        val recorded = recordQualification.perform(command, recordQualification.load(command))
        recordQualification.priorResult(command, recordQualification.load(command)) shouldBe recorded
        qualifications.size shouldBe 1
    }

    @Test
    fun `another outcome supersedes the earlier record and both stay in the history (INV-Q-8689)`() {
        val command =
            RecordQualificationCommand(
                ActorId("carol"),
                CommercialRef("CUST-0091"),
                CommercialRef("INST-0091"),
                QualificationOutcome.QUALIFIED,
                request,
            )
        recordQualification.perform(command, recordQualification.load(command))
        val other = command.copy(outcome = QualificationOutcome.NOT_QUALIFIED)
        recordQualification.priorResult(other, recordQualification.load(other)) shouldBe null
        recordQualification.perform(other, recordQualification.load(other))
        qualifications.map { it.outcome } shouldBe
            listOf(QualificationOutcome.QUALIFIED, QualificationOutcome.NOT_QUALIFIED)
        qualificationRepository.latestFor(CommercialRef("INST-0091"))?.outcome shouldBe
            QualificationOutcome.NOT_QUALIFIED
    }

    @Test
    fun `recordQualification is audited`() {
        recordQualification.audited shouldBe true
    }
}
