package com.dynokore.gst.tenancy.application.tenant

import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** One tenant row as the contract's TenantResponse reads it. */
data class TenantRecord(
    val tenantId: UUID,
    val ref: String,
    val legalName: String,
    val planRef: String,
    val contractReference: String,
    val structureVersion: Long = 1,
    val retired: Boolean = false,
)

/** What the tenant-creating function answered (INV-Q-7468 A). */
sealed interface TenantCreation {
    data class Created(
        val tenant: TenantRecord,
    ) : TenantCreation

    /** The same tenant and contract again: nothing is written and the original row is returned. */
    data class Repeated(
        val tenant: TenantRecord,
    ) : TenantCreation

    /** Another contract on the tenant, or the contract or ref of another tenant: nothing is written. */
    data object Conflict : TenantCreation
}

/** The binding or the arguments were refused by the function: [invalid] answers the contract's 400, else its 403. */
class TenantRefused(
    val invalid: Boolean,
    message: String,
) : RuntimeException(message)

/** The one route that writes `tenancy.tenant`: a definer function (INV-Q-7468 A). */
interface TenantStore {
    fun create(tenant: TenantRecord): TenantCreation

    /** The bound tenant's own row; row-level security hides every other. */
    fun current(): TenantRecord?
}

/** createTenant (scope platform): runs under the reserved platform binding, never under a tenant's. */
class CreateTenant(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val store: TenantStore,
    private val actor: String,
) {
    fun handle(tenant: TenantRecord): TenantCreation = platform.bindPlatform(actor) { tx.inTransaction { store.create(tenant) } }
}
