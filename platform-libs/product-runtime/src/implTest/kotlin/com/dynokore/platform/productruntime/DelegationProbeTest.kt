package com.dynokore.platform.productruntime

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.delegation.AuthorityDelegationProbe
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentScopeItem
import com.dynokore.platform.serviceruntime.replica.ConsentState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** INV-T-2876: the DelegationProbe over the authority replica (INV-Q-3136, INV-Q-3854 (i)). */
class DelegationProbeTest {
    private val day = LocalDate.parse("2026-10-12")
    private val entity = SettingTarget(TENANT, ScopeRef(ScopeLevel.ENTITY, "E-001"), "E-001")

    private fun replicaWith(
        state: ConsentState,
        from: String,
        until: String?,
    ): AuthorityReplica = AuthorityReplica(CLOCK).apply {
        val consent =
            AuthorityRecord.ConsentRecord(
                "E-001",
                state,
                setOf(ConsentScopeItem.entries.first()),
                Instant.parse(from),
                until?.let(Instant::parse),
            )
        accept(AuthorityChanged(TENANT, "agent-1", 1, consent))
        onSnapshotComplete(1)
    }

    private fun probe(replica: AuthorityReplica?) = AuthorityDelegationProbe { replica }

    @Test
    fun `a live agent consent covering the entity on the date answers delegated`() {
        probe(replicaWith(ConsentState.LIVE, "2026-10-01T00:00:00Z", null)).isDelegated(entity, day) shouldBe true
    }

    @Test
    fun `a consent overlapping any instant of the Singapore day counts, one ending before it does not`() {
        // 2026-10-12 in Asia/Singapore is 2026-10-11T16:00Z to 2026-10-12T16:00Z.
        probe(replicaWith(ConsentState.LIVE, "2026-10-12T15:59:00Z", null)).isDelegated(entity, day) shouldBe true
        probe(replicaWith(ConsentState.LIVE, "2026-10-01T00:00:00Z", "2026-10-11T16:01:00Z")).isDelegated(entity, day) shouldBe true
        probe(replicaWith(ConsentState.LIVE, "2026-10-01T00:00:00Z", "2026-10-11T16:00:00Z")).isDelegated(entity, day) shouldBe false
        probe(replicaWith(ConsentState.LIVE, "2026-10-12T16:00:00Z", null)).isDelegated(entity, day) shouldBe false
    }

    @Test
    fun `a withdrawn consent or none at all is not delegated`() {
        probe(replicaWith(ConsentState.WITHDRAWN, "2026-10-01T00:00:00Z", null)).isDelegated(entity, day) shouldBe false
        val other = SettingTarget(TENANT, ScopeRef(ScopeLevel.ENTITY, "E-002"), "E-002")
        probe(replicaWith(ConsentState.LIVE, "2026-10-01T00:00:00Z", null)).isDelegated(other, day) shouldBe false
    }

    @Test
    fun `a client-entity scope answers delegated`() {
        val client = SettingTarget(TENANT, ScopeRef(ScopeLevel.CLIENT_ENTITY, "C-1"), "E-001")
        probe(AuthorityReplica(CLOCK)).isDelegated(client, day) shouldBe true
    }

    @Test
    fun `an unavailable replica answers delegated`() {
        // No snapshot yet: the lag is unknown, so the replica cannot say.
        probe(AuthorityReplica(CLOCK)).isDelegated(entity, day) shouldBe true
    }

    @Test
    fun `an unbound probe answers delegated`() {
        probe(null).isDelegated(entity, day) shouldBe true
    }

    @Test
    fun `a target without an entity is not delegated`() {
        val tenant = SettingTarget(TENANT, ScopeRef(ScopeLevel.TENANT, TENANT.toString()), null)
        probe(replicaWith(ConsentState.LIVE, "2026-10-01T00:00:00Z", null)).isDelegated(tenant, day) shouldBe false
    }
}
