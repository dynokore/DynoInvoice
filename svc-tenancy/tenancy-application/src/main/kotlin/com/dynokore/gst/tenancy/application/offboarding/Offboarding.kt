package com.dynokore.gst.tenancy.application.offboarding

import com.dynokore.gst.tenancy.application.confirmation.ConfirmationVerdict
import com.dynokore.gst.tenancy.application.confirmation.SecondConfirmation
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.OffboardingRules
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.gst.tenancy.domain.TenantState
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The request of offboardTenant as the use case takes it; [handoverStatementRef] is stored unchanged (INV-Q-16395). */
data class OffboardRequest(
    val scope: OffboardingScope,
    val entityRef: String?,
    val retentionCustodyHolder: RetentionCustodyHolder?,
    val secondConfirmationBy: String? = null,
    val unsubmittedOverride: UnsubmittedOverride? = null,
    val noticeWindowDays: Int? = null,
    val handoverStatementRef: String? = null,
)

/** One offboarding record. [requestedBy] is a user ID, never a role; null when the scheduled job started it. */
data class OffboardingRecord(
    val offboardingRef: String,
    val tenantRef: String,
    val scope: OffboardingScope,
    val entityRef: String?,
    val state: OffboardingState,
    val retentionCustodyHolder: RetentionCustodyHolder,
    val export: ExportManifest,
    val unsubmittedOverride: UnsubmittedOverride? = null,
    val noticeWindowDays: Int? = null,
    val handoverStatementRef: String? = null,
    val idempotencyKey: String,
    val requestedBy: String? = null,
    val secondConfirmationBy: String? = null,
)

/** The offboarding records of the bound tenant; every method runs in the caller's transaction. */
interface OffboardingStore {
    fun byIdempotencyKey(key: String): OffboardingRecord?

    /** The record of this scope and entity in a state that blocks a new request, else null. */
    fun blocking(
        scope: OffboardingScope,
        entityRef: String?,
    ): OffboardingRecord?

    fun nextOffboardingRef(): String

    fun insert(record: OffboardingRecord)
}

/** The count of unsubmitted in-scope documents (documents-count port); throws [UnavailableInput] when unreadable. */
fun interface UnsubmittedDocuments {
    fun count(
        scope: OffboardingScope,
        entityRef: String?,
    ): Int
}

/** An input of the use case cannot be read or reached: the call is refused 422 POL-014 naming [input] (INV-Q-4900). */
class UnavailableInput(
    val input: String,
) : RuntimeException("the input $input is unavailable")

/** What the export port is asked to produce. */
data class ExportOrder(
    val offboardingRef: String,
    val tenantRef: String,
    val scope: OffboardingScope,
    val entityRef: String?,
)

/** The export port (INV-REQ-877); unbound in profile prod before Wave 4, where [UnboundTenantExport] refuses. */
fun interface TenantExport {
    fun produce(order: ExportOrder): ExportManifest
}

/** The binding of the export port while no export producer exists (INV-Q-4900): refuses naming the input. */
class UnboundTenantExport(
    private val input: String = "tenant-export",
) : TenantExport {
    override fun produce(order: ExportOrder): ExportManifest = throw UnavailableInput(input)
}

sealed interface OffboardOutcome {
    /** 202: the record was stored (its state is COMPLETED, REQUESTED or FAILED by the export manifest). */
    data class Accepted(
        val record: OffboardingRecord,
    ) : OffboardOutcome

    /** 202: the same Idempotency-Key again; the original record, nothing written. */
    data class Replayed(
        val record: OffboardingRecord,
    ) : OffboardOutcome

    /** 400 SRT-001: a missing or malformed property; nothing is stored. */
    data class Invalid(
        val reason: String,
    ) : OffboardOutcome

    /** 403: scope TENANT through the HTTP operation, a caller without the key, or a failed second confirmation. */
    data class Forbidden(
        val reason: String,
    ) : OffboardOutcome

    /** 404: no such tenant or entity in the caller's tenant. */
    data object NotFound : OffboardOutcome

    /** 409 with [code] TNC-008, TNC-009, TNC-010 or TNC-014; nothing is created. */
    data class Conflict(
        val code: String,
        val reason: String,
    ) : OffboardOutcome

    /** 422 POL-014 naming the unavailable [input]; nothing is stored. */
    data class Refused(
        val input: String,
    ) : OffboardOutcome
}

