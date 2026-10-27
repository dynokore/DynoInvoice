package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Licence
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.isQualified
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import java.time.Clock
import java.time.temporal.ChronoUnit

data class IssueLicenceCommand(
    val actor: ActorId,
    val installationRef: CommercialRef,
    val term: String,
    val request: RequestContext,
)

/** What `issueLicence` reads: the licence issued for the term and the latest qualification of the installation. */
data class IssueState(
    val issued: Licence?,
    val qualification: Qualification?,
)

/**
 * issueLicence (SCR-38.3, INV-REQ-906 b1): `commercial.admin` issues the signed licence file of an installation term.
 * The precondition "Qualification recorded" holds when the latest outcome of the installation is QUALIFIED
 * (INV-Q-8689 A). The repeat key is (installationRef, term): a repeat returns the original licence, so the file is
 * signed once.
 */
class IssueLicence(
    private val licences: LicenceRepository,
    private val qualifications: QualificationRepository,
    private val signer: LicenceSigner,
    private val roles: OperatorRoles,
    private val clock: Clock,
    private val newLicenceRef: () -> CommercialRef,
) : DeclaredOperation<IssueLicenceCommand, IssueState, Licence> {
    override val operationId = "issueLicence"
    override val reversibility = Reversibility.COMPENSABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: IssueLicenceCommand,
    ) = roles.holds(actor, OperatorRole.COMMERCIAL_ADMIN) && actor == command.actor.value

    override fun repeatKey(command: IssueLicenceCommand) = "${command.installationRef.value}:${command.term}"

    override fun load(command: IssueLicenceCommand) =
        IssueState(
            licences.findByTerm(command.installationRef, command.term),
            qualifications.latestFor(command.installationRef),
        )

    override fun priorResult(
        command: IssueLicenceCommand,
        state: IssueState,
    ) = state.issued

    override fun unmetPrecondition(
        command: IssueLicenceCommand,
        state: IssueState,
    ): String? = if (state.qualification.isQualified()) null else QUALIFICATION_NOT_RECORDED

    override fun perform(
        command: IssueLicenceCommand,
        state: IssueState,
    ): Licence {
        val licenceRef = newLicenceRef()
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val file = signer.sign(licenceRef, command.installationRef, command.term, issuedAt)
        return Licence(licenceRef, command.installationRef, command.term, issuedAt, file)
            .also { licences.save(it, command.actor.value) }
    }

    override fun audit(
        command: IssueLicenceCommand,
        state: IssueState,
        result: Licence,
    ) = OperationAudit(SCOPE, null, null, auditContext(repeatKey(command), command.request))

    companion object {
        const val QUALIFICATION_NOT_RECORDED = "QUALIFICATION_NOT_RECORDED"
    }
}

/** getLicence: reads an issued licence with the exact bytes of its signed file. */
class GetLicence(
    private val licences: LicenceRepository,
) {
    fun get(licenceRef: CommercialRef): Licence? = licences.findByRef(licenceRef)
}
