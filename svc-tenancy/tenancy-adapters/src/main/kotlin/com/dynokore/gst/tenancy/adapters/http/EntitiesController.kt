package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.adapters.lifecycle.AuditLogLifecycleAudit
import com.dynokore.gst.tenancy.application.certification.CertifyOutcome
import com.dynokore.gst.tenancy.application.certification.CertifySandbox
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.ClosedChange
import com.dynokore.gst.tenancy.application.election.ElectionCommands
import com.dynokore.gst.tenancy.application.election.ElectionOutcome
import com.dynokore.gst.tenancy.application.election.ElectionRecord
import com.dynokore.gst.tenancy.application.election.ElectionStatusQueries
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseOutcome
import com.dynokore.gst.tenancy.application.election.PendingChange
import com.dynokore.gst.tenancy.application.lifecycle.DeregisterEntity
import com.dynokore.gst.tenancy.application.lifecycle.GoLive
import com.dynokore.gst.tenancy.application.lifecycle.LifecycleOutcome
import com.dynokore.gst.tenancy.application.lifecycle.SuspendEntity
import com.dynokore.gst.tenancy.application.migration.GetMigrationCutover
import com.dynokore.gst.tenancy.application.migration.MigrationCutover
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverOutcome
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverPermissions
import com.dynokore.gst.tenancy.application.migration.NewMigrationCutover
import com.dynokore.gst.tenancy.application.migration.RecordMigrationCutover
import com.dynokore.gst.tenancy.application.period.FilingFrequencyCommands
import com.dynokore.gst.tenancy.application.rendition.RecordRenditionOutcome
import com.dynokore.gst.tenancy.application.rendition.RecordRenditionResponsibility
import com.dynokore.gst.tenancy.application.status.AssessOutcome
import com.dynokore.gst.tenancy.application.status.AssessPhaseCommands
import com.dynokore.gst.tenancy.application.status.EntityStatusCommands
import com.dynokore.gst.tenancy.application.status.StatusOutcome
import com.dynokore.gst.tenancy.application.structure.GroupKind
import com.dynokore.gst.tenancy.application.structure.NewBusinessUnit
import com.dynokore.gst.tenancy.application.structure.NewEntity
import com.dynokore.gst.tenancy.application.structure.NewGroup
import com.dynokore.gst.tenancy.application.structure.StructureCommands
import com.dynokore.gst.tenancy.application.structure.StructureOutcome
import com.dynokore.gst.tenancy.application.structure.StructureQueries
import com.dynokore.gst.tenancy.contract.api.EntitiesApi
import com.dynokore.gst.tenancy.contract.model.AssessPhaseRequest
import com.dynokore.gst.tenancy.contract.model.BusinessUnitResponse
import com.dynokore.gst.tenancy.contract.model.CertificationResponse
import com.dynokore.gst.tenancy.contract.model.CertifySandboxRequest
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.DeregisterEntityRequest
import com.dynokore.gst.tenancy.contract.model.ElectionChangeApproved
import com.dynokore.gst.tenancy.contract.model.ElectionChangeClosed
import com.dynokore.gst.tenancy.contract.model.ElectionRequest
import com.dynokore.gst.tenancy.contract.model.ElectionResponse
import com.dynokore.gst.tenancy.contract.model.ElectionStatusResponse
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.EntityStatusResponse
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyChangeRequest
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponse
import com.dynokore.gst.tenancy.contract.model.GoLiveRequest
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.IssuanceModeResponse
import com.dynokore.gst.tenancy.contract.model.MigrationCutoverRecord
import com.dynokore.gst.tenancy.contract.model.MoveBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.PendingElectionChange
import com.dynokore.gst.tenancy.contract.model.PendingElectionChangeList
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentResponse
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionResponse
import com.dynokore.gst.tenancy.contract.model.RecordMigrationCutoverRequest
import com.dynokore.gst.tenancy.contract.model.RegisterBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.RenditionResponsibilityRequest
import com.dynokore.gst.tenancy.contract.model.RenditionResponsibilityResponse
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.contract.model.StructureNode
import com.dynokore.gst.tenancy.contract.model.SuspendEntityRequest
import com.dynokore.gst.tenancy.contract.model.UpdateBusinessUnitRequest
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.gst.tenancy.domain.CodeRefusal
import com.dynokore.gst.tenancy.domain.ElectionKeys
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.IssuanceMode
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-08. The structure operations of the tenancy contract that this service implements in INV-T-4925: createGroup,
 * createEntity, registerBusinessUnit, moveBusinessUnit, updateBusinessUnit and getEntityStructure (scope tenant). The
 * request's `tenantRef` must name the bound tenant, else the tenant is absent from the caller's scope (404).
 */
