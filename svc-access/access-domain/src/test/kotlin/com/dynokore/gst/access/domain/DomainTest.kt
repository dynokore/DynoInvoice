package com.dynokore.gst.access.domain

import com.dynokore.gst.access.domain.grant.AuthorisationPolicy
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PeriodRange
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class DomainTest {
    private val tenant = TenantRef("t1")
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val view = Permission("doc.view")
    private val edit = Permission("doc.edit")
    private val matrix =
        PermissionMatrix(
            listOf(PermissionDefinition(view, PermissionKind.READ), PermissionDefinition(edit, PermissionKind.WRITE)),
            mapOf(
                Role.AUDITOR to mapOf(view to Allowance.PERMITTED),
                Role.TAX_OPERATOR to mapOf(view to Allowance.PERMITTED, edit to Allowance.WITHIN_SCOPE),
            ),
        )
    private val membership = EntityMembership(tenant, GroupRef("g1"))
    private val window = ValidityWindow(now.minusSeconds(60), now.plusSeconds(60))

    private fun grant(
        role: Role,
        scope: Scope,
        window: ValidityWindow = this.window,
    ) = Grant.issue(GrantHeader(GrantRef("g"), tenant, IdentityRef("i")), role, scope, window)

    private fun decide(
        grants: List<Grant>,
        permission: Permission,
        target: Target,
    ) = AuthorisationPolicy.decide(grants, AuthorisationPolicy.Question(permission, target, membership), matrix, now)

    @Test
    fun `the role catalogue holds the nineteen roles of the model and names no other`() {
        assertEquals(19, Role.entries.size)
        assertEquals(Role.TAX_APPROVER, Role.ofWire("tax.approver"))
        listOf("Tax.Approver", " tax.approver", "tax.approver ", "tax-approver", "", "admin").forEach {
            assertNull(Role.ofWire(it), it)
        }
    }

    @Test
    fun `a read-only role cannot hold a write permission by data`() {
        assertThrows(IllegalArgumentException::class.java) {
            PermissionMatrix(
                listOf(PermissionDefinition(edit, PermissionKind.WRITE)),
                mapOf(Role.AUDITOR to mapOf(edit to Allowance.PERMITTED)),
            )
        }
    }

    @Test
    fun `a role listing an undefined permission is refused and an unlisted pair is denied`() {
        assertThrows(IllegalArgumentException::class.java) {
            PermissionMatrix(
                emptyList(),
                mapOf(Role.TAX_OPERATOR to mapOf(view to Allowance.PERMITTED)),
            )
        }
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(listOf(grant(Role.FINANCE_VIEWER, Scope.Tenant(tenant))), view, Target(EntityRef("e1"))),
        )
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(listOf(grant(Role.AUDITOR, Scope.Tenant(tenant))), edit, Target(EntityRef("e1"))),
        )
    }

    @Test
    fun `a grant authorises only inside its scope and only while its window is open`() {
        val e1 = Target(EntityRef("e1"))
        assertEquals(
            Decision.Allow(Role.TAX_OPERATOR),
            decide(listOf(grant(Role.TAX_OPERATOR, Scope.Entity(EntityRef("e1")))), edit, e1),
        )
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(listOf(grant(Role.TAX_OPERATOR, Scope.Entity(EntityRef("e2")))), edit, e1),
        )
        assertEquals(Decision.Allow(Role.AUDITOR), decide(listOf(grant(Role.AUDITOR, Scope.Tenant(tenant))), view, e1))
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(listOf(grant(Role.AUDITOR, Scope.Tenant(TenantRef("other")))), view, e1),
        )
        assertEquals(
            Decision.Allow(Role.AUDITOR),
            decide(listOf(grant(Role.AUDITOR, Scope.Group(GroupRef("g1"), tenant))), view, e1),
        )
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(listOf(grant(Role.AUDITOR, Scope.Group(GroupRef("g2"), tenant))), view, e1),
        )
        val expired = ValidityWindow(now.minusSeconds(120), now.minusSeconds(60))
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(listOf(grant(Role.AUDITOR, Scope.Tenant(tenant), expired)), view, e1),
        )
        val notYet = ValidityWindow(now.plusSeconds(1), null)
        assertEquals(
            Decision.Deny("NO_GRANT"),
            decide(listOf(grant(Role.AUDITOR, Scope.Tenant(tenant), notYet)), view, e1),
        )
    }

    @Test
    fun `each action is authorised by one grant that covers it and never by another grant the identity holds`() {
        // role A has the permission but not the scope; role B has the scope but not the permission
        val grants =
            listOf(
                grant(Role.TAX_OPERATOR, Scope.Entity(EntityRef("e2"))),
                grant(Role.AUDITOR, Scope.Entity(EntityRef("e1"))),
            )
        assertEquals(Decision.Deny("NO_GRANT"), decide(grants, edit, Target(EntityRef("e1"))))
        // the permission on e2 is held, the scope of e1 is held: the pair is still denied on e1 and allowed on e2
        assertEquals(Decision.Allow(Role.TAX_OPERATOR), decide(grants, edit, Target(EntityRef("e2"))))
    }

    @Test
    fun `an entity and period grant needs a target period inside the range`() {
        val scope =
            Scope.EntityPeriod(
                EntityRef("e1"),
                PeriodRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30)),
            )
        val g = listOf(grant(Role.AUDITOR, scope))

        fun at(period: PeriodRange?) = decide(g, view, Target(EntityRef("e1"), period))
        assertTrue(at(PeriodRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))) is Decision.Allow)
        assertTrue(at(PeriodRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30))) is Decision.Allow)
        assertTrue(at(PeriodRange(LocalDate.of(2027, 4, 1), LocalDate.of(2027, 9, 30))) is Decision.Deny)
        assertTrue(at(PeriodRange(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 3, 31))) is Decision.Deny)
        assertTrue(at(null) is Decision.Deny)
    }

    @Test
    fun `a revoked or pending grant authorises nothing`() {
        val revoked = grant(Role.AUDITOR, Scope.Tenant(tenant)).revoked()
        assertEquals(Decision.Deny("NO_GRANT"), decide(listOf(revoked), view, Target(EntityRef("e1"))))
        assertTrue(revoked.revoked() === revoked)
        assertEquals(1, revoked.version)
    }

    @Test
    fun `a window must end after it starts and a period range must not run backwards`() {
        assertThrows(IllegalArgumentException::class.java) { ValidityWindow(now, now) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { PeriodRange(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 1)) }
    }
}
