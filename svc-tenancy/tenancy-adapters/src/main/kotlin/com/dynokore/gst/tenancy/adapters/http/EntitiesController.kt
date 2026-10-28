package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.election.ElectionCommands
import com.dynokore.gst.tenancy.application.election.ElectionOutcome
import com.dynokore.gst.tenancy.application.election.ElectionRecord
import com.dynokore.gst.tenancy.application.election.ElectionStatusQueries
import com.dynokore.gst.tenancy.application.period.FilingFrequencyCommands
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
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.ElectionRequest
import com.dynokore.gst.tenancy.contract.model.ElectionResponse
import com.dynokore.gst.tenancy.contract.model.ElectionStatusResponse
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.EntityStatusResponse
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyChangeRequest
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.IssuanceModeResponse
import com.dynokore.gst.tenancy.contract.model.MoveBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentResponse
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionResponse
import com.dynokore.gst.tenancy.contract.model.RegisterBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.contract.model.StructureNode
import com.dynokore.gst.tenancy.contract.model.UpdateBusinessUnitRequest
import com.dynokore.gst.tenancy.domain.CodeRefusal
import com.dynokore.gst.tenancy.domain.IssuanceMode
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * TPL-08. The structure operations of the tenancy contract that this service implements in INV-T-4925: createGroup,
 * createEntity, registerBusinessUnit, moveBusinessUnit, updateBusinessUnit and getEntityStructure (scope tenant). The
 * request's `tenantRef` must name the bound tenant, else the tenant is absent from the caller's scope (404).
 */
@RestController
@Suppress("TooManyFunctions", "LongParameterList") // one function per contract operation; one collaborator per use case
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
        val outcome =
            electionCommands.recordElection(entityRef, electionRequest.election, electionRequest.effectiveFrom)
        val done = elected(outcome)
        return ResponseEntity
            .status(created(outcome))
            .body(ElectionResponse(entityRef, done.value, done.effectiveFrom))
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

    private fun elected(outcome: ElectionOutcome): ElectionRecord =
        when (outcome) {
            is ElectionOutcome.Done -> outcome.election
            ElectionOutcome.NotFound -> notFound()
            is ElectionOutcome.Invalid -> invalid(outcome.reason)
            is ElectionOutcome.Refused -> constraintRefused(outcome.reason)
            is ElectionOutcome.Forbidden -> forbidden(outcome.reason)
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