object OffboardingCodes {
    const val UNSUBMITTED_DOCUMENT = "TNC-008"
    const val CUSTODY_HOLDER = "TNC-009"
    const val RECORD_EXISTS = "TNC-010"
    const val TENANT_NOT_CLOSABLE = "TNC-014"
}

/**
 * offboardTenant (INV-REQ-877, INV-REQ-956, INV-REQ-875): [byPerson] is the HTTP operation, [forWholeTenant] the
 * start of the whole-tenant offboarding that closeTenant (INV-T-8179) calls. Scope TENANT is refused for every person
 * (owner answer INV-Q-8137) and takes no decision about a second person (owner answer INV-Q-8024): the TENANT path
 * reads no role, permission or confirmer. Scope ENTITY needs `entity-config.manage` of the caller and a second
 * confirmation (INV-Q-7985 B).
 */
@Suppress("LongParameterList", "TooManyFunctions")
class OffboardTenant(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val store: OffboardingStore,
    private val documents: UnsubmittedDocuments,
    private val export: TenantExport,
    private val authority: ActionAuthority,
    private val tenants: TenantContext,
    private val tenantStore: TenantStore,
) {
    private val confirmation = SecondConfirmation(authority)

    /** The HTTP operation: scope TENANT is refused 403 for every caller and nothing is stored. */
    fun byPerson(
        tenantRef: String,
        caller: String,
        idempotencyKey: String,
        request: OffboardRequest,
    ): OffboardOutcome =
        if (request.scope == OffboardingScope.TENANT) {
            OffboardOutcome.Forbidden("the whole tenant is offboarded through closeTenant")
        } else {
            run(tenantRef, caller, idempotencyKey, request)
        }

    /** The whole-tenant offboarding with no person: `secondConfirmationBy` is ignored, no role is read. */
    fun forWholeTenant(
        tenantRef: String,
        idempotencyKey: String,
        request: OffboardRequest,
    ): OffboardOutcome =
        if (request.scope != OffboardingScope.TENANT) {
            OffboardOutcome.Invalid("scope must be TENANT")
        } else {
            run(tenantRef, null, idempotencyKey, request)
        }

    private fun run(
        tenantRef: String,
        caller: String?,
        idempotencyKey: String,
        request: OffboardRequest,
    ): OffboardOutcome {
        val shape = shapeProblem(request, idempotencyKey)
        val sameTenant = runCatching { TenantRefs.idOf(tenantRef) == tenants.current() }.getOrDefault(false)
        return when {
            shape != null -> OffboardOutcome.Invalid(shape)
            !sameTenant -> OffboardOutcome.NotFound
            else -> tx.inTransaction { decide(tenantRef, caller, idempotencyKey, request) }
        }
    }

    private fun decide(
        tenantRef: String,
        caller: String?,
        idempotencyKey: String,
        request: OffboardRequest,
    ): OffboardOutcome {
        val entityRef = request.entityRef
        val entity = entityRef?.let { structure.entity(it) }
        val refusal =
            when {
                entityRef != null && entity == null -> OffboardOutcome.NotFound
                caller != null && entity != null -> personProblem(caller, entity.ref, request)
                else -> null
            }
        val holder = checkNotNull(request.retentionCustodyHolder)
        return refusal
            ?: store.byIdempotencyKey(idempotencyKey)?.let { OffboardOutcome.Replayed(it) }
            ?: gates(request, holder)
            ?: produce(tenantRef, caller, idempotencyKey, request, holder)
    }

    private fun personProblem(
        caller: String,
        entityRef: String,
        request: OffboardRequest,
    ): OffboardOutcome.Forbidden? {
        val managed =
            try {
                authority.holds(caller, entityRef, ElectionPermissions.MANAGE)
            } catch (_: RuntimeException) {
                false
            }
        val verdict =
            if (managed) confirmation.verdict(caller, checkNotNull(request.secondConfirmationBy), entityRef) else null
        return when {
            !managed -> {
                OffboardOutcome.Forbidden("the caller lacks ${ElectionPermissions.MANAGE} or cannot be proven")
            }

            verdict == ConfirmationVerdict.SAME_PERSON -> {
                OffboardOutcome.Forbidden("the confirmer is the caller")
            }

            verdict == ConfirmationVerdict.NOT_PERMITTED -> {
                OffboardOutcome.Forbidden(
                    "the confirmer does not hold ${ElectionPermissions.CONFIRM} or cannot be proven",
                )
            }

            else -> {
                null
            }
        }
    }

    /** TNC-009, TNC-010, TNC-014 (scope TENANT), TNC-008 in this order; an unreadable count is a refusal. */
    private fun gates(
        request: OffboardRequest,
        holder: RetentionCustodyHolder,
    ): OffboardOutcome? {
        val unsubmitted =
            try {
                documents.count(request.scope, request.entityRef)
            } catch (e: UnavailableInput) {
                return OffboardOutcome.Refused(e.input)
            }
        return when {
            OffboardingRules.holderIsBlank(holder) -> {
                OffboardOutcome.Conflict(OffboardingCodes.CUSTODY_HOLDER, "a retention custody holder must be named")
            }

            store.blocking(request.scope, request.entityRef) != null -> {
                OffboardOutcome.Conflict(OffboardingCodes.RECORD_EXISTS, "an offboarding record already exists")
            }

            request.scope == OffboardingScope.TENANT -> {
                tenantStateProblem() ?: unsubmittedProblem(unsubmitted, request)
            }

            else -> {
                unsubmittedProblem(unsubmitted, request)
            }
        }
    }

    /** TNC-014: only an OPEN tenant is closed; a missing row is NotFound. */
    private fun tenantStateProblem(): OffboardOutcome? {
        val state = tenantStore.current()?.state ?: return OffboardOutcome.NotFound
        return if (state == TenantState.OPEN.name) {
            null
        } else {
            OffboardOutcome.Conflict(OffboardingCodes.TENANT_NOT_CLOSABLE, "the tenant is $state")
        }
    }

    private fun unsubmittedProblem(
        unsubmitted: Int,
        request: OffboardRequest,
    ): OffboardOutcome? =
        when {
            unsubmitted > 0 && request.unsubmittedOverride == null -> {
                OffboardOutcome.Conflict(OffboardingCodes.UNSUBMITTED_DOCUMENT, "$unsubmitted document(s) unsubmitted")
            }

            else -> {
                null
            }
        }

    private fun produce(
        tenantRef: String,
        caller: String?,
        idempotencyKey: String,
        request: OffboardRequest,
        holder: RetentionCustodyHolder,
    ): OffboardOutcome {
        val ref = store.nextOffboardingRef()
        val manifest =
            try {
                export.produce(ExportOrder(ref, tenantRef, request.scope, request.entityRef))
            } catch (e: UnavailableInput) {
                return OffboardOutcome.Refused(e.input)
            }
        val record =
            OffboardingRecord(
                ref,
                tenantRef,
                request.scope,
                request.entityRef,
                OffboardingRules.recordState(manifest),
                holder,
                manifest,
                request.unsubmittedOverride,
                request.noticeWindowDays,
                request.handoverStatementRef,
                idempotencyKey,
                caller,
                request.secondConfirmationBy.takeIf { request.scope == OffboardingScope.ENTITY },
            )
        store.insert(record)
        // INV-Q-8097 B: a COMPLETED record of scope TENANT closes the tenant; ENTITY and FAILED records leave it as is.
        if (record.scope == OffboardingScope.TENANT && record.state == OffboardingState.COMPLETED) tenantStore.close()
        return OffboardOutcome.Accepted(record)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun shapeProblem(
        request: OffboardRequest,
        idempotencyKey: String,
    ): String? {
        val holder = request.retentionCustodyHolder
        return when {
            idempotencyKey.isEmpty() -> {
                "Idempotency-Key is required"
            }

            holder == null -> {
                "retentionCustodyHolder is required"
            }

            holder.name.length > OffboardingRules.HOLDER_NAME_MAX -> {
                "retentionCustodyHolder.name is too long"
            }

            holder.contactEmail.length > OffboardingRules.HOLDER_EMAIL_MAX -> {
                "retentionCustodyHolder.contactEmail is too long"
            }

            (request.handoverStatementRef?.length ?: 0) > OffboardingRules.HANDOVER_REF_MAX -> {
                "handoverStatementRef is too long"
            }

            (request.noticeWindowDays ?: 1) < 1 -> {
                "noticeWindowDays must be at least 1"
            }

            request.scope == OffboardingScope.TENANT && request.entityRef != null -> {
                "entityRef must be absent for scope TENANT"
            }

            request.scope == OffboardingScope.ENTITY && request.entityRef.isNullOrEmpty() -> {
                "entityRef is required for scope ENTITY"
            }

            request.scope == OffboardingScope.ENTITY && request.secondConfirmationBy.isNullOrEmpty() -> {
                "secondConfirmationBy is required for scope ENTITY"
            }

            else -> {
                null
            }
        }
    }
}
