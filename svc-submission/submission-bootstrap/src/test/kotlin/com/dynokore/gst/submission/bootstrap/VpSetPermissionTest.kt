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

    private data class Held(
        val role: String,
        val scope: GrantScope,
        val ref: String?,
        val until: Instant?,
    )

    private data class Rec(
        val tenant: TenantId,
        val state: RoleGrantState,
        val grantRef: String?,
    )

    private data class Ask(
        val who: String,
        val entity: String,
        val level: ScopeLevel,
        val tenant: TenantId,
    )

    private fun held(
        role: String,
        scope: GrantScope,
        ref: String?,
        until: Instant? = null,
    ) = Held(role, scope, ref, until)

    private fun signed(
        ref: String,
        until: Instant? = null,
    ) = held("client.signatory", GrantScope.CLIENT_ENTITY, ref, until)

    private fun rec(
        inTenant: TenantId = tenant,
        state: RoleGrantState = RoleGrantState.ACTIVE,
        grantRef: String? = null,
    ) = Rec(inTenant, state, grantRef)

    private fun ask(
        who: String = signatory,
        entity: String = cli1,
        level: ScopeLevel = ScopeLevel.CLIENT_ENTITY,
        inTenant: TenantId = tenant,
    ) = Ask(who, entity, level, inTenant)

    private fun feed(
        replica: AuthorityReplica,
        who: String,
        held: Held,
        record: Rec = rec(),
    ) {
        val grant = RoleGrant(held.role, held.scope, held.ref, Instant.now().minusSeconds(DAY), held.until)
        val ref = record.grantRef ?: "${held.role}/${held.scope}/${held.ref}"
        replica.accept(
            AuthorityChanged(
                record.tenant,
                who,
                version.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(ref, record.state, grant),
            ),
        )
        current(replica)
    }

    private fun decide(
        replica: AuthorityReplica,
        action: String,
        ask: Ask = ask(),
    ): Triple<Boolean, DecisionSource, DecisionReason> {
        val principal = Principal(ask.who, PrincipalKind.HUMAN)
        val request = AuthorizationRequest(ask.tenant, principal, ask.entity, action, level = ask.level)
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
        feed(replica, signatory, signed(cli1))
        for (action in listOf("vp-11.set", "vp-13.set")) {
            decide(replica, action) shouldBe granted
            decide(replica, action, ask(entity = cli2)) shouldBe refused
            decide(replica, action, ask(level = ScopeLevel.TENANT)) shouldBe refused
            decide(replica, action, ask(level = ScopeLevel.ENTITY)) shouldBe refused
            decide(replica, action, ask(inTenant = otherTenant)) shouldBe refused
        }
    }

    @Test
    fun `a grant of another tenant, an expired grant and a removed grant are each refused`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        feed(replica, signatory, signed(cli1), rec(inTenant = otherTenant))
        decide(replica, "vp-11.set") shouldBe refused

        val lapsed = "link:LNK-771C"
        feed(replica, lapsed, signed(cli1, Instant.now().minusSeconds(60)))
        decide(replica, "vp-13.set", ask(lapsed)) shouldBe refused

        val removed = "link:LNK-771D"
        feed(replica, removed, signed(cli1), rec(grantRef = "g1"))
        decide(replica, "vp-13.set", ask(removed)) shouldBe granted
        feed(replica, removed, signed(cli1), rec(state = RoleGrantState.REMOVED, grantRef = "g1"))
        decide(replica, "vp-13.set", ask(removed)) shouldBe refused
    }

    @Test
    fun `INV-AC-2224 the look-alike principals and keys are refused`(
        @TempDir dir: Path,
    ) = serve(dir) { replica ->
        feed(replica, signatory, signed(cli1))
        for (who in listOf("link:LNK-771B", "LNK-771A", "link:lnk-771a", "link:LNK-771A ")) {
            decide(replica, "vp-11.set", ask(who)) shouldBe refused
            decide(replica, "vp-13.set", ask(who)) shouldBe refused
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
            feed(replica, who, held(role, GrantScope.CLIENT_ENTITY, cli1))
            feed(replica, who, held(role, GrantScope.TENANT, tenant.toString()))
            decide(replica, "vp-11.set", ask(who)) shouldBe refused
            decide(replica, "vp-13.set", ask(who)) shouldBe refused
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
        feed(replica, signatory, signed(cli1))
        decide(replica, "vp-13.set") shouldBe granted
        replica.onDisconnected()
        Thread.sleep(replica.lagBound.toMillis() + MARGIN_MS)
        decide(replica, "vp-13.set") shouldBe degraded
    }

    @Test
    fun `a stale replica is decided by the fallback and FALLBACK allows`(
        @TempDir dir: Path,
    ) = serve(dir, AuthorityFallback { _, _ -> FallbackResult.Found(AuthorityVerdict(true)) }) { replica ->
        feed(replica, signatory, signed(cli1))
        replica.onDisconnected()
        Thread.sleep(replica.lagBound.toMillis() + MARGIN_MS)
        decide(replica, "vp-11.set") shouldBe Triple(true, DecisionSource.FALLBACK, DecisionReason.GRANTED)
    }

    private companion object {
        const val DAY = 86_400L
        const val MARGIN_MS = 300L
    }
}
