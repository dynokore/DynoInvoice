package com.dynokore.gst.access.domain

import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PlatformAuthorisation
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** INV-AC-9789 (domain side): the platform decision is a pure function of grants, matrix and instant. */
class PlatformAuthorisationTest {
    private val now = Instant.parse("2026-10-26T08:00:00Z")
    private val tenant = TenantRef("ffffffff-ffff-ffff-ffff-ffffffffffff")
    private val publish = Permission("release-train.publish")
    private val plan = Permission("plan.manage")
    private val scoped = Permission("entity.scoped")
    private val matrix =
        PermissionMatrix(
            listOf(
                PermissionDefinition(publish, PermissionKind.WRITE),
                PermissionDefinition(plan, PermissionKind.WRITE),
                PermissionDefinition(scoped, PermissionKind.READ),
            ),
            mapOf(
                Role.RELEASE_MANAGER to mapOf(publish to Allowance.PERMITTED, scoped to Allowance.WITHIN_SCOPE),
                Role.COMMERCIAL_ADMIN to mapOf(plan to Allowance.PERMITTED),
            ),
        )
    private val open = ValidityWindow(now.minusSeconds(60), now.plusSeconds(60))

    private fun grant(
        role: Role,
        status: GrantStatus = GrantStatus.ACTIVE,
        window: ValidityWindow = open,
        scope: Scope = Scope.Tenant(tenant),
    ) = Grant(
        GrantHeader(GrantRef("g"), tenant, IdentityRef("i")),
        role,
        scope,
        window,
        GrantState(status, GrantSource.BY_HAND, 0),
    )

    private fun decide(
        vararg grants: Grant,
        permission: Permission = publish,
    ) = PlatformAuthorisation.decide(grants.toList(), permission, matrix, now)

    @Test
    fun `INV-AC-9789 an active tenant grant of a permitted role allows with that role`() {
        assertEquals(Decision.Allow(Role.RELEASE_MANAGER), decide(grant(Role.RELEASE_MANAGER)))
        assertEquals(Decision.Allow(Role.COMMERCIAL_ADMIN), decide(grant(Role.COMMERCIAL_ADMIN), permission = plan))
    }

    @Test
    fun `INV-AC-9789 a role without PERMITTED for the permission is denied`() {
        assertEquals(Decision.Deny("NO_GRANT"), decide(grant(Role.COMMERCIAL_ADMIN)))
        assertEquals(Decision.Deny("NO_GRANT"), decide(grant(Role.RELEASE_MANAGER), permission = scoped))
        assertEquals(Decision.Deny("NO_GRANT"), decide(grant(Role.RELEASE_MANAGER), permission = Permission("x.y")))
    }

    @Test
    fun `INV-AC-9789 a pending, revoked or out-of-window grant and no grant are denied`() {
        val deny = Decision.Deny("NO_GRANT")
        assertEquals(deny, decide(grant(Role.RELEASE_MANAGER, status = GrantStatus.PENDING)))
        assertEquals(deny, decide(grant(Role.RELEASE_MANAGER, status = GrantStatus.REVOKED)))
        assertEquals(deny, decide(grant(Role.RELEASE_MANAGER, window = ValidityWindow(now.minusSeconds(60), now))))
        assertEquals(
            deny,
            decide(grant(Role.RELEASE_MANAGER, window = ValidityWindow(now.plusSeconds(1), now.plusSeconds(60)))),
        )
        assertEquals(deny, decide())
    }

    @Test
    fun `INV-AC-9789 only a tenant-level grant counts, and another grant of the list can still allow`() {
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(
                grant(
                    Role.RELEASE_MANAGER,
                    scope =
                        Scope.Entity(
                            com.dynokore.gst.access.domain.grant
                                .EntityRef("E"),
                        ),
                ),
            ),
        )
        assertEquals(
            Decision.Allow(Role.RELEASE_MANAGER),
            decide(grant(Role.COMMERCIAL_ADMIN), grant(Role.RELEASE_MANAGER)),
        )
    }
}
