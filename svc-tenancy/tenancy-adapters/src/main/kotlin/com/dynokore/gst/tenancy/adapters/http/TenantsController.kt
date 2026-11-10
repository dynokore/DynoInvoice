package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.metering.Entitlement
import com.dynokore.gst.tenancy.application.metering.Metering
import com.dynokore.gst.tenancy.application.offboarding.OffboardOutcome
import com.dynokore.gst.tenancy.application.offboarding.OffboardRequest
import com.dynokore.gst.tenancy.application.offboarding.OffboardTenant
import com.dynokore.gst.tenancy.application.offboarding.OffboardingCodes
import com.dynokore.gst.tenancy.application.offboarding.OffboardingRecord
import com.dynokore.gst.tenancy.application.profile.AppliedOperatingProfiles
import com.dynokore.gst.tenancy.application.profile.AppliedProfile
import com.dynokore.gst.tenancy.application.profile.AppliedProfileOutcome
import com.dynokore.gst.tenancy.application.profile.ProfileScope
import com.dynokore.gst.tenancy.application.questionnaire.AnswerQuestionnaire
import com.dynokore.gst.tenancy.application.questionnaire.EntityFactValues
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnairePermissions
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnaireRecommendations
import com.dynokore.gst.tenancy.application.questionnaire.Recommendation
import com.dynokore.gst.tenancy.application.questionnaire.RecordEntityFacts
import com.dynokore.gst.tenancy.application.tenant.CreateTenant
import com.dynokore.gst.tenancy.application.tenant.OpenTenant
import com.dynokore.gst.tenancy.application.tenant.TenantCreation
import com.dynokore.gst.tenancy.application.tenant.TenantOpening
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantRefused
import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.gst.tenancy.contract.api.TenantsApi
import com.dynokore.gst.tenancy.contract.model.AppliedOperatingProfileRecord
import com.dynokore.gst.tenancy.contract.model.CloseTenantRequest
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.EntitlementResponse
import com.dynokore.gst.tenancy.contract.model.EntityUsage
import com.dynokore.gst.tenancy.contract.model.OffboardTenantRequest
import com.dynokore.gst.tenancy.contract.model.OffboardingRecordResponse
import com.dynokore.gst.tenancy.contract.model.OpenTenantRequest
import com.dynokore.gst.tenancy.contract.model.QuestionnaireAnswersRequest
import com.dynokore.gst.tenancy.contract.model.QuestionnaireAnswersResponse
import com.dynokore.gst.tenancy.contract.model.QuestionnaireRecommendation
import com.dynokore.gst.tenancy.contract.model.RecordAppliedOperatingProfileRequest
import com.dynokore.gst.tenancy.contract.model.RecordEntitlementRequest
import com.dynokore.gst.tenancy.contract.model.RetentionCustodyHolder
import com.dynokore.gst.tenancy.contract.model.TenantExportManifest
import com.dynokore.gst.tenancy.contract.model.TenantResponse
import com.dynokore.gst.tenancy.contract.model.TenantStateResponse
import com.dynokore.gst.tenancy.contract.model.UnsubmittedOverrideAcknowledgement
import com.dynokore.gst.tenancy.contract.model.UsageCountersResponse
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder as DomainHolder

/**
 * TPL-08. createTenant (scope platform, caller control-plane-svc): the tenant is recorded under the `tenantRef` sent,
 * or
 * under the ref derived from the contract reference; a repeat of the same tenant and contract returns the original
 * (INV-Q-7466 A, INV-Q-7468 A). recordAppliedOperatingProfile and getAppliedOperatingProfile (scope tenant, INV-T-4933)
 * store and return the scope, the opaque profile reference and the version, nothing else (INV-Q-5022 A).
 */
