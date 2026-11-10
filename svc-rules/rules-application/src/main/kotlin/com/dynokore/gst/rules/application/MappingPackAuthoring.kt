package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.coding.CodeFinding
import com.dynokore.gst.rules.domain.coding.MappingCodeChecks
import com.dynokore.gst.rules.domain.coding.MappingEntry
import com.dynokore.gst.rules.domain.coding.SchemeTagAuthoring
import com.dynokore.gst.rules.domain.coding.SchemeTagCheck
import com.dynokore.gst.rules.domain.pack.MappingAcceptance
import com.dynokore.gst.rules.domain.pack.MappingAcceptanceRecord
import com.dynokore.gst.rules.domain.pack.MappingCommand
import com.dynokore.gst.rules.domain.pack.MappingDraftEntry
import com.dynokore.gst.rules.domain.pack.MappingPackLifecycle
import com.dynokore.gst.rules.domain.pack.MappingSignOff
import com.dynokore.gst.rules.domain.pack.PackEntry
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SourceClass
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** Why a mapping pack command was refused. */
enum class RefusalReason {
    INPUT_INVALID,
    LIFECYCLE,
    PRIMARY_ENTRY,
    NO_DRY_RUN_DELTA,
    CODE_CHECK,
    SIGN_OFF_BLOCKED,
    RATE_SOURCE_BLOCKED,
    RATE_SOURCE_UNAVAILABLE,
    DECISION_UNAVAILABLE,
    HOLDER_COPY_UNAVAILABLE,
    UNKNOWN_ENTRY,
    SCHEME_TAG_SIDE,
}

sealed interface MappingPackResult {
    data class Done(
        val status: PackStatus,
    ) : MappingPackResult

    data object NotFound : MappingPackResult

    /** Forbidden (SRT-004); the detail names no person. */
    data class Forbidden(
        val code: ErrorCode,
        val detail: String,
    ) : MappingPackResult

    data class Refused(
        val reason: RefusalReason,
        val code: ErrorCode? = null,
        val names: List<String> = emptyList(),
        val findings: List<CodeFinding> = emptyList(),
        val status: PackStatus? = null,
    ) : MappingPackResult
}

data class DraftCommand(
    val actor: String,
    val entityRef: String,
    val packId: String,
    val tenant: TenantId,
    val label: String,
)

data class MapTaxCodeCommand(
    val actor: String,
    val entityRef: String,
    val packId: String,
    val internalTaxCode: String,
    val irasCode: String,
    val sourceClass: SourceClass,
    val schemeTag: String? = null,
)

data class AcceptEntryCommand(
    val actor: String,
    val entityRef: String,
    val packId: String,
    val entryId: String,
    val rationale: String,
    val expiresOn: LocalDate,
)

data class PackActionCommand(
    val actor: String,
    val entityRef: String,
    val packId: String,
)

data class ActivateCommand(
    val actor: String,
    val entityRef: String,
    val packId: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val minEngineVersion: String,
)

/** The collaborators of [MappingPackAuthoring]: the store and the ports it asks (INV-Q-14646 to INV-Q-14651). */
data class MappingPackCollaborators(
    val store: MappingPackStore,
    val authority: PackActionAuthority,
    val deltas: DryRunDeltaPort,
    val decisions: PackApprovalDecisionPort,
    val catalogues: CodeCataloguePort,
    val rateSources: RateSourcePort,
    val holderCopies: HolderCopyPort,
    val installationFacts: InstallationApproverFactPort,
    val acknowledgements: MappingAcknowledgementPort,
    /** True in the private deployment form; the SaaS form never asks the installation fact port. */
    val privateForm: Boolean,
    val scenarios: ScenarioDataPort,
)

/** Thrown inside a command to stop it with a typed result; caught at the command boundary, never escapes. */
private class Stop(
    val result: MappingPackResult,
) : RuntimeException(null, null, false, false)

private fun stop(result: MappingPackResult): Nothing = throw Stop(result)

private fun stopRefused(
    reason: RefusalReason,
    code: ErrorCode? = null,
    names: List<String> = emptyList(),
): Nothing = stop(MappingPackResult.Refused(reason, code, names))

private fun guarded(block: () -> MappingPackResult): MappingPackResult =
    try {
        block()
    } catch (stopped: Stop) {
        stopped.result
    }

/**
 * The mapping pack authoring use cases (INV-REQ-380, INV-REQ-419, INV-REQ-683, INV-REQ-728, INV-REQ-504 decision part).
 * Every command checks a scoped permission key through the authority port with the command's `entityRef` (INV-Q-10027,
 * INV-Q-14652 A), never a role name. A refusal stores nothing: every check runs before the first write.
 */
