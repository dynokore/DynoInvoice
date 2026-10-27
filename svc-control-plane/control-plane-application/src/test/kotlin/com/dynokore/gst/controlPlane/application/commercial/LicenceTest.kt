package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Licence
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.QualificationOutcome
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LicenceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00.123456789Z"), ZoneOffset.UTC)
    private val installation = CommercialRef("INST-0091")
    private val qualifications = ArrayList<Qualification>()
    private val licences = LinkedHashMap<Pair<String, String>, Licence>()
    private var counter = 0
    private val signed = ArrayList<Instant>()

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
    private val licenceRepository =
        object : LicenceRepository {
            override fun findByRef(licenceRef: CommercialRef) =
                licences.values.firstOrNull {
                    it.licenceRef ==
                        licenceRef
                }

            override fun findByTerm(
                installationRef: CommercialRef,
                term: String,
            ) = licences[installationRef.value to term]

            override fun save(
                licence: Licence,
                issuedBy: String,
            ) {
                licences[licence.installationRef.value to licence.term] = licence
            }
        }
    private val roles = OperatorRoles { actor, role -> actor == "carol" && role == OperatorRole.COMMERCIAL_ADMIN }
    private val issue =
        IssueLicence(
            licenceRepository,
            qualificationRepository,
            { ref, inst, term, at ->
                signed += at
                "$ref|$inst|$term|$at".toByteArray()
            },
            roles,
            clock,
        ) { CommercialRef("LIC-${++counter}") }

    private fun command(term: String = "2026-10-01/2027-09-30") =
        IssueLicenceCommand(ActorId("carol"), installation, term, RequestContext("corr", "cause"))

    private fun qualify(outcome: QualificationOutcome) {
        qualifications += Qualification(CommercialRef("CUST-1"), installation, outcome)
    }

    private fun unmet(command: IssueLicenceCommand) = issue.unmetPrecondition(command, issue.load(command))

    @Test
    fun `only commercial admin acting as herself issues a licence`() {
        issue.permits("carol", command()) shouldBe true
        issue.permits("mallory", command()) shouldBe false
        issue.permits("carol", command().copy(actor = ActorId("mallory"))) shouldBe false
    }

    @Test
    fun `with no qualification recorded the issue is refused`() {
        unmet(command()) shouldBe IssueLicence.QUALIFICATION_NOT_RECORDED
    }

    @Test
    fun `INV-Q-8689 probe - QUALIFIED then another outcome refuses issueLicence, QUALIFIED issues it`() {
        qualify(QualificationOutcome.QUALIFIED)
        unmet(command()) shouldBe null
        qualify(QualificationOutcome.NOT_QUALIFIED)
        unmet(command()) shouldBe IssueLicence.QUALIFICATION_NOT_RECORDED
        qualify(QualificationOutcome.QUALIFIED)
        unmet(command()) shouldBe null
    }

    @Test
    fun `the licence is signed once and a repeat returns the original`() {
        qualify(QualificationOutcome.QUALIFIED)
        val first = issue.perform(command(), issue.load(command()))
        first.issuedAt shouldBe Instant.parse("2026-10-27T08:00:00Z")
        issue.priorResult(command(), issue.load(command())) shouldBe first
        issue.priorResult(command("2027-10-01/2028-09-30"), issue.load(command("2027-10-01/2028-09-30"))) shouldBe null
        GetLicence(licenceRepository).get(first.licenceRef) shouldBe first
        GetLicence(licenceRepository).get(CommercialRef("LIC-404")) shouldBe null
        signed.size shouldBe 1
    }

    @Test
    fun `issueLicence is audited and compensable`() {
        issue.audited shouldBe true
        issue.reversibility.name shouldBe "COMPENSABLE"
    }
}
