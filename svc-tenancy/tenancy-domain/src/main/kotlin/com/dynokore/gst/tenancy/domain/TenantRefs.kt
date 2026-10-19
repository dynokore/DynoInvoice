package com.dynokore.gst.tenancy.domain

import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/**
 * The mapping between the contract's opaque `tenantRef` text and the uuid tenant id of the tenant column and of the
 * signed binding (INV-Q-7466 A). Pure: nothing is stored. The one file of the service that derives a name-based UUID
 * (the architecture test reads the sources and asserts it).
 */
object TenantRefs {
    private val PLATFORM_ID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    private const val TENANT_PREFIX = "gst-tenant:"
    private const val CONTRACT_PREFIX = "gst-contract:"
    private const val WORKSPACE_PREFIX = "gst-workspace:"

    /**
     * The id of [tenantRef]: the canonical lower-case hyphenated UUID text is that UUID, any other text maps to the
     * name-based UUID of `gst-tenant:` + text. The reserved platform id and empty text are refused.
     */
    fun idOf(tenantRef: String): TenantId {
        require(tenantRef.isNotEmpty()) { "tenantRef is empty" }
        val id = canonical(tenantRef) ?: nameBased(TENANT_PREFIX + tenantRef)
        require(id != PLATFORM_ID) { "tenantRef names the reserved platform tenant" }
        return TenantId(id)
    }

    /** The `ref` of a tenant created without one: a repeat of the same contract maps to the same tenant. */
    fun refOfContract(contractReference: String): String = nameBased(CONTRACT_PREFIX + contractReference).toString()

    /** The `ref` of the SANDBOX sibling [name] of the production tenant [productionRef] (INV-Q-7468). */
    fun refOfWorkspace(
        productionRef: String,
        name: String,
    ): String = nameBased("$WORKSPACE_PREFIX$productionRef:$name").toString()

    private fun canonical(text: String): UUID? =
        runCatching { UUID.fromString(text) }.getOrNull()?.takeIf { it.toString() == text }

    private fun nameBased(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))
}
