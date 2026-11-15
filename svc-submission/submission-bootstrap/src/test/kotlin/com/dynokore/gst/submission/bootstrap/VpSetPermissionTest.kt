package com.dynokore.gst.submission.bootstrap

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * VP-11 and VP-13 are set by the permissions `vp-11.set` and `vp-13.set` (INV-T-9366, INV-Q-9222 A, INV-Q-10027,
 * INV-Q-10107 C). The service mounts no `PolicyCommandServlet` (it has no `PolicyWriter`: the variation point store
 * belongs to the task that owns it), so every case calls `AuthorityReplica.decide` of the production context with the
 * action and the level CLIENT_ENTITY and compares `allowed`, `source` and `reason`. The holder set of both keys is the
 * role `client.signatory` (platform-contracts role-permissions); the service compares no role name.
 */
class VpSetPermissionTest {
    private val clock = Clock.systemUTC()
    private val tenant = TenantId.of("11111111-1111-4111-8111-111111111111")
    private val otherTenant = TenantId.of("22222222-2222-4222-8222-222222222222")
    private val signatory = "link:LNK-771A"
    private val cli1 = "CLI-2201"
    private val cli2 = "CLI-2202"
    private val version = AtomicLong(0)

    private val granted = Triple(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val refused = Triple(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
    private val degraded = Triple(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)

    private fun serve(
        dir: Path,
        fallback: AuthorityFallback? = null,
        block: (AuthorityReplica) -> Unit,
    ) {
        // registered per context, never as a scanned @Configuration: it would join every context of the package
        val runner = SubmissionContext.offline(dir)
        val withFallback = fallback?.let { runner.withBean(AuthorityFallback::class.java, { it }) } ?: runner
        withFallback.run { context ->
            context.startupFailure.shouldBeNull()
            block(context.getBean(AuthorityReplica::class.java))
        }
    }

    private fun current(replica: AuthorityReplica) {
        replica.onSnapshotComplete(version.get())
        replica.observeHead(version.get())
    }

    private fun feed(
        replica: AuthorityReplica,
        who: String,
        role: String,
        scope: GrantScope,
        ref: String?,
        inTenant: TenantId = tenant,
        state: RoleGrantState = RoleGrantState.ACTIVE,
        until: Instant? = null,
        grantRef: String = "$role/$scope/$ref",
    ) {
        val grant = RoleGrant(role, scope, ref, Instant.now().minusSeconds(DAY), until)
        replica.accept(
            AuthorityChanged(
                inTenant,
                who,
                version.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(grantRef, state, grant),
            ),
        )
        current(replica)
    }

    private fun decide(
        replica: AuthorityReplica,
        action: String,
        who: String = signatory,
        entity: String = cli1,
        level: ScopeLevel = ScopeLevel.CLIENT_ENTITY,
        inTenant: TenantId = tenant,
    ): Triple<Boolean, DecisionSource, DecisionReason> {
        val request = AuthorizationRequest(inTenant, Principal(who, PrincipalKind.HUMAN), entity, action, level = level)
        val d: AuthorityDecision = replica.decide(request, Deadline.after(Duration.ofSeconds(5), clock))
        return Triple(d.allowed, d.source, d.reason)
    }

    @Test
    fun `INV-AC-2224 exactly one AuthorityReplica exists, no servlet is mounted and no role resolver bean exists`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(AuthorityReplica::class.java).size shouldBe 1
            context.containsBean("policyCommandServlet") shouldBe false
            context.beanDefinitionNames.filter { it.contains("RoleResolver", ignoreCase = true) } shouldBe emptyList()
        }
    }

