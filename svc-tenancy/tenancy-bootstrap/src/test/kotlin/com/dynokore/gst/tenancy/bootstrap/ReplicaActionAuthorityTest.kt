package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
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
 * valid grant whose action equals the permission key, on that entity, for that person, within the lag bound.
 */
class ReplicaActionAuthorityTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val otherTenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val replica = AuthorityReplica(clock).also { it.onSnapshotComplete(0) }
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
        action: String,
        validUntil: Instant? = null,
        on: TenantId = tenant,
    ) {
        replica.accept(
            AuthorityChanged(
                on,
                person,
                ++version,
                AuthorityRecord.GrantRecord(
                    entity,
                    listOf(Grant(action, ScopeLevel.ENTITY, entity, now.minusSeconds(60), validUntil)),
                ),
            ),
        )
        replica.observeHead(version)
    }

    @Test
    fun `a holder of the permission is accepted`() {
        grant("identity:USR-004", "E1", ElectionPermissions.CONFIRM)
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM)).isTrue()
    }

    @Test
    fun `a person holding only entity-config manage does not hold confirm`() {
        grant("identity:USR-004", "E1", ElectionPermissions.MANAGE)
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM)).isFalse()
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.MANAGE)).isTrue()
    }

    @Test
    fun `an expired grant, a revoked person, the old role-name guess and look-alikes are refused`() {
        grant("identity:USR-001", "E1", ElectionPermissions.CONFIRM, validUntil = now.minusSeconds(1))
        assertThat(authority.holds("identity:USR-001", "E1", ElectionPermissions.CONFIRM)).isFalse()
        grant("identity:USR-002", "E1", ElectionPermissions.CONFIRM)
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
        grant("identity:USR-003", "E1", "entity.admin")
        assertThat(authority.holds("identity:USR-003", "E1", ElectionPermissions.CONFIRM)).isFalse()
        assertThat(authority.holds("identity:USR-003", "E1", ElectionPermissions.MANAGE)).isFalse()
        grant("identity:USR-004", "E1", ElectionPermissions.CONFIRM)
        assertThat(authority.holds("identity:USR-004", "E1", "${ElectionPermissions.CONFIRM} ")).isFalse()
        assertThat(authority.holds("identity:USR-004 ", "E1", ElectionPermissions.CONFIRM)).isFalse()
        assertThat(authority.holds("identity:UNKNOWN", "E1", ElectionPermissions.CONFIRM)).isFalse()
        // a grant on another entity is not a grant on this one
        assertThat(authority.holds("identity:USR-004", "E2", ElectionPermissions.CONFIRM)).isFalse()
    }

    @Test
    fun `election-change approve is held only by its own grant and refused for every look-alike`() {
        val approve = ElectionPermissions.APPROVE
        grant("identity:USR-009", "E1", approve)
        assertThat(authority.holds("identity:USR-009", "E1", approve)).isTrue()
        assertThat(authority.holds("identity:USR-009", "E1", "$approve ")).isFalse()
        assertThat(authority.holds("identity:USR-009 ", "E1", approve)).isFalse()
        assertThat(authority.holds("identity:USR-009", "E2", approve)).isFalse()
        grant("identity:USR-010", "E1", ElectionPermissions.CONFIRM)
        grant("identity:USR-011", "E1", ElectionPermissions.MANAGE)
        grant("identity:USR-012", "E1", "tax.approver")
        listOf("identity:USR-010", "identity:USR-011", "identity:USR-012").forEach {
            assertThat(authority.holds(it, "E1", approve)).describedAs(it).isFalse()
        }
        grant("identity:USR-013", "E1", approve, validUntil = now.minusSeconds(1))
        assertThat(authority.holds("identity:USR-013", "E1", approve)).isFalse()
        grant("identity:USR-014", "E1", approve)
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
        grant("identity:USR-004", "E1", ElectionPermissions.CONFIRM, on = otherTenant)
        assertThat(authority.holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM)).isFalse()
    }

    @Test
    fun `no replica bound and a lag beyond the bound refuse the person`() {
        assertThat(ReplicaActionAuthority(null, tenants, clock).holds("p", "E1", ElectionPermissions.CONFIRM)).isFalse()
        grant("identity:USR-004", "E1", ElectionPermissions.CONFIRM)
        // the source head runs ahead of what the replica applied for longer than the bound: no fallback in tenancy-svc
        val late = MutableClock(now)
        val lagging = AuthorityReplica(late, lagBound = Duration.ofSeconds(1)).also { it.onSnapshotComplete(0) }
        lagging.accept(
            AuthorityChanged(
                tenant,
                "identity:USR-004",
                1,
                AuthorityRecord.GrantRecord(
                    "E1",
                    listOf(Grant(ElectionPermissions.CONFIRM, ScopeLevel.ENTITY, "E1", now.minusSeconds(60))),
                ),
            ),
        )
        lagging.observeHead(5)
        late.advance(Duration.ofSeconds(30))
        assertThat(
            ReplicaActionAuthority(lagging, tenants, late).holds("identity:USR-004", "E1", ElectionPermissions.CONFIRM),
        ).isFalse()
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
