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

    /** Marks the request CREATED once tenancy-svc created the tenant. */
    fun markCreated(contractReference: CommercialRef)
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

/** Builds the signed, expiring reference of the first `tenant.admin` invitation (INV-AC-935). */
fun interface InvitationIssuer {
    fun issue(
        contractReference: CommercialRef,
        tenantRef: CommercialRef,
        issuedAt: Instant,
        expiresAt: Instant,
    ): String
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