class MappingPackAuthoring(
    private val ports: MappingPackCollaborators,
    private val clock: Clock,
) {
    private val store = ports.store

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun needs(
        actor: String,
        entityRef: String,
        permission: String,
    ) {
        val valid = entityRef.isNotBlank() && entityRef.length <= MAX_ENTITY_REF
        if (!valid || !ports.authority.holds(actor, entityRef, permission)) {
            stop(MappingPackResult.Forbidden(ErrorCode.SRT_004, "the action is not permitted"))
        }
    }

    /** The pack, only when it belongs to the entity the permission was checked against (OWASP ASVS V4). */
    private fun packOf(
        packId: String,
        entityRef: String,
    ): MappingPackDraft {
        val pack = store.find(packId) ?: stop(MappingPackResult.NotFound)
        if (pack.entityRef != entityRef) {
            stop(MappingPackResult.Forbidden(ErrorCode.SRT_004, "the action is not permitted"))
        }
        return pack
    }

    fun draft(command: DraftCommand): MappingPackResult =
        guarded {
            needs(command.actor, command.entityRef, MappingPackPermissions.AUTHOR)
            val labelOk = command.label.isNotBlank() && command.label.length <= MAX_LABEL
            if (!labelOk || command.packId.isBlank() || store.find(command.packId) != null) {
                stopRefused(RefusalReason.INPUT_INVALID)
            }
            val author = command.actor
            store.draft(
                MappingPackDraft(
                    command.packId,
                    command.tenant,
                    command.entityRef,
                    command.label,
                    author,
                    PackStatus.DRAFT,
                ),
            )
            MappingPackResult.Done(PackStatus.DRAFT)
        }

    fun mapTaxCode(command: MapTaxCodeCommand): MappingPackResult =
        guarded {
            needs(command.actor, command.entityRef, MappingPackPermissions.AUTHOR)
            val pack = packOf(command.packId, command.entityRef)
            if (!MappingPackLifecycle.entriesMayChange(pack.status)) {
                stop(MappingPackResult.Refused(RefusalReason.LIFECYCLE, status = pack.status))
            }
            if (command.internalTaxCode.isBlank() || command.internalTaxCode.length > MAX_ENTRY_ID) {
                stopRefused(RefusalReason.INPUT_INVALID)
            }
            val checked = MappingEntry(command.internalTaxCode, command.irasCode)
            stopOnFindings(MappingCodeChecks.atAuthoring(listOf(checked)))
            val tag = command.schemeTag
            if (tag != null) {
                SchemeTagGate.check(ports, pack, MappingEntry(command.internalTaxCode, command.irasCode, tag), today())
            }
            store.replaceEntry(
                command.packId,
                MappingDraftEntry(command.internalTaxCode, command.irasCode, command.sourceClass, tag),
            )
            MappingPackResult.Done(pack.status)
        }

    fun acceptEntry(command: AcceptEntryCommand): MappingPackResult =
        guarded {
            needs(command.actor, command.entityRef, MappingPackPermissions.APPROVE)
            val pack = packOf(command.packId, command.entityRef)
            if (!MappingPackLifecycle.acceptanceMayBeRecorded(pack.status)) {
                stop(MappingPackResult.Refused(RefusalReason.LIFECYCLE, status = pack.status))
            }
            val entry =
                entriesOf(store, command.packId).firstOrNull { it.entryId == command.entryId }
                    ?: stopRefused(RefusalReason.UNKNOWN_ENTRY, names = listOf(command.entryId))
            if (entry.sourceClass == SourceClass.PRIMARY) {
                stopRefused(RefusalReason.PRIMARY_ENTRY, names = listOf(entry.entryId))
            }
            if (!MappingAcceptance.rationaleValid(command.rationale) || !command.expiresOn.isAfter(today())) {
                stopRefused(RefusalReason.INPUT_INVALID)
            }
            val record =
                MappingAcceptanceRecord(
                    entry.entryId,
                    command.rationale,
                    command.expiresOn,
                    entry.irasCode,
                    entry.sourceClass,
                    command.actor,
                )
            store.recordAcceptance(command.packId, record)
            MappingPackResult.Done(pack.status)
        }

    fun submit(command: PackActionCommand): MappingPackResult =
        guarded {
            needs(command.actor, command.entityRef, MappingPackPermissions.AUTHOR)
            val pack = packOf(command.packId, command.entityRef)
            lifecycle(pack, MappingCommand.SUBMIT)
            ports.deltas.qualifying(PACK_TYPE, pack.label) ?: stopRefused(RefusalReason.NO_DRY_RUN_DELTA)
            store.setStatus(command.packId, PackStatus.SUBMITTED)
            MappingPackResult.Done(PackStatus.SUBMITTED)
        }

    /** The sign-off order (INV-REQ-380): each step refuses before the later ones run and before anything is stored. */
    fun approve(command: PackActionCommand): MappingPackResult =
        guarded {
            needs(command.actor, command.entityRef, MappingPackPermissions.APPROVE)
            val pack = packOf(command.packId, command.entityRef)
            lifecycle(pack, MappingCommand.APPROVE)
            val deltaId =
                ports.deltas.qualifying(PACK_TYPE, pack.label) ?: stopRefused(RefusalReason.NO_DRY_RUN_DELTA)
            val asOf = today()
            checkSignOff(pack, command.packId, asOf)
            val prior = store.approvals(command.packId).map { it.approverUserId }
            val request =
                PackApprovalRequest(
                    command.entityRef,
                    command.packId,
                    pack.label,
                    pack.authorUserId,
                    command.actor,
                    prior,
                )
            val decision = ports.decisions.decide(request)
            record(command, decision, deltaId)
        }

    private fun checkSignOff(
        pack: MappingPackDraft,
        packId: String,
        asOf: LocalDate,
    ) {
        val entries = entriesOf(store, packId)
        stopOnFindings(codeFindings(entries, ports.catalogues.catalogueAt(pack.tenant, TransactionDate(asOf))))
        val accepted = MappingAcceptance.acceptedEntryIds(entries, store.acceptances(packId), asOf)
        val outcome = MappingSignOff.attempt(entries.map { PackEntry(it.entryId, null, it.sourceClass) }, accepted)
        if (outcome is MappingSignOff.Outcome.Blocked) {
            stopRefused(RefusalReason.SIGN_OFF_BLOCKED, outcome.code, outcome.entryIds)
        }
        val blocking =
            ports.rateSources.blocking(pack.tenant, asOf) ?: stopRefused(RefusalReason.RATE_SOURCE_UNAVAILABLE)
        if (blocking.isNotEmpty()) stopRefused(RefusalReason.RATE_SOURCE_BLOCKED, ErrorCode.RUL_007, blocking)
    }

    private fun record(
        command: PackActionCommand,
        decision: PackApprovalDecision,
        deltaId: String,
    ): MappingPackResult =
        when (decision) {
            is PackApprovalDecision.Deny -> {
                MappingPackResult.Forbidden(ErrorCode.SRT_004, "the approval is not permitted")
            }

            PackApprovalDecision.Unavailable -> {
                MappingPackResult.Refused(RefusalReason.DECISION_UNAVAILABLE)
            }

            is PackApprovalDecision.Allow -> {
                val resulting =
                    if (CLIENT_APPROVAL in decision.outstanding) PackStatus.SUBMITTED else PackStatus.APPROVED
                val approval =
                    MappingApproval(
                        command.actor,
                        clock.instant(),
                        deltaId,
                        decision.soleOperator,
                        decision.soleOperatorAckRef,
                        decision.option,
                        decision.outstanding,
                    )
                store.recordApproval(command.packId, approval, resulting)
                MappingPackResult.Done(resulting)
            }
        }

    fun activate(command: ActivateCommand): MappingPackResult =
        guarded {
            needs(command.actor, command.entityRef, MappingPackPermissions.ACTIVATE)
            val pack = packOf(command.packId, command.entityRef)
            lifecycle(pack, MappingCommand.ACTIVATE)
            val to = command.effectiveTo
            if (command.minEngineVersion.isBlank() || (to != null && !to.isAfter(command.effectiveFrom))) {
                stopRefused(RefusalReason.INPUT_INVALID)
            }
            holdForAcknowledgement(ports, pack)
            val entries = entriesOf(store, command.packId)
            val catalogue = ports.catalogues.catalogueAt(pack.tenant, TransactionDate(command.effectiveFrom))
            stopOnFindings(codeFindings(entries, catalogue))
            val noDistinctApprover = noDistinctApprover(ports)
            val request =
                HolderCopyRequest(
                    pack.tenant,
                    pack.packId,
                    PACK_TYPE,
                    pack.label,
                    command.effectiveFrom,
                    to,
                    command.minEngineVersion,
                    contentsOf(entries),
                )
            if (ports.holderCopies.write(request) != HolderCopyResult.Written) {
                stopRefused(RefusalReason.HOLDER_COPY_UNAVAILABLE)
            }
            val activation =
                MappingActivation(command.effectiveFrom, to, command.actor, clock.instant(), noDistinctApprover)
            store.recordActivation(command.packId, activation)
            MappingPackResult.Done(PackStatus.ARMED)
        }

    companion object {
        const val PACK_TYPE = "MAPPING"
        const val CLIENT_APPROVAL = "CLIENT_APPROVAL"
        const val CLIENT_ACKNOWLEDGEMENT = "CLIENT_ACKNOWLEDGEMENT"
        private const val MAX_LABEL = 200
        private const val MAX_ENTITY_REF = 64
        private const val MAX_ENTRY_ID = 40
    }
}