@RestController
@Suppress("TooManyFunctions", "LongParameterList") // one function per contract operation; one port per use case
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class TenantsController(
    private val createTenant: CreateTenant,
    private val profiles: AppliedOperatingProfiles,
    private val questionnaire: AnswerQuestionnaire,
    private val entityFacts: RecordEntityFacts,
    private val metering: Metering,
    private val tenants: TenantContext,
    private val caller: CallerSource,
    private val authority: TenantAuthority,
    private val offboard: OffboardTenant,
    private val openTenant: OpenTenant,
) : TenantsApi {
    override fun createTenant(
        idempotencyKey: UUID,
        createTenantRequest: CreateTenantRequest,
    ): ResponseEntity<TenantResponse> {
        val ref = createTenantRequest.tenantRef ?: TenantRefs.refOfContract(createTenantRequest.contractReference)
        val id =
            runCatching {
                TenantRefs.idOf(
                    ref,
                )
            }.getOrElse { invalid("tenantRef is not a usable tenant reference") }
        val requested =
            TenantRecord(
                id.value,
                ref,
                createTenantRequest.legalName,
                createTenantRequest.planRef,
                createTenantRequest.contractReference,
                dataRegion = createTenantRequest.dataRegion,
            )
        val stored =
            try {
                when (val outcome = createTenant.handle(requested)) {
                    is TenantCreation.Created -> {
                        outcome.tenant
                    }

                    is TenantCreation.Repeated -> {
                        outcome.tenant
                    }

                    TenantCreation.Conflict -> {
                        conflict(
                            TenancyErrorCode.TENANT_NOT_CREATED,
                            "the tenant or its contract reference is already recorded",
                        )
                    }
                }
            } catch (e: TenantRefused) {
                if (e.invalid) invalid("the tenant is refused") else throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(response(stored))
    }

    /**
     * offboardTenant (INV-REQ-877, INV-REQ-956, INV-REQ-875): the use case decides; scope TENANT is refused 403 for
     * every caller (INV-Q-8137) and the whole tenant is offboarded through closeTenant (INV-T-8179).
     */
    override fun offboardTenant(
        tenantRef: String,
        idempotencyKey: UUID,
        offboardTenantRequest: OffboardTenantRequest,
    ): ResponseEntity<OffboardingRecordResponse> {
        val outcome =
            offboard.byPerson(
                tenantRef,
                caller.actor(),
                idempotencyKey.toString(),
                offboardRequestOf(offboardTenantRequest),
            )
        return answerOf(outcome)
    }

    /**
     * closeTenant (scope platform, caller control-plane-svc; INV-REQ-8100, owner answer INV-Q-8137): the whole tenant
     * is offboarded through the use case with scope TENANT, no override and no confirmer. The caller check is the
     * generated one of service-runtime (x-sync-edge); this method names no caller.
     */
    override fun closeTenant(
        tenantRef: String,
        idempotencyKey: UUID,
        closeTenantRequest: CloseTenantRequest,
    ): ResponseEntity<OffboardingRecordResponse> {
        val holder = closeTenantRequest.retentionCustodyHolder
        val request =
            OffboardRequest(
                scope = OffboardingScope.TENANT,
                entityRef = null,
                retentionCustodyHolder = DomainHolder(holder.name, holder.contactEmail, holder.contactMobile),
                noticeWindowDays = closeTenantRequest.noticeWindowDays,
            )
        // a service-job call carries no end-user token, so nothing is bound yet: bind the tenant of the path
        val id = runCatching { TenantRefs.idOf(tenantRef) }.getOrElse { notFound() }
        return tenants.bind(id, SCHEDULED_JOB_ACTOR) {
            answerOf(offboard.forWholeTenant(tenantRef, idempotencyKey.toString(), request))
        }
    }

    private companion object {
        /** The audit actor of a call by the scheduled job; the record stores no user (requestedBy is null). */
        const val SCHEDULED_JOB_ACTOR = "tenancy-svc"
    }

    private fun answerOf(outcome: OffboardOutcome): ResponseEntity<OffboardingRecordResponse> =
        when (outcome) {
            is OffboardOutcome.Accepted -> {
                ResponseEntity
                    .status(
                        HttpStatus.ACCEPTED,
                    ).body(offboardingOf(outcome.record))
            }

            is OffboardOutcome.Replayed -> {
                ResponseEntity
                    .status(
                        HttpStatus.ACCEPTED,
                    ).body(offboardingOf(outcome.record))
            }

            is OffboardOutcome.Invalid -> {
                invalid(outcome.reason)
            }

            is OffboardOutcome.Forbidden -> {
                forbidden(outcome.reason)
            }

            OffboardOutcome.NotFound -> {
                notFound()
            }

            is OffboardOutcome.Refused -> {
                constraintRefused("the input ${outcome.input} is unavailable")
            }

            is OffboardOutcome.Conflict -> {
                offboardingConflict(outcome)
            }
        }

    private fun offboardRequestOf(request: OffboardTenantRequest): OffboardRequest {
        val holder = request.retentionCustodyHolder
        return OffboardRequest(
            scope = OffboardingScope.valueOf(request.scope.value),
            entityRef = request.entityRef,
            retentionCustodyHolder = DomainHolder(holder.name, holder.contactEmail, holder.contactMobile),
            secondConfirmationBy = request.secondConfirmationBy,
            unsubmittedOverride =
                request.unsubmittedOverride?.let {
                    UnsubmittedOverride(
                        it.acknowledgedBy,
                        it.statement,
                    )
                },
            noticeWindowDays = request.noticeWindowDays,
            handoverStatementRef = request.handoverStatementRef,
        )
    }

    private fun offboardingConflict(outcome: OffboardOutcome.Conflict): Nothing =
        conflict(
            when (outcome.code) {
                OffboardingCodes.UNSUBMITTED_DOCUMENT -> TenancyErrorCode.UNSUBMITTED_DOCUMENTS_REFUSED
                OffboardingCodes.CUSTODY_HOLDER -> TenancyErrorCode.CUSTODY_HOLDER_REFUSED
                OffboardingCodes.RECORD_EXISTS -> TenancyErrorCode.OFFBOARDING_RECORD_EXISTS
                OffboardingCodes.TENANT_NOT_CLOSABLE -> TenancyErrorCode.TENANT_NOT_CLOSABLE
                else -> error("unknown offboarding refusal code ${outcome.code}")
            },
            outcome.reason,
        )

    private fun offboardingOf(record: OffboardingRecord): OffboardingRecordResponse =
        OffboardingRecordResponse(
            offboardingRef = record.offboardingRef,
            tenantRef = record.tenantRef,
            scope = OffboardingRecordResponse.Scope.valueOf(record.scope.name),
            state = OffboardingRecordResponse.State.valueOf(record.state.name),
            retentionCustodyHolder =
                RetentionCustodyHolder(
                    record.retentionCustodyHolder.name,
                    record.retentionCustodyHolder.contactEmail,
                    record.retentionCustodyHolder.contactMobile,
                ),
            export =
                TenantExportManifest(
                    record.export.exportRef,
                    TenantExportManifest.State.valueOf(record.export.state.name),
                    record.export.contents.map { TenantExportManifest.Contents.valueOf(it.name) },
                ),
            entityRef = record.entityRef,
            unsubmittedOverride =
                record.unsubmittedOverride?.let { UnsubmittedOverrideAcknowledgement(it.acknowledgedBy, it.statement) },
            noticeWindowDays = record.noticeWindowDays,
            handoverStatementRef = record.handoverStatementRef,
        )

    /**
     * openTenant (scope platform, caller control-plane-svc; INV-REQ-8100, INV-Q-8097 B): SCHEDULED becomes OPEN, a
     * repeat for an OPEN tenant answers OPEN and writes nothing, a CLOSED tenant is refused 409 and nothing changes.
     * Only the tenant of the path is touched.
     */
    override fun openTenant(
        tenantRef: String,
        idempotencyKey: UUID,
        openTenantRequest: OpenTenantRequest,
    ): ResponseEntity<TenantStateResponse> =
        when (val outcome = openTenant.handle(tenantRef)) {
            is TenantOpening.Opened -> {
                ResponseEntity.ok(stateOf(outcome.tenant))
            }

            is TenantOpening.Repeated -> {
                ResponseEntity.ok(stateOf(outcome.tenant))
            }

            is TenantOpening.Refused -> {
                conflict(
                    TenancyErrorCode.TENANT_NOT_OPENABLE,
                    "the tenant is closed and cannot be opened",
                )
            }

            TenantOpening.Missing -> {
                notFound()
            }
        }

    private fun stateOf(tenant: TenantRecord) =
        TenantStateResponse(tenant.ref, TenantStateResponse.State.valueOf(tenant.state))

    private fun response(stored: TenantRecord) =
        TenantResponse(stored.ref, stored.legalName, stored.planRef, stored.dataRegion)

    override fun recordAppliedOperatingProfile(
        tenantRef: String,
        idempotencyKey: UUID,
        recordAppliedOperatingProfileRequest: RecordAppliedOperatingProfileRequest,
    ): ResponseEntity<AppliedOperatingProfileRecord> {
        boundTenant(tenantRef)
        val request = recordAppliedOperatingProfileRequest
        val applied =
            AppliedProfile(
                ProfileScope.valueOf(request.scope.value),
                request.entityRef,
                request.profileRef,
                request.profileVersion.toLong(),
            )
        return ResponseEntity.ok(recordOf(tenantRef, answer(profiles.record(applied))))
    }

    override fun getAppliedOperatingProfile(
        tenantRef: String,
        scope: String,
        entityRef: String?,
    ): ResponseEntity<AppliedOperatingProfileRecord> {
        boundTenant(tenantRef)
        val profileScope = ProfileScope.entries.firstOrNull { it.name == scope } ?: invalid("scope is not known")
        return ResponseEntity.ok(recordOf(tenantRef, answer(profiles.read(profileScope, entityRef))))
    }

    /**
     * answerQuestionnaire (INV-REQ-1246): records the answers of the version and returns the recommendation entries;
     * nothing is applied. With `entityRef` the entity facts are recorded (INV-Q-9696 B): the entity-scope check of
     * the caller is the service-runtime filter's (403 SRT-004); `entityFacts` without `entityRef` is 400 SRT-001 and an
     * `entityRef` that is no entity of the tenant is 404 SRT-005; no entity check of the caller is written here.
     */
    private fun holdsAnswerKey(): Boolean =
        try {
            authority.holds(caller.actor(), QuestionnairePermissions.ANSWER)
        } catch (_: RuntimeException) {
            false
        }

    override fun answerQuestionnaire(
        tenantRef: String,
        idempotencyKey: UUID,
        questionnaireAnswersRequest: QuestionnaireAnswersRequest,
    ): ResponseEntity<QuestionnaireAnswersResponse> {
        boundTenant(tenantRef)
        if (!holdsAnswerKey()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val request = questionnaireAnswersRequest
        if (request.entityFacts != null && request.entityRef == null) invalid("entityFacts needs an entityRef")
        if (request.questionnaireVersion.isEmpty()) invalid("questionnaireVersion is empty")
        val entityRef = request.entityRef
        val entries =
            if (entityRef == null) {
                questionnaire.handle(request.questionnaireVersion, request.answers)
            } else {
                // entity scope: the facts are kept per entity and apart from the tenant answers (contract)
                val facts = request.entityFacts
                val values =
                    EntityFactValues(
                        facts?.recordsPurchases,
                        facts?.hasForeignCurrencyDocuments,
                        facts?.claimsInputTaxOnPostingBasis,
                    )
                if (!entityFacts.handle(entityRef, values)) notFound()
                QuestionnaireRecommendations.entries(request.questionnaireVersion, request.answers)
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            QuestionnaireAnswersResponse(
                tenantRef = tenantRef,
                recommendedProfile = "",
                reasons = emptyList(),
                recommendations = entries.map(::entryOf),
            ),
        )
    }

    /** recordEntitlement (INV-REQ-879): stores the plan limits of the tenant, a repeat replaces. */
    override fun recordEntitlement(
        idempotencyKey: UUID,
        tenantRef: String,
        recordEntitlementRequest: RecordEntitlementRequest,
    ): ResponseEntity<EntitlementResponse> {
        boundTenant(tenantRef)
        val request = recordEntitlementRequest
        metering.record(Entitlement(request.planVersion, request.maxEntities, request.maxDocumentsPerMonth))
        return ResponseEntity.ok(
            EntitlementResponse(tenantRef, request.planVersion, request.maxEntities, request.maxDocumentsPerMonth),
        )
    }

    /** getUsageCounters (INV-REQ-878, INV-REQ-907): a read; no plan limit changes its status. */
    override fun getUsageCounters(
        tenantRef: String,
        period: String?,
        entityRef: String?,
    ): ResponseEntity<UsageCountersResponse> {
        boundTenant(tenantRef)
        val usage = metering.usage(period, entityRef)
        return ResponseEntity.ok(
            UsageCountersResponse(
                tenantRef = tenantRef,
                documentsThisPeriod = usage.documents,
                activeEntities = usage.activeEntities,
                period = usage.period,
                resubmissions = usage.resubmissions,
                entities =
                    usage.entities.map {
                        EntityUsage(it.entityRef, it.documents, it.resubmissions, it.archiveVolumeBytes)
                    },
                archiveVolumeBytes = usage.archiveVolumeBytes,
            ),
        )
    }

    private fun entryOf(entry: Recommendation) =
        QuestionnaireRecommendation(
            QuestionnaireRecommendation.Kind.forValue(entry.kind.name),
            entry.ref,
            entry.reason,
        )

    private fun answer(outcome: AppliedProfileOutcome): AppliedProfile =
        when (outcome) {
            is AppliedProfileOutcome.Done -> outcome.profile
            AppliedProfileOutcome.NotFound -> notFound()
            is AppliedProfileOutcome.Invalid -> invalid(outcome.reason)
        }

    private fun recordOf(
        tenantRef: String,
        profile: AppliedProfile,
    ) = AppliedOperatingProfileRecord(
        tenantRef,
        AppliedOperatingProfileRecord.Scope.forValue(profile.scope.name),
        profile.profileRef,
        profile.profileVersion.toInt(),
        profile.entityRef,
    )

    /** The `tenantRef` must name the bound tenant, else the tenant is absent from the caller's scope (404). */
    private fun boundTenant(tenantRef: String) {
        val id =
            runCatching {
                TenantRefs.idOf(
                    tenantRef,
                )
            }.getOrElse { invalid("tenantRef is not a usable tenant reference") }
        if (id != tenants.current()) notFound()
    }
}
