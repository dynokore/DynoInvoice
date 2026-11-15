package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/**
 * The CLIENT_ENTITY answer of [LiveSetterPermission] for a link principal (INV-Q-9222 A, INV-REQ-899): access-svc does
 * not subscribe to its own feed, so it answers from its own link store, the facts INV-T-9364 publishes. True exactly
 * when the actor is `link:` followed by the reference of a stored client signatory link (one with a client entity) of
 * [tenant] whose client entity equals the entity asked for, that is not invalidated or revoked and has not expired, and
 * the matrix gives the signatory role the key as PERMITTED or WITHIN_SCOPE (the link ties it to exactly one entity).
 * A failed read, an exception and every look-alike are false (fail closed).
 */
class LinkSignatoryPermission(
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val links: SignedLinks,
    private val matrix: PermissionMatrix,
    private val clock: Clock,
) {
    fun holds(
        actor: String,
        tenant: TenantId,
        scopeRef: String?,
        entityRef: String?,
        permission: Permission,
    ): Boolean {
        if (!actor.startsWith(PREFIX) || tenant.isPlatform || entityRef == null || scopeRef != entityRef) return false
        if (!allowed(permission)) return false
        val linkRef = actor.substring(PREFIX.length)
        return runCatching {
            tenants.bind(tenant, actor) {
                tx.inTransaction {
                    val link = links.find(LinkRef(linkRef))
                    link != null &&
                        link.ref.value == linkRef &&
                        link.client?.value == entityRef &&
                        link.usable &&
                        clock.instant().isBefore(link.expiresAt)
                }
            }
        }.getOrDefault(false)
    }

    private fun allowed(permission: Permission): Boolean =
        permission in matrix.permissionsOf(Role.CLIENT_SIGNATORY) &&
            matrix.allowance(Role.CLIENT_SIGNATORY, permission).let {
                it == Allowance.PERMITTED || it == Allowance.WITHIN_SCOPE
            }

    companion object {
        /** The principal prefix of a link (the publisher's form `link:<linkRef>`). */
        const val PREFIX = "link:"
    }
}
