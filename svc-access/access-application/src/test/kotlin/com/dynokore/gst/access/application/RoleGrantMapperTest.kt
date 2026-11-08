package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.application.grant.RoleGrantMapper
import com.dynokore.gst.access.application.grant.RoleGrantState
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PeriodRange
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** INV-Q-8972 A: one role-grant change per grant, for every scope level and state, read from the grant alone. */
class RoleGrantMapperTest {
    private val tenant = TenantRef("0a000000-0000-4000-8000-00000000000a")
    private val identity = IdentityRef("IDN-1")
    private val from = Instant.parse("2026-10-19T00:00:00Z")
    private val until = Instant.parse("2027-09-30T23:59:59Z")
    private val mapper = RoleGrantMapper()

    private fun grant(
        scope: Scope,
        ref: String = "GRT-1",
        status: GrantStatus = GrantStatus.ACTIVE,
        role: Role = Role.TAX_APPROVER,
        window: ValidityWindow = ValidityWindow(from, until),
    ) = Grant(
        GrantHeader(GrantRef(ref), tenant, identity),
        role,
        scope,
        window,
        GrantState(status, com.dynokore.gst.access.domain.grant.GrantSource.BY_HAND, 0),
    )

    private val scopes: Map<ScopeLevel, Pair<Scope, String>> =
        mapOf(
            ScopeLevel.TENANT to (Scope.Tenant(tenant) to tenant.value),
            ScopeLevel.GROUP to (Scope.Group(GroupRef("GRP-1"), tenant) to "GRP-1"),
            ScopeLevel.ENTITY to (Scope.Entity(EntityRef("ENT-1")) to "ENT-1"),
            ScopeLevel.ENTITY_PERIOD to
                (
                    Scope.EntityPeriod(
                        EntityRef("ENT-1"),
                        PeriodRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30)),
                    ) to "ENT-1"
                ),
            ScopeLevel.CONSENT to (Scope.ConsentDerived(tenant, ConsentCheck { true }) to tenant.value),
        )

    @Test
    fun `one change per scope level and per state with the expected fields`() {
        assertEquals(ScopeLevel.entries.toSet(), scopes.keys)
        scopes.forEach { (level, pair) ->
            val (scope, ref) = pair
            listOf(GrantStatus.ACTIVE to RoleGrantState.ACTIVE, GrantStatus.REVOKED to RoleGrantState.REMOVED)
                .forEach { (status, state) ->
                    val period = scope as? Scope.EntityPeriod
                    val expected =
                        RoleGrantChange(
                            tenant,
                            identity,
                            GrantRef("GRT-1"),
                            Role.TAX_APPROVER,
                            state,
                            level,
                            ref,
                            from,
                            until,
                            period?.periods?.from,
                            period?.periods?.to,
                            41,
                        )
                    assertEquals(
                        listOf(expected),
                        mapper.forChange(grant(scope, status = status)) { 41 },
                        "$level $state",
                    )
                }
        }
    }

    @Test
    fun `the version is drawn once and a revoke keeps the grantRef at a higher version`() {
        val sequence = AtomicLong(10)
        val scope = scopes.getValue(ScopeLevel.GROUP).first
        val granted = mapper.forChange(grant(scope)) { sequence.incrementAndGet() }.single()
        val revoked =
            mapper.forChange(grant(scope, status = GrantStatus.REVOKED)) { sequence.incrementAndGet() }.single()
        assertEquals(11L, granted.authorityVersion)
        assertEquals(granted.grantRef, revoked.grantRef)
        assertTrue(revoked.authorityVersion > granted.authorityVersion)
        assertEquals(12L, sequence.get())
    }

    @Test
    fun `an open-ended window has no end and only an entity-period grant carries a span`() {
        val open = ValidityWindow(from, null)
        scopes.forEach { (level, pair) ->
            val change = mapper.forChange(grant(pair.first, window = open)) { 1 }.single()
            assertNull(change.validUntil, level.name)
            assertEquals(level == ScopeLevel.ENTITY_PERIOD, change.periodFrom != null && change.periodTo != null)
        }
    }

    @Test
    fun `the group grant carries the group and not the tenant as its scope reference`() {
        val change = mapper.forChange(grant(scopes.getValue(ScopeLevel.GROUP).first)) { 1 }.single()
        assertEquals("GRP-1", change.scopeRef)
    }

    @Test
    fun `a pending grant is never published`() {
        assertThrows(IllegalStateException::class.java) {
            mapper.forChange(grant(scopes.getValue(ScopeLevel.TENANT).first, status = GrantStatus.PENDING)) { 1 }
        }
    }

    @Test
    fun `a reactivation re-emits one ACTIVE change for each active grant, each at a new higher version`() {
        val all =
            listOf(
                grant(scopes.getValue(ScopeLevel.TENANT).first, ref = "GRT-1"),
                grant(scopes.getValue(ScopeLevel.GROUP).first, ref = "GRT-2"),
                grant(scopes.getValue(ScopeLevel.ENTITY).first, ref = "GRT-3"),
                grant(scopes.getValue(ScopeLevel.ENTITY).first, ref = "GRT-4", status = GrantStatus.REVOKED),
            )
        val sequence = AtomicLong(100)
        val changes = mapper.forReactivation(all) { sequence.incrementAndGet() }
        assertEquals(listOf("GRT-1", "GRT-2", "GRT-3"), changes.map { it.grantRef.value })
        assertEquals(listOf(101L, 102L, 103L), changes.map { it.authorityVersion })
        assertTrue(changes.all { it.state == RoleGrantState.ACTIVE })
    }
}
