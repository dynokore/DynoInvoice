package com.dynokore.gst.access.application.token

import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalType

/** The environment of a tenant (INV-Q-9032 A). */
enum class TenantEnvironment { PRODUCTION, SANDBOX }

/**
 * Reads the environment of a tenant from the replica. Null means the environment is not known: no value, an
 * unrecognised value, or no node in the replica.
 */
fun interface TenantEnvironments {
    fun of(tenant: TenantRef): TenantEnvironment?
}

/**
 * The certification rule (INV-Q-9032 A): a CERTIFICATION principal is permitted only in a SANDBOX tenant, so an unknown
 * environment fails closed; every other type is permitted in every environment, known or not.
 */
object CertificationPolicy {
    fun permits(
        type: PrincipalType,
        environment: TenantEnvironment?,
    ): Boolean = type != PrincipalType.CERTIFICATION || environment == TenantEnvironment.SANDBOX
}
