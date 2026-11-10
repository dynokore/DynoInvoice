package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.application.session.Standing
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalState.REVOKED
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-14457: the standing comes from the live authority replica, with the replica's own lag bound. */
class ReplicaPrincipalStandingTest {
    private class MutableClock(
        var now: Instant = Instant.parse("2026-11-10T10:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MutableClock()
    private val replica = AuthorityReplica(clock, OperationRoleMap { emptyMap() })
    private val standing = ReplicaPrincipalStanding(replica)
    private val tenant = TenantId.of("11111111-1111-1111-1111-111111111111")
    private val otherTenant = TenantId.of("22222222-2222-2222-2222-222222222222")

    private fun caughtUp() = replica.onSnapshotComplete(1)

    private fun revoke(
        version: Long,
        principal: String = "p1",
        on: TenantId = tenant,
    ) = replica.accept(AuthorityChanged(on, principal, version, AuthorityRecord.PrincipalRecord(REVOKED)))

    @Test
    fun `INV-AC-14457 a replica with no version received yet is UNKNOWN`() {
        standing.of(tenant.toString(), "p1", 1) shouldBe Standing.UNKNOWN
    }

    @Test
    fun `INV-AC-14457 a lag beyond the bound is UNKNOWN`() {
        caughtUp()
        standing.of(tenant.toString(), "p1", 1) shouldBe Standing.ACTIVE
        replica.observeHead(99)
        clock.now = clock.now.plus(replica.lagBound).plusMillis(1)
        standing.of(tenant.toString(), "p1", 1) shouldBe Standing.UNKNOWN
    }

    @Test
    fun `INV-AC-14457 a revocation of version 7 is ACTIVE for a session at 7 and REVOKED at 6`() {
        caughtUp()
        revoke(7)
        standing.of(tenant.toString(), "p1", 7) shouldBe Standing.ACTIVE
        standing.of(tenant.toString(), "p1", 6) shouldBe Standing.REVOKED
        standing.of(tenant.toString(), "p1", 8) shouldBe Standing.ACTIVE
    }

    @Test
    fun `INV-AC-14457 a different principal and a different tenant with the same id text are ACTIVE`() {
        caughtUp()
        revoke(7)
        standing.of(tenant.toString(), "p2", 6) shouldBe Standing.ACTIVE
        standing.of(otherTenant.toString(), "p1", 6) shouldBe Standing.ACTIVE
    }

    @Test
    fun `INV-AC-14457 the lag bound is the replica's own`() {
        replica.lagBound shouldBe Duration.ofSeconds(1)
    }
}
