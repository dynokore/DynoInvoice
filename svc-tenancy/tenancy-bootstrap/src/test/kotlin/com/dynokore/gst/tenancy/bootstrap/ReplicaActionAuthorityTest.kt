package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The production binding of the permission check (ruling INV-Q-10025) over a real [AuthorityReplica]: true only for a
 * valid role grant of a role the catalogue lists for the permission key, on that entity, for that person, within the lag bound.
 */
class ReplicaActionAuthorityTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val otherTenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val replica = AuthorityReplica(clock, TestOperationRoles.map).also { it.onSnapshotComplete(0) }
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor(): String? = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val authority = ReplicaActionAuthority(replica, tenants, clock)
    private var version = 0L

    private fun grant(
        person: String,
        entity: String,
        role: String,
        validUntil: Instant? = null,
        on: TenantId = tenant,
    ) {
        replica.accept(
            AuthorityChanged(
                on,
                person,
                ++version,
                AuthorityRecord.RoleGrantRecord(
                    "GR-$version",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, GrantScope.ENTITY, entity, now.minusSeconds(60), validUntil),
                ),
            ),
        )
        replica.observeHead(version)
    }

    @Test
    fun `a holder of the permission is accepted`() {
        grant("identity:USR-004", "E1", TAX_APPROVER)
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM)).isTrue()
    }

    // Old case: a manage grant did not hold confirm. In the owner's model no role holds manage without confirm
    // (entity.admin holds both), so the outcome turned; the separation of the two keys is kept by tax.approver.
    @Test
    fun `a person holding only entity-config manage does not hold confirm`() {
        assertThat(TestOperationRoles.holders(ElectionPermissions.MANAGE)).isEqualTo(setOf(ENTITY_ADMIN))
        grant("identity:USR-004", "E1", TAX_APPROVER)
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM)).isTrue()
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.MANAGE)).isFalse()
        grant("identity:USR-005", "E1", ENTITY_ADMIN)
        assertThat(authority.holds("identity:USR-005", "E1", ElectionPermissions.MANAGE)).isTrue()
        assertThat(authority.holds("identity:USR-005", "E1", ElectionPermissions.CONFIRM)).isTrue()
    }

    @Test
    fun `an expired grant, a revoked person, the old role-name guess and look-alikes are refused`() {
        grant("identity:USR-001", "E1", TAX_APPROVER, validUntil = now.minusSeconds(1))
        assertThat(authority.holds("identity:USR-001", "E1", ElectionPermissions.CONFIRM)).isFalse()
        grant("identity:USR-002", "E1", TAX_APPROVER)
        replica.accept(
            AuthorityChanged(
                tenant,
                "identity:USR-002",
                ++version,
                AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED),
            ),
        )
        replica.observeHead(version)
        assertThat(authority.holds("identity:USR-002", "E1", ElectionPermissions.CONFIRM)).isFalse()
        // a role whose text equals a permission key holds neither key
        grant("identity:USR-003", "E1", ElectionPermissions.CONFIRM)
        grant("identity:USR-006", "E1", ElectionPermissions.MANAGE)
        listOf("identity:USR-003", "identity:USR-006").forEach {
            assertThat(authority.holds(it, "E1", ElectionPermissions.CONFIRM)).describedAs(it).isFalse()
            assertThat(authority.holds(it, "E1", ElectionPermissions.MANAGE)).describedAs(it).isFalse()
        }
        grant("identity:USR-007", "E1", "$TAX_APPROVER ")
        assertThat(authority.holds("identity:USR-007", "E1", ElectionPermissions.CONFIRM)).isFalse()
        grant("identity:USR-004", "E1", TAX_APPROVER)
        assertThat(authority.holds("identity:USR-004", "E1", "${ElectionPermissions.CONFIRM} ")).isFalse()
        assertThat(authority.holds("identity:USR-004 ", "E1", ElectionPermissions.CONFIRM)).isFalse()
        assertThat(authority.holds("identity:UNKNOWN", "E1", ElectionPermissions.CONFIRM)).isFalse()
        // a grant on another entity is not a grant on this one
        assertThat(authority.holds("identity:USR-004", "E2", ElectionPermissions.CONFIRM)).isFalse()
    }

    @Test
    fun `INV-AC-9515 election-change approve is held only by its own role grant and refused for every look-alike`() {
        val approve = ElectionPermissions.APPROVE
        assertThat(TestOperationRoles.holders(approve)).isEqualTo(setOf(TAX_APPROVER))
        grant("identity:USR-009", "E1", TAX_APPROVER)
        assertThat(authority.holds("identity:USR-009", "E1", approve)).isTrue()
        assertThat(authority.holds("identity:USR-009", "E1", "$approve ")).isFalse()
        assertThat(authority.holds("identity:USR-009 ", "E1", approve)).isFalse()
        assertThat(authority.holds("identity:USR-009", "E2", approve)).isFalse()
        // the roles that hold confirm or manage but not approve
        grant("identity:USR-010", "E1", ENTITY_ADMIN)
        grant("identity:USR-011", "E1", "tenant.admin")
        // a role whose text is the permission key, and the key text with a trailing space
        grant("identity:USR-012", "E1", approve)
        grant("identity:USR-015", "E1", "$TAX_APPROVER ")
        listOf("identity:USR-010", "identity:USR-011", "identity:USR-012", "identity:USR-015").forEach {
            assertThat(authority.holds(it, "E1", approve)).describedAs(it).isFalse()
        }
        grant("identity:USR-013", "E1", TAX_APPROVER, validUntil = now.minusSeconds(1))
        assertThat(authority.holds("identity:USR-013", "E1", approve)).isFalse()
        grant("identity:USR-014", "E1", TAX_APPROVER)
        replica.accept(
            AuthorityChanged(
                tenant,
                "identity:USR-014",
                ++version,
                AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED),
            ),
        )
        replica.observeHead(version)
        assertThat(authority.holds("identity:USR-014", "E1", approve)).isFalse()
        assertThat(ReplicaActionAuthority(null, tenants, clock).holds("identity:USR-009", "E1", approve)).isFalse()
    }

    @Test
    fun `a grant of another tenant is not read`() {
        grant("identity:USR-004", "E1", TAX_APPROVER, on = otherTenant)
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM)).isFalse()
    }

    @Test
    fun `no replica bound and a lag beyond the bound refuse the person`() {
        assertThat(ReplicaActionAuthority(null, tenants, clock).holds("p", "E1", ElectionPermissions.CONFIRM)).isFalse()
        grant("identity:USR-004", "E1", TAX_APPROVER)
        // the source head runs ahead of what the replica applied for longer than the bound: no fallback in tenancy-svc
        val late = MutableClock(now)
        val lagging =
            AuthorityReplica(late, TestOperationRoles.map, lagBound = Duration.ofSeconds(1)).also {
                it.onSnapshotComplete(0)
            }
        lagging.accept(
            AuthorityChanged(
                tenant,
                "identity:USR-004",
                1,
                AuthorityRecord.RoleGrantRecord(
                    "GR-1",
                    RoleGrantState.ACTIVE,
                    RoleGrant(TAX_APPROVER, GrantScope.ENTITY, "E1", now.minusSeconds(60)),
                ),
            ),
        )
        lagging.observeHead(5)
        late.advance(Duration.ofSeconds(30))
        assertThat(
            ReplicaActionAuthority(lagging, tenants, late).holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM),
        ).isFalse()
    }

    private companion object {
        const val TAX_APPROVER = "tax.approver"
        const val ENTITY_ADMIN = "entity.admin"
    }

    private class MutableClock(
        private var instant: Instant,
    ) : Clock() {
        fun advance(by: Duration) {
            instant = instant.plus(by)
        }

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = instant
    }
}