    @Test
    fun `the holder of client signatory at CLIENT_ENTITY on CLI-2201 is allowed for vp-11 and vp-13 only on CLI-2201`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        feed(replica, signatory, "client.signatory", GrantScope.CLIENT_ENTITY, cli1)
        for (action in listOf("vp-11.set", "vp-13.set")) {
            decide(replica, action) shouldBe granted
            decide(replica, action, entity = cli2) shouldBe refused
            decide(replica, action, level = ScopeLevel.TENANT) shouldBe refused
            decide(replica, action, level = ScopeLevel.ENTITY) shouldBe refused
            decide(replica, action, inTenant = otherTenant) shouldBe refused
        }
    }

    @Test
    fun `a grant of another tenant, an expired grant and a removed grant are each refused`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        feed(replica, signatory, "client.signatory", GrantScope.CLIENT_ENTITY, cli1, inTenant = otherTenant)
        decide(replica, "vp-11.set") shouldBe refused

        val lapsed = "link:LNK-771C"
        feed(replica, lapsed, "client.signatory", GrantScope.CLIENT_ENTITY, cli1, until = Instant.now().minusSeconds(60))
        decide(replica, "vp-13.set", who = lapsed) shouldBe refused

        val removed = "link:LNK-771D"
        feed(replica, removed, "client.signatory", GrantScope.CLIENT_ENTITY, cli1, grantRef = "g1")
        decide(replica, "vp-13.set", who = removed) shouldBe granted
        feed(
            replica,
            removed,
            "client.signatory",
            GrantScope.CLIENT_ENTITY,
            cli1,
            state = RoleGrantState.REMOVED,
            grantRef = "g1",
        )
        decide(replica, "vp-13.set", who = removed) shouldBe refused
    }

    @Test
    fun `INV-AC-2224 the look-alike principals and keys are refused`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        feed(replica, signatory, "client.signatory", GrantScope.CLIENT_ENTITY, cli1)
        for (who in listOf("link:LNK-771B", "LNK-771A", "link:lnk-771a", "link:LNK-771A ")) {
            decide(replica, "vp-11.set", who = who) shouldBe refused
            decide(replica, "vp-13.set", who = who) shouldBe refused
        }
        for (action in listOf("vp-11.set ", "VP-11.set", "vp-11.sets", "vp-1.set", "vp-14.set")) {
            decide(replica, action) shouldBe refused
        }
    }

    @Test
    fun `INV-AC-2224 a principal holding another role of the catalogue is refused`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        for ((i, role) in listOf("tenant.admin", "tax.approver", "client.signatory2", "Client.Signatory").withIndex()) {
            val who = "link:LNK-90$i"
            feed(replica, who, role, GrantScope.CLIENT_ENTITY, cli1)
            feed(replica, who, role, GrantScope.TENANT, tenant.toString())
            decide(replica, "vp-11.set", who = who) shouldBe refused
            decide(replica, "vp-13.set", who = who) shouldBe refused
        }
    }

    @Test
    fun `a replica with no snapshot is DEGRADED and refused`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        decide(replica, "vp-11.set") shouldBe degraded
    }

    @Test
    fun `a stale replica is DEGRADED and refused for a holder`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        feed(replica, signatory, "client.signatory", GrantScope.CLIENT_ENTITY, cli1)
        decide(replica, "vp-13.set") shouldBe granted
        replica.onDisconnected()
        Thread.sleep(replica.lagBound.toMillis() + MARGIN_MS)
        decide(replica, "vp-13.set") shouldBe degraded
    }

    @Test
    fun `a stale replica is decided by the fallback and FALLBACK allows`(
        @TempDir dir: Path,
    ) = serve(dir, AuthorityFallback { _, _ -> FallbackResult.Found(AuthorityVerdict(true)) }) { replica ->
        feed(replica, signatory, "client.signatory", GrantScope.CLIENT_ENTITY, cli1)
        replica.onDisconnected()
        Thread.sleep(replica.lagBound.toMillis() + MARGIN_MS)
        decide(replica, "vp-11.set") shouldBe Triple(true, DecisionSource.FALLBACK, DecisionReason.GRANTED)
    }

    private companion object {
        const val DAY = 86_400L
        const val MARGIN_MS = 300L
    }
}