/** The client's acknowledgement is asked only when the latest approval lists it as outstanding. */
private fun holdForAcknowledgement(
    ports: MappingPackCollaborators,
    pack: MappingPackDraft,
) {
    val latest = ports.store.approvals(pack.packId).lastOrNull() ?: return
    if (MappingPackAuthoring.CLIENT_ACKNOWLEDGEMENT !in latest.outstanding) return
    val answer = ports.acknowledgements.read(pack.label, pack.entityRef)
    if (answer == MappingAcknowledgement.NotRecorded) {
        stop(MappingPackResult.Forbidden(ErrorCode.SRT_004, "the client's acknowledgement is outstanding"))
    }
    if (answer == MappingAcknowledgement.Unavailable) {
        stopRefused(RefusalReason.DECISION_UNAVAILABLE, names = listOf("mapping-acknowledgement"))
    }
}

/** True for a recorded false or nothing recorded; false for a recorded true and in the SaaS form. */
private fun noDistinctApprover(ports: MappingPackCollaborators): Boolean {
    if (!ports.privateForm) return false
    return when (val fact = ports.installationFacts.read()) {
        is InstallationApproverFact.Recorded -> {
            !fact.distinctTaxApprover
        }

        InstallationApproverFact.NotRecorded -> {
            true
        }

        InstallationApproverFact.Unavailable -> {
            stopRefused(RefusalReason.DECISION_UNAVAILABLE, names = listOf("installation-approver-fact"))
        }
    }
}

