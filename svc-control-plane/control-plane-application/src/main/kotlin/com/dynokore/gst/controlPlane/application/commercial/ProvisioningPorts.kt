package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Licence
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import java.time.Instant
import java.util.UUID

/** The platform's region list and the sub-processor statement of each region (INV-REQ-1306, INV-REQ-1386). */
interface RegionList {
    /** Whether [key] is an entry on the list, compared by equality. */
    fun contains(key: RegionKey): Boolean

    /** The recorded statement for [key], or null when the region is not on the list. */
    fun statement(key: RegionKey): String?
}

/** A recorded provisioning request with the Idempotency-Key it arrived under (API-15). */
data class ProvisioningRecord(
    val provisioning: TenantProvisioning,
    val idempotencyKey: UUID,
    val fingerprint: String,
)

/** Provisioning requests, in the open transaction of the command. */
interface ProvisioningRepository {
    fun findByContract(contractReference: CommercialRef): ProvisioningRecord?

    fun findByKey(
        idempotencyKey: UUID,
        recordedBy: String,
    ): ProvisioningRecord?

    /** Inserts the request; a stored request is never replaced and its region is never changed. */
    fun save(
        record: ProvisioningRecord,
        recordedBy: String,
    )

    /** Marks the request CREATED with the access-svc `invitationRef` once the first invitation was issued. */
    fun markInvited(
        contractReference: CommercialRef,
        invitationRef: String,
    )

    /** Marks the request CREATED and records that access-svc refused the first administrator with 409. */
    fun markRefused(contractReference: CommercialRef)
}

/** What tenancy-svc `createTenant` is sent (control-plane-svc->tenancy-svc, INV-Q-8675 A, INV-Q-8691 A). */
data class CreateTenantCall(
    val tenantRef: CommercialRef,
    val contractReference: CommercialRef,
    val legalName: String,
    val planRef: PlanRef,
    val dataRegion: RegionKey,
    val credential: CallerCredential,
)

/** The caller's bearer credential, passed on to tenancy-svc; it never prints. */
class CallerCredential(
    val value: String,
) {
    override fun toString() = "CallerCredential(***)"
}

sealed interface TenantCreation {
    /** tenancy-svc created the tenant within the deadline. */
    data object Created : TenantCreation

    /** The call outlasted its deadline or failed: the request stays PENDING and is never reported as created. */
    data object Pending : TenantCreation
}

/** Calls tenancy-svc `createTenant`, bounded by the deadline of the adapter. */
fun interface TenantCreator {
    fun create(call: CreateTenantCall): TenantCreation
}

/** The one-time invitation code of access-svc: it travels only in the signed invitation and never prints. */
class InvitationCode(
    val value: String,
) {
    override fun toString() = "InvitationCode(***)"
}

/** Builds the signed, expiring first `tenant.admin` invitation with the access-svc ref and code (INV-AC-935). */
fun interface InvitationIssuer {
    fun issue(
        contractReference: CommercialRef,
        tenantRef: CommercialRef,
        invitation: FirstAdministratorAnswer.Issued,
        issuedAt: Instant,
        expiresAt: Instant,
    ): String
}

/** `provisionFirstAdministrator` of access-svc (control-plane-svc->access-svc, INV-Q-8185 A). */
data class ProvisionFirstAdministratorCall(
    val tenantRef: CommercialRef,
    val administratorEmail: String,
    val invitationExpiresAt: Instant,
    val idempotencyKey: UUID,
)

/** `reissueFirstAdministratorInvitation` of access-svc (INV-Q-8232 A). */
data class ReissueFirstAdministratorCall(
    val tenantRef: CommercialRef,
    val invitationExpiresAt: Instant,
    val idempotencyKey: UUID,
)

sealed interface FirstAdministratorAnswer {
    /** 201 or 200: the pending grant with its invitation. */
    class Issued(
        val invitationRef: String,
        val invitationCode: InvitationCode,
    ) : FirstAdministratorAnswer

    /** 409: access-svc refused and stored nothing. */
    data object Refused : FirstAdministratorAnswer

    /** No answer within the edge policy (timeout, no connection, open breaker, full bulkhead) or HTTP 503. */
    data object Unavailable : FirstAdministratorAnswer

    /** Any other 5xx, or an answer the access contract does not define. Retrying with the same key stays safe. */
    data object Failed : FirstAdministratorAnswer
}

/** The calls to access-svc made under the service identity of control-plane-svc, never an end-user token. */
interface FirstAdministratorAccess {
    fun provision(call: ProvisionFirstAdministratorCall): FirstAdministratorAnswer

    fun reissue(call: ReissueFirstAdministratorCall): FirstAdministratorAnswer
}

/** Writes the invitation MessageRequested to the outbox in the open transaction (INV-Q-8677 A). */
fun interface InvitationMessages {
    fun request(
        contractReference: CommercialRef,
        firstAdministratorEmail: String,
        invitationRef: String,
        request: RequestContext,
    )
}

/** Licences, in the open transaction of the command. */
interface LicenceRepository {
    fun findByRef(licenceRef: CommercialRef): Licence?

    fun findByTerm(
        installationRef: CommercialRef,
        term: String,
    ): Licence?

    fun save(
        licence: Licence,
        issuedBy: String,
    )
}

/** Builds the signed licence file of the licence members (INV-Q-8679 A): the exact bytes the file holds. */
fun interface LicenceSigner {
    fun sign(
        licenceRef: CommercialRef,
        installationRef: CommercialRef,
        term: String,
        issuedAt: Instant,
    ): ByteArray
}

/**
 * One call of `requestFirstAdministratorInvitationReissue` under its Idempotency-Key: the expiry is fixed when the
 * call first arrives, so every retry sends access-svc the same request. It holds no invitation code.
 */
data class ReissueAttempt(
    val idempotencyKey: UUID,
    val contractReference: CommercialRef,
    val invitationExpiresAt: Instant,
    /** The `invitationRef` access-svc issued; null until it answered 200. */
    val invitationRef: String?,
)

/** Reissue attempts, in the open transaction of the command. */
interface ReissueAttemptRepository {
    fun find(
        idempotencyKey: UUID,
        recordedBy: String,
    ): ReissueAttempt?

    fun save(
        attempt: ReissueAttempt,
        recordedBy: String,
    )

    fun complete(
        idempotencyKey: UUID,
        recordedBy: String,
        invitationRef: String,
    )
}