@RestController
// one function per contract operation; one collaborator per use case
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class EntitiesController(
    private val commands: StructureCommands,
    private val statusCommands: EntityStatusCommands,
    private val phaseCommands: AssessPhaseCommands,
    private val frequencyCommands: FilingFrequencyCommands,
    private val queries: StructureQueries,
    private val tenants: TenantContext,
    private val electionCommands: ElectionCommands,
    private val electionQueries: ElectionStatusQueries,
    private val certifySandbox: CertifySandbox,
    private val recordRendition: RecordRenditionResponsibility,
    private val renditionAuthority: ActionAuthority,
    private val recordCutover: RecordMigrationCutover,
    private val getCutover: GetMigrationCutover,
    private val caller: CallerSource,
    private val goLiveUseCase: GoLive,
    private val suspendUseCase: SuspendEntity,
    private val deregisterUseCase: DeregisterEntity,
    private val tx: TransactionRunner,
    private val lifecycleAudit: AuditLogLifecycleAudit,
) : EntitiesApi {
    override fun createGroup(
        idempotencyKey: UUID,
        createGroupRequest: CreateGroupRequest,
    ): ResponseEntity<GroupResponse> {
        val tenant = boundTenant(createGroupRequest.tenantRef)
        val request =
            NewGroup(
                createGroupRequest.name,
                GroupKind.valueOf(createGroupRequest.groupKind.name),
                createGroupRequest.gstn,
            )
        val group = respond(commands.createGroup(tenant, createGroupRequest.tenantRef, request))
        return ResponseEntity.status(HttpStatus.CREATED).body(groupResponse(group, createGroupRequest.tenantRef))
    }

    override fun createEntity(
        idempotencyKey: UUID,
        createEntityRequest: CreateEntityRequest,
    ): ResponseEntity<EntityResponse> {
        val tenant = boundTenant(createEntityRequest.tenantRef)
        val request =
            NewEntity(
                createEntityRequest.groupRef,
                createEntityRequest.legalName,
                createEntityRequest.constitution.value,
                createEntityRequest.uen,
                createEntityRequest.gstn,
                createEntityRequest.code,
            )
        val entity = respond(commands.createEntity(tenant, request))
        return ResponseEntity.status(HttpStatus.CREATED).body(entityResponse(entity))
    }

    override fun registerBusinessUnit(
        idempotencyKey: UUID,
        registerBusinessUnitRequest: RegisterBusinessUnitRequest,
    ): ResponseEntity<BusinessUnitResponse> {
        val tenant = boundTenant(registerBusinessUnitRequest.tenantRef)
        val request =
            NewBusinessUnit(
                registerBusinessUnitRequest.entityRef,
                registerBusinessUnitRequest.name,
                registerBusinessUnitRequest.code,
            )
        val unit = respond(commands.registerBusinessUnit(tenant, request))
        return ResponseEntity.status(HttpStatus.CREATED).body(unitResponse(unit, registerBusinessUnitRequest.tenantRef))
    }

    override fun moveBusinessUnit(
        businessUnitRef: String,
        idempotencyKey: UUID,
        moveBusinessUnitRequest: MoveBusinessUnitRequest,
    ): ResponseEntity<BusinessUnitResponse> {
        val unit =
            respond(commands.moveBusinessUnit(tenants.current(), businessUnitRef, moveBusinessUnitRequest.toEntityRef))
        return ResponseEntity.ok(unitResponse(unit, tenantRef()))
    }

    override fun updateBusinessUnit(
        businessUnitRef: String,
        idempotencyKey: UUID,
        updateBusinessUnitRequest: UpdateBusinessUnitRequest,
    ): ResponseEntity<BusinessUnitResponse> {
        val unit =
            respond(
                commands.updateBusinessUnit(
                    tenants.current(),
                    businessUnitRef,
                    updateBusinessUnitRequest.name,
                    updateBusinessUnitRequest.code,
                ),
            )
        return ResponseEntity.ok(unitResponse(unit, tenantRef()))
    }

    override fun getEntityStructure(entityRef: String): ResponseEntity<EntityResponse> {
        val view = queries.node(entityRef) ?: notFound()
        return ResponseEntity.ok(viewResponse(view))
    }

    override fun setEntityStatus(
        entityRef: String,
        idempotencyKey: UUID,
        setEntityStatusRequest: SetEntityStatusRequest,
    ): ResponseEntity<EntityStatusResponse> {
        val outcome =
            statusCommands.setEntityStatus(
                tenants.current(),
                entityRef,
                statusChangeOf(setEntityStatusRequest),
            )
        val entry =
            when (outcome) {
                is StatusOutcome.Done -> {
                    outcome.entry
                }

                StatusOutcome.NotFound -> {
                    notFound()
                }

                is StatusOutcome.Invalid -> {
                    invalid(outcome.reason)
                }

                is StatusOutcome.Conflict -> {
                    statusConflict()
                }

                is StatusOutcome.Outstanding -> {
                    return ResponseEntity.ok(outstandingStatusResponse(entityRef, setEntityStatusRequest))
                }
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(statusResponse(entityRef, entry))
    }

    override fun assessPhase(
        entityRef: String,
        idempotencyKey: UUID,
        assessPhaseRequest: AssessPhaseRequest,
    ): ResponseEntity<PhaseAssessmentResponse> =
        when (val outcome = phaseCommands.assess(entityRef, phaseRequestOf(assessPhaseRequest))) {
            is AssessOutcome.Done -> ResponseEntity.ok(phaseResponse(entityRef, outcome.stored))
            AssessOutcome.NotFound -> notFound()
            is AssessOutcome.Invalid -> invalid(outcome.reason)
        }

    override fun changeFilingFrequency(
        entityRef: String,
        idempotencyKey: UUID,
        filingFrequencyChangeRequest: FilingFrequencyChangeRequest,
    ): ResponseEntity<FilingFrequencyResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            frequencyOf(
                entityRef,
                frequencyCommands.changeFilingFrequency(entityRef, frequencyRequestOf(filingFrequencyChangeRequest)),
            ),
        )

    override fun setIssuanceMode(
        entityRef: String,
        idempotencyKey: UUID,
        setIssuanceModeRequest: SetIssuanceModeRequest,
    ): ResponseEntity<IssuanceModeResponse> {
        val mode = IssuanceMode.valueOf(setIssuanceModeRequest.mode.name)
        val outcome =
            electionCommands.setIssuanceMode(
                tenants.current(),
                entityRef,
                mode,
                setIssuanceModeRequest.effectiveFrom,
                setIssuanceModeRequest.secondConfirmationBy,
            )
        val done = elected(outcome)
        return ResponseEntity
            .status(created(outcome))
            .body(IssuanceModeResponse(entityRef, IssuanceModeResponse.Mode.valueOf(done.value), done.effectiveFrom))
    }

    override fun recordElection(
        entityRef: String,
        idempotencyKey: UUID,
        electionRequest: ElectionRequest,
    ): ResponseEntity<ElectionResponse> {
        val exempt = electionRequest.election == ElectionKeys.EXEMPT_PURCHASE_CODES
        if (exempt) return recordExemptPurchase(entityRef, electionRequest)
        if (electionRequest.taxCodes != null || electionRequest.basis != null) {
            invalid("taxCodes and basis are accepted only for ${ElectionKeys.EXEMPT_PURCHASE_CODES}")
        }
        val outcome =
            electionCommands.recordElection(entityRef, electionRequest.election, electionRequest.effectiveFrom)
        val done = elected(outcome)
        return ResponseEntity
            .status(created(outcome))
            .body(ElectionResponse(entityRef, done.value, done.effectiveFrom))
    }

    /** The exempt-purchase election (INV-REQ-369): both properties are required; 201 for a record, 200 for a repeat. */
    private fun recordExemptPurchase(
        entityRef: String,
        request: ElectionRequest,
    ): ResponseEntity<ElectionResponse> {
        val taxCodes = request.taxCodes ?: invalid("taxCodes is required for ${ElectionKeys.EXEMPT_PURCHASE_CODES}")
        val basis = request.basis ?: invalid("basis is required for ${ElectionKeys.EXEMPT_PURCHASE_CODES}")
        return when (
            val outcome =
                electionCommands.recordExemptPurchase(entityRef, taxCodes.toList(), basis, request.effectiveFrom)
        ) {
            is ExemptPurchaseOutcome.Done -> {
                val row = outcome.row
                ResponseEntity
                    .status(if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED)
                    .body(
                        ElectionResponse(
                            entityRef,
                            ElectionKeys.EXEMPT_PURCHASE_CODES,
                            row.effectiveFrom,
                            row.taxCodes.toSet(),
                            row.basis,
                        ),
                    )
            }

            ExemptPurchaseOutcome.NotFound -> {
                notFound()
            }

            is ExemptPurchaseOutcome.Invalid -> {
                invalid(outcome.reason)
            }

            is ExemptPurchaseOutcome.Forbidden -> {
                forbidden(outcome.reason)
            }
        }
    }

    override fun electRateSource(
        entityRef: String,
        idempotencyKey: UUID,
        rateSourceElectionRequest: RateSourceElectionRequest,
    ): ResponseEntity<RateSourceElectionResponse> {
        val outcome =
            electionCommands.electRateSource(
                entityRef,
                rateSourceElectionRequest.source,
                rateSourceElectionRequest.basis.value,
                rateSourceElectionRequest.effectiveFrom,
            )
        val done = elected(outcome)
        return ResponseEntity
            .status(created(outcome))
            .body(RateSourceElectionResponse(entityRef, done.value, done.effectiveFrom))
    }

    override fun approveElectionChange(
        entityRef: String,
        changeRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<ElectionChangeApproved> =
        when (val outcome = electionCommands.approveElectionChange(entityRef, changeRef)) {
            is ElectionOutcome.Approved -> {
                val approval = outcome.approval
                ResponseEntity.ok(
                    ElectionChangeApproved(
                        approval.change.changeRef,
                        approval.change.entityRef,
                        approval.change.effectiveFrom,
                        approval.change.requestedBy,
                        approval.approvedBy,
                        approval.approvedAt.atOffset(ZoneOffset.UTC),
                    ),
                )
            }

            else -> {
                refused(outcome)
            }
        }

    override fun withdrawElectionChange(
        entityRef: String,
        changeRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<ElectionChangeClosed> =
        when (val outcome = electionCommands.withdrawElectionChange(entityRef, changeRef)) {
            is ElectionOutcome.Withdrawn -> {
                ResponseEntity.ok(
                    closedChange(outcome.closure, ElectionChangeClosed.Outcome.WITHDRAWN),
                )
            }

            else -> {
                refused(outcome)
            }
        }

    override fun declineElectionChange(
        entityRef: String,
        changeRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<ElectionChangeClosed> =
        when (val outcome = electionCommands.declineElectionChange(entityRef, changeRef)) {
            is ElectionOutcome.Declined -> {
                ResponseEntity.ok(
                    closedChange(outcome.closure, ElectionChangeClosed.Outcome.DECLINED),
                )
            }

            else -> {
                refused(outcome)
            }
        }

    override fun listElectionChanges(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<PendingElectionChangeList> =
        when (val outcome = electionCommands.listElectionChanges(entityRef, limit, cursor)) {
            is ElectionOutcome.Listed -> {
                ResponseEntity.ok(
                    PendingElectionChangeList(
                        items = outcome.page.items.map { pendingChange(it) },
                        nextCursor = outcome.page.next,
                    ),
                )
            }

            else -> {
                refused(outcome)
            }
        }

    private fun closedChange(
        closure: ClosedChange,
        outcome: ElectionChangeClosed.Outcome,
    ): ElectionChangeClosed =
        ElectionChangeClosed(
            changeRef = closure.change.changeRef,
            entityRef = closure.change.entityRef,
            outcome = outcome,
            requestedBy = closure.change.requestedBy,
            closedBy = closure.closedBy,
            closedAt = closure.closedAt.atOffset(ZoneOffset.UTC),
        )

    override fun getElectionStatus(
        entityRef: String,
        on: LocalDate?,
    ): ResponseEntity<ElectionStatusResponse> {
        val report = electionQueries.status(entityRef, on ?: LocalDate.now(SINGAPORE)) ?: notFound()
        return ResponseEntity.ok(electionStatusResponse(report))
    }

    /** 201 for a written election, 200 for a replay that wrote nothing (RFC 9110 sec. 15.3.2, INV-Q-11868). */
    private fun created(outcome: ElectionOutcome): HttpStatus =
        if (outcome is ElectionOutcome.Done && outcome.repeated) HttpStatus.OK else HttpStatus.CREATED

    /** The stored election, or 202 (a change waits for approval) or the refusal. */
    private fun elected(outcome: ElectionOutcome): ElectionRecord =
        when (outcome) {
            is ElectionOutcome.Done -> outcome.election
            is ElectionOutcome.Pending -> throw PendingChangeAccepted(pendingChange(outcome.change))
            else -> refused(outcome)
        }

    /** The refusal an outcome that stored nothing stands for. */
    private fun refused(outcome: ElectionOutcome): Nothing =
        when (outcome) {
            ElectionOutcome.NotFound -> {
                notFound()
            }

            is ElectionOutcome.Invalid -> {
                invalid(outcome.reason)
            }

            is ElectionOutcome.Refused -> {
                constraintRefused(outcome.reason)
            }

            is ElectionOutcome.Forbidden -> {
                forbidden(outcome.reason)
            }

            // INV-T-13532 (S1) adds Withdrawn, Declined, Listed and Conflict; INV-T-15888 (S2) maps them to HTTP.
            is ElectionOutcome.Done,
            is ElectionOutcome.Pending,
            is ElectionOutcome.Approved,
            is ElectionOutcome.Withdrawn,
            is ElectionOutcome.Declined,
            is ElectionOutcome.Listed,
            is ElectionOutcome.Conflict,
            -> {
                error("outcome ${outcome::class.simpleName} is not a refusal")
            }
        }

    /** certifySandbox (INV-Q-16389 permission, INV-Q-16403 failedScenarios rule). */
    override fun certifySandbox(
        entityRef: String,
        idempotencyKey: UUID,
        certifySandboxRequest: CertifySandboxRequest,
    ): ResponseEntity<CertificationResponse> =
        when (
            val outcome =
                certifySandbox.handle(
                    caller.actor(),
                    entityRef,
                    CertificationOutcome.valueOf(certifySandboxRequest.outcome.name),
                    certifySandboxRequest.suiteVersion,
                    certifySandboxRequest.failedScenarios,
                )
        ) {
            is CertifyOutcome.Recorded -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    CertificationResponse(
                        outcome.record.entityRef,
                        CertificationResponse.Outcome.valueOf(outcome.record.outcome.name),
                        outcome.record.recordedAt.atOffset(ZoneOffset.UTC),
                        failedScenarios = outcome.record.failedScenarios,
                    ),
                )
            }

            CertifyOutcome.Forbidden -> {
                forbidden("sandbox-certification.record is required")
            }

            CertifyOutcome.NotFound -> {
                notFound()
            }

            is CertifyOutcome.Invalid -> {
                invalid(outcome.reason)
            }
        }

    /** recordRenditionResponsibility (INV-Q-16397: `attestedBy` must be the authenticated caller, else SRT-004). */
    override fun recordRenditionResponsibility(
        entityRef: String,
        idempotencyKey: UUID,
        renditionResponsibilityRequest: RenditionResponsibilityRequest,
    ): ResponseEntity<RenditionResponsibilityResponse> {
        val actor = caller.actor()
        // the permission key is decided per request by the authority replica before the use case runs; an
        // exception or an absent or DEGRADED replica counts as not held (fail closed)
        val holds =
            runCatching { renditionAuthority.holds(actor, entityRef, ElectionPermissions.MANAGE) }.getOrDefault(false)
        if (!holds) forbidden("${ElectionPermissions.MANAGE} is required")
        return when (
            val outcome =
                recordRendition.handle(
                    actor,
                    entityRef,
                    renditionResponsibilityRequest.documentClass,
                    renditionResponsibilityRequest.renderer.value,
                    renditionResponsibilityRequest.attestedBy,
                )
        ) {
            is RecordRenditionOutcome.Recorded -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    RenditionResponsibilityResponse(
                        outcome.attestation.entityRef,
                        outcome.attestation.documentClass,
                        RenditionResponsibilityResponse.Renderer.valueOf(outcome.attestation.renderer.name),
                    ),
                )
            }

            RecordRenditionOutcome.Forbidden -> {
                forbidden("attestedBy must be the authenticated caller")
            }

            RecordRenditionOutcome.NotFound -> {
                notFound()
            }

            is RecordRenditionOutcome.Invalid -> {
                invalid(outcome.reason)
            }
        }
    }

    /**
     * goLive (INV-REQ-987, INV-REQ-963, INV-REQ-875): the caller holds `entity-config.manage` (decided per request by
     * the authority replica, fail closed); the use case decides the second confirmation and the gate.
     */
    override fun goLive(
        entityRef: String,
        idempotencyKey: UUID,
        goLiveRequest: GoLiveRequest,
    ): ResponseEntity<EntityResponse> {
        val actor = lifecycleActor(entityRef)
        return lifecycleResult(goLiveUseCase.handle(actor, entityRef, goLiveRequest.secondConfirmationBy))
    }

    /** suspendEntity (INV-REQ-963 b2): stops future submission, retracts nothing. */
    override fun suspendEntity(
        entityRef: String,
        idempotencyKey: UUID,
        suspendEntityRequest: SuspendEntityRequest,
    ): ResponseEntity<EntityResponse> {
        val actor = lifecycleActor(entityRef)
        val outcome =
            tx.inTransaction {
                suspendUseCase.handle(entityRef).also {
                    if (it is LifecycleOutcome.Done) {
                        lifecycleAudit.suspended(
                            actor,
                            entityRef,
                            suspendEntityRequest.reason,
                        )
                    }
                }
            }
        return lifecycleResult(outcome)
    }

    /** deregisterEntity (INV-REQ-956 b1, INV-Q-16387): 409 TNC-008 for an unsubmitted document without an override. */
    override fun deregisterEntity(
        entityRef: String,
        idempotencyKey: UUID,
        deregisterEntityRequest: DeregisterEntityRequest,
    ): ResponseEntity<EntityResponse> {
        lifecycleActor(entityRef)
        return lifecycleResult(
            deregisterUseCase.handle(
                entityRef,
                deregisterEntityRequest.registrationEndDate,
                deregisterEntityRequest.unsubmittedOverride?.let {
                    UnsubmittedOverride(it.acknowledgedBy, it.statement)
                },
            ),
        )
    }

    /** The authenticated caller, once the replica says it holds the permission to manage the entity's lifecycle. */
    private fun lifecycleActor(entityRef: String): String {
        val actor = caller.actor()
        val holds =
            runCatching { renditionAuthority.holds(actor, entityRef, ElectionPermissions.MANAGE) }.getOrDefault(false)
        if (!holds) forbidden("${ElectionPermissions.MANAGE} is required")
        return actor
    }

    private fun lifecycleResult(outcome: LifecycleOutcome): ResponseEntity<EntityResponse> =
        when (outcome) {
            is LifecycleOutcome.Done -> {
                ResponseEntity.ok(viewResponse(queries.node(outcome.entityRef) ?: notFound()))
            }

            is LifecycleOutcome.Invalid -> {
                invalid(outcome.reason)
            }

            is LifecycleOutcome.Forbidden -> {
                forbidden(outcome.reason)
            }

            LifecycleOutcome.NotFound -> {
                notFound()
            }

            is LifecycleOutcome.Conflict -> {
                conflict(TenancyErrorCode.UNSUBMITTED_DOCUMENTS_REFUSED, outcome.reason)
            }

            is LifecycleOutcome.Refused -> {
                constraintRefused(outcome.detail)
            }
        }

    /**
     * recordMigrationCutover (INV-REQ-1109, INV-Q-6232 A): 201 first record, 200 exact repeat, 409 TNC-011 another
     * date; the permission key `migration-cutover.record` is decided per request inside the use case (fail closed).
     */
    override fun recordMigrationCutover(
        entityRef: String,
        idempotencyKey: UUID,
        recordMigrationCutoverRequest: RecordMigrationCutoverRequest,
    ): ResponseEntity<MigrationCutoverRecord> =
        when (
            val outcome =
                recordCutover.record(
                    caller.actor(),
                    entityRef,
                    NewMigrationCutover(
                        recordMigrationCutoverRequest.cutoverDate,
                        recordMigrationCutoverRequest.priorSolution,
                        recordMigrationCutoverRequest.approvedBy,
                        recordMigrationCutoverRequest.evidenceRef,
                    ),
                )
        ) {
            is MigrationCutoverOutcome.Created -> {
                ResponseEntity.status(HttpStatus.CREATED).body(cutoverResponse(outcome.cutover))
            }

            is MigrationCutoverOutcome.Existing -> {
                ResponseEntity.ok(cutoverResponse(outcome.cutover))
            }

            else -> {
                refuseCutover(outcome, MigrationCutoverPermissions.RECORD)
            }
        }

    /** getMigrationCutover (INV-REQ-1109): the stored record, 404 when the entity has none. */
    override fun getMigrationCutover(entityRef: String): ResponseEntity<MigrationCutoverRecord> =
        when (val outcome = getCutover.get(caller.actor(), entityRef)) {
            is MigrationCutoverOutcome.Existing -> ResponseEntity.ok(cutoverResponse(outcome.cutover))
            else -> refuseCutover(outcome, MigrationCutoverPermissions.VIEW)
        }

    private fun refuseCutover(
        outcome: MigrationCutoverOutcome,
        permission: String,
    ): Nothing =
        when (outcome) {
            MigrationCutoverOutcome.Denied -> {
                forbidden("$permission is required")
            }

            MigrationCutoverOutcome.NotFound -> {
                notFound()
            }

            is MigrationCutoverOutcome.Conflict -> {
                conflict(
                    TenancyErrorCode.MIGRATION_CUTOVER_CONFLICT,
                    "a migration cutover with another date is already recorded for the entity",
                )
            }

            is MigrationCutoverOutcome.Rejected -> {
                invalid(outcome.reason)
            }

            is MigrationCutoverOutcome.Created, is MigrationCutoverOutcome.Existing -> {
                error("outcome ${outcome::class.simpleName} is not a refusal")
            }
        }

    private fun cutoverResponse(cutover: MigrationCutover): MigrationCutoverRecord =
        MigrationCutoverRecord(
            cutover.entityRef,
            cutover.cutoverDate,
            cutover.priorSolution,
            cutover.approvedBy,
            cutover.recordedAt.atOffset(ZoneOffset.UTC),
            cutover.evidenceRef,
        )

    private fun pendingChange(change: PendingChange): PendingElectionChange {
        val rateSource = change.kind == ElectionKind.EXCHANGE_RATE_SOURCE
        return PendingElectionChange(
            changeRef = change.changeRef,
            entityRef = change.entityRef,
            election = if (rateSource) RATE_SOURCE else change.value,
            effectiveFrom = change.effectiveFrom,
            requestedBy = change.requestedBy,
            requestedAt = change.requestedAt.atOffset(ZoneOffset.UTC),
            source = change.value.takeIf { rateSource },
            basis = change.basis?.let { PendingElectionChange.Basis.valueOf(it) },
        )
    }

    private fun boundTenant(tenantRef: String): TenantId {
        val id =
            runCatching {
                TenantRefs.idOf(
                    tenantRef,
                )
            }.getOrElse { invalid("tenantRef is not a usable tenant reference") }
        if (id != tenants.current()) notFound()
        return id
    }

    private fun tenantRef(): String = queries.tenantRef() ?: notFound()

    private fun <T> respond(outcome: StructureOutcome<T>): T =
        when (outcome) {
            is StructureOutcome.Done -> outcome.value
            StructureOutcome.NotFound -> notFound()
            is StructureOutcome.Conflict -> shortCodeConflict(outcome.reason)
            is StructureOutcome.Invalid -> invalid("the short code must be 1 to 10 characters of A-Z and 0-9")
        }
}

private val SINGAPORE: ZoneId = ZoneId.of("Asia/Singapore")

/** The `election` of a pending change made through electRateSource (PendingElectionChange). */
private const val RATE_SOURCE = "RATE_SOURCE"