/** The scheme tag against the sides the scenario pack declares (INV-REQ-2444 behaviour 4, INV-AC-2502). */
private object SchemeTagGate {
    /** Reads the packs in force on [today]; a port that cannot answer refuses: fail closed. */
    fun check(
        ports: MappingPackCollaborators,
        pack: MappingPackDraft,
        entry: MappingEntry,
        today: LocalDate,
    ) {
        val date = TransactionDate(today)
        val catalogue = ports.catalogues.catalogueAt(pack.tenant, date)
        val scenario = ports.scenarios.scenarioAt(pack.tenant, date)
        val refused =
            catalogue == null ||
                scenario == null ||
                SchemeTagAuthoring.check(entry, catalogue, scenario) is SchemeTagCheck.Refused
        if (refused) stopRefused(RefusalReason.SCHEME_TAG_SIDE, names = listOf(entry.schemeTag.orEmpty()))
    }
}

private fun stopOnFindings(findings: List<CodeFinding>) {
    val first = findings.firstOrNull() ?: return
    stop(
        MappingPackResult.Refused(
            RefusalReason.CODE_CHECK,
            first.code,
            findings.map { it.internalTaxCode },
            findings,
        ),
    )
}

/** One finding per entry in entry order; a missing catalogue is RUL-009 for every code. */
private fun codeFindings(
    entries: List<MappingDraftEntry>,
    catalogue: CodeCatalogue?,
): List<CodeFinding> {
    val mapped = entries.map { MappingEntry(it.entryId, it.irasCode) }
    return if (catalogue == null) {
        mapped.map { CodeFinding(it.internalTaxCode, it.irasCode, ErrorCode.RUL_009) }
    } else {
        MappingCodeChecks.atSignOff(mapped, catalogue)
    }
}

/** The document of `mapping-pack-contents.schema.json`: entries in internal-tax-code order. */
private fun contentsOf(entries: List<MappingDraftEntry>): ByteArray {
    val rows =
        entries.sortedBy { it.entryId }.joinToString(",") {
            "{\"entryId\":${json(it.entryId)},\"internalTaxCode\":${json(it.entryId)}," +
                "\"irasCode\":${json(it.irasCode)},\"sourceClass\":${json(it.sourceClass.name)}}"
        }
    return "{\"entries\":[$rows]}".toByteArray(Charsets.UTF_8)
}

private fun json(s: String): String =
    buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u").append(Integer.toHexString(c.code).padStart(UNICODE_ESCAPE_WIDTH, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

private const val PAGE = 200
private const val UNICODE_ESCAPE_WIDTH = 4

private fun lifecycle(
    pack: MappingPackDraft,
    command: MappingCommand,
) {
    val t = MappingPackLifecycle.next(pack.status, command)
    if (t is MappingPackLifecycle.Transition.Refused) {
        stop(MappingPackResult.Refused(RefusalReason.LIFECYCLE, status = t.status))
    }
}

private fun entriesOf(
    store: MappingPackStore,
    packId: String,
): List<MappingDraftEntry> {
    val all = mutableListOf<MappingDraftEntry>()
    var cursor: String? = null
    do {
        val page = store.listEntries(packId, cursor, PAGE)
        all += page
        cursor = page.lastOrNull()?.entryId
    } while (page.size == PAGE)
    return all.sortedBy { it.entryId }
}
