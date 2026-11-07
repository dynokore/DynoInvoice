package com.dynokore.gst.controlPlane.application.accreditation

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationStatus
import com.dynokore.gst.controlPlane.domain.accreditation.SandboxAcknowledgement
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** The permission keys of the accreditation operations (each a row of the catalogue's `OperationRoles`). */
object AccreditationPermission {
    /** recordAccreditation. */
    const val RECORD = "accreditation.record"

    /** getAccreditation (INV-Q-15430 A): its own key, not an alias of [RECORD]. */
    const val READ = "accreditation.view"

    /** listAccreditationTasks (INV-Q-15430 A). */
    const val TASK_LIST = "accreditation-task.list"

    /** getAccreditationTask (INV-Q-15430 A). */
    const val TASK_VIEW = "accreditation-task.view"

    /** concludeAccreditationTask (INV-Q-10127 A): decided per request, never by a role name. */
    const val CONCLUDE = "accreditation.conclude"
}

/** The append-only accreditation records: the service never updates or deletes a row. */
interface AccreditationRecords {
    /** The record with the latest `recorded_at`, or null when none is recorded. */
    fun latest(): AccreditationRecord?

    fun find(
        imdaReference: String,
        currentUntil: LocalDate,
    ): AccreditationRecord?

    fun save(record: AccreditationRecord)
}

data class RecordAccreditationCommand(
    val actor: ActorId,
    val grantedOn: LocalDate,
    val currentUntil: LocalDate,
    val imdaReference: String,
    val coveredSubmissionTypes: List<SubmissionType>,
    val sandboxAcknowledgements: List<SandboxAcknowledgement>,
    val request: RequestContext,
) {
    init {
        require(!currentUntil.isBefore(grantedOn)) { "currentUntil is before grantedOn" }
        require(sandboxAcknowledgements.map { it.submissionType }.toSet().size == sandboxAcknowledgements.size) {
            "at most one acknowledgement per submission type"
        }
    }
}

/**
 * recordAccreditation (INV-REQ-1363, INV-REQ-1364): a holder of [AccreditationPermission.RECORD] appends a record whose
 * `recordedBy` is the caller's user ID. A repeat of (`imdaReference`, `currentUntil`) returns the existing record and
 * stores nothing. A covered type without an acknowledgement is accepted.
 */
class RecordAccreditation(
    private val records: AccreditationRecords,
    private val permissions: OperatorPermissions,
    private val clock: Clock,
) : DeclaredOperation<RecordAccreditationCommand, AccreditationRecord?, AccreditationRecord> {
    override val operationId = "recordAccreditation"
    override val reversibility = Reversibility.COMPENSABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: RecordAccreditationCommand,
    ) = permissions.holds(actor, AccreditationPermission.RECORD) && actor == command.actor.value

    override fun repeatKey(command: RecordAccreditationCommand) = "${command.imdaReference}|${command.currentUntil}"

    override fun load(command: RecordAccreditationCommand) = records.find(command.imdaReference, command.currentUntil)

    override fun priorResult(
        command: RecordAccreditationCommand,
        state: AccreditationRecord?,
    ) = state

    override fun unmetPrecondition(
        command: RecordAccreditationCommand,
        state: AccreditationRecord?,
    ): String? = null

    override fun perform(
        command: RecordAccreditationCommand,
        state: AccreditationRecord?,
    ): AccreditationRecord =
        AccreditationRecord(
            command.grantedOn,
            command.currentUntil,
            command.imdaReference,
            command.coveredSubmissionTypes,
            command.sandboxAcknowledgements,
            clock.instant(),
            command.actor,
        ).also(records::save)

    override fun audit(
        command: RecordAccreditationCommand,
        state: AccreditationRecord?,
        result: AccreditationRecord,
    ) = OperationAudit(
        "platform",
        null,
        null,
        EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = repeatKey(command),
            aggregateVersion = null,
            correlation = CorrelationId(command.request.correlationId),
            causation = CausationId(command.request.causationId),
        ),
    )
}

/**
 * getAccreditation: the latest record and its currency on the UTC date of the read. It never refuses a go-live and
 * never blocks a release: a lapse is reported, not enforced (INV-AC-2338).
 */
class GetAccreditation(
    private val records: AccreditationRecords,
    private val permissions: OperatorPermissions,
    private val clock: Clock,
) {
    /** Null when [actor] does not hold [AccreditationPermission.READ] (the caller answers 403). */
    fun get(actor: String): AccreditationStatus? =
        if (permissions.holds(actor, AccreditationPermission.READ)) {
            AccreditationStatus.of(records.latest(), LocalDate.now(clock.withZone(ZoneOffset.UTC)))
        } else {
            null
        }
}
