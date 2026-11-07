package com.dynokore.gst.evidence.retention

import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Instant
import com.dynokore.gst.evidence.domain.retention.RetentionTier.ACKNOWLEDGEMENTS_AND_TRACES as ACK
import com.dynokore.gst.evidence.domain.retention.RetentionTier.SOURCE_DOCUMENT_REFERENCES as SOURCE
import com.dynokore.gst.evidence.domain.retention.RetentionTier.STRUCTURED_SUBMISSIONS as STRUCTURED

/**
 * `setPolicy` of VP-43 decides by the permission `vp-43.set` through the one `AuthorityReplica` of the context
 * (INV-T-9236, INV-Q-8972 A, INV-Q-10107 C). The holder set of `vp-43.set` is the role `tenant.admin` (gst-catalogue
 * table); the service has no role resolver and compares no role name. Every case calls the mounted
 * `POST /internal/v1/policies/VP-43` and reads the stored value back through `PolicyReader`.
 */
class VpSetPermissionTest {
    private val mapper = ObjectMapper()
    private val years = mapOf(STRUCTURED to 6, ACK to 5, SOURCE to 5)
    private val e1 = "ENT-A"
    private val e2 = "ENT-B"
    private val who = "user-7"

    private fun held(
        s: RetentionService,
        tenant: TenantId,
        grant: RoleGrant,
        state: RoleGrantState = RoleGrantState.ACTIVE,
        grantRef: String = "${grant.role}/${grant.scope}/${grant.scopeRef}",
    ) = s.grants.grant(tenant, who, grant, grantRef, state)

    private fun RetentionService.admin(
        role: String,
        scope: GrantScope,
        ref: String?,
        until: Instant? = null,
    ) = grants.role(role, scope, ref, until)

    private fun RetentionService.stored(
        tenant: TenantId,
        entity: String?,
    ) = selected(tenant, entity).singleOrNull()?.let { it.key to it.valueJson?.let(mapper::readTree) }

    private fun RetentionService.expected() =
        "LONGER" to mapper.valueToTree<tools.jackson.databind.JsonNode>(record(years))

    private fun refused(
        s: RetentionService,
        tenant: TenantId,
        level: String,
        entity: String?,
    ) {
        val before = s.stored(tenant, entity)
        val answer = s.setBy(who, tenant, level, entity, years)
        answer.status shouldBe 403
        answer.code shouldBe "SRT-004"
        s.stored(tenant) shouldBe 0
        s.stored(tenant, entity) shouldBe before
    }

    @Test
    fun `INV-AC-2224 exactly one AuthorityReplica exists and the mounted setPolicy decides through it`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            s.bean(AuthorityReplica::class.java) // single: getBean fails on none or on two
            val all = s.beansOf(AuthorityReplica::class.java)
            all.size shouldBe 1
            val tenant = s.bareTenant()
            // the decision is the replica's: with no grant the call is refused, with the grant it is accepted
            s.setBy(who, tenant, "TENANT", null, years).status shouldBe 403
            held(s, tenant, s.admin("tenant.admin", GrantScope.TENANT, tenant.value.toString()))
            s.setBy(who, tenant, "TENANT", null, years).status shouldBe 200
        }

    @Test
    fun `holder of tenant admin at TENANT scope sets the value at tenant scope and it is stored`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val tenant = s.bareTenant()
            held(s, tenant, s.admin("tenant.admin", GrantScope.TENANT, tenant.value.toString()))
            s.stored(tenant, null) shouldBe ("YEARS_5" to null)
            s.setBy(who, tenant, "TENANT", null, years).accepted()
            s.stored(tenant, null) shouldBe s.expected()
        }

    @Test
    fun `a holder on entity E1 only sets entity E1 and nothing at tenant scope or on E2`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val tenant = s.bareTenant()
            held(s, tenant, s.admin("tenant.admin", GrantScope.ENTITY, e1))
            refused(s, tenant, "TENANT", null)
            refused(s, tenant, "ENTITY", e2)
            s.setBy(who, tenant, "ENTITY", e1, years).accepted()
            s.stored(tenant, e1) shouldBe s.expected()
        }

    @Test
    fun `INV-AC-2224 the look-alike role tenant admin2 at the same scope is refused with SRT-004`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            for (role in listOf("tenant.admin2", "Tenant.Admin", "tenant.admin ", "tenant_admin", "tenant.admins")) {
                val tenant = s.bareTenant()
                held(s, tenant, s.admin(role, GrantScope.TENANT, tenant.value.toString()))
                held(s, tenant, s.admin(role, GrantScope.ENTITY, e1))
                refused(s, tenant, "TENANT", null)
                refused(s, tenant, "ENTITY", e1)
            }
        }

    @Test
    fun `INV-AC-2224 a caller holding another role of the catalogue or none is refused with SRT-004`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            for (role in listOf("security.officer", "tax.approver")) {
                val tenant = s.bareTenant()
                held(s, tenant, s.admin(role, GrantScope.TENANT, tenant.value.toString()))
                held(s, tenant, s.admin(role, GrantScope.ENTITY, e1))
                refused(s, tenant, "TENANT", null)
                refused(s, tenant, "ENTITY", e1)
            }
            refused(s, s.bareTenant(), "TENANT", null)
        }

    @Test
    fun `a grant of another tenant is refused`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val tenant = s.bareTenant()
            val other = s.bareTenant()
            held(s, other, s.admin("tenant.admin", GrantScope.TENANT, other.value.toString()))
            held(s, other, s.admin("tenant.admin", GrantScope.ENTITY, e1))
            refused(s, tenant, "TENANT", null)
            refused(s, tenant, "ENTITY", e1)
            // the grant of the other tenant is a TENANT grant naming that tenant: not valid for this one either
            held(s, tenant, s.admin("tenant.admin", GrantScope.TENANT, other.value.toString()), grantRef = "foreign")
            refused(s, tenant, "TENANT", null)
        }

    @Test
    fun `an expired, a removed and a voided grant are each refused`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val expired = s.bareTenant()
            val lapsed =
                s.admin(
                    "tenant.admin",
                    GrantScope.TENANT,
                    expired.value.toString(),
                    Instant.now().minusSeconds(60),
                )
            held(s, expired, lapsed)
            refused(s, expired, "TENANT", null)

            val removed = s.bareTenant()
            val grant = s.admin("tenant.admin", GrantScope.TENANT, removed.value.toString())
            held(s, removed, grant)
            s.setBy(who, removed, "TENANT", null, years).accepted()
            held(s, removed, grant, RoleGrantState.REMOVED)
            val again = s.setBy(who, removed, "TENANT", null, mapOf(STRUCTURED to 7, ACK to 5, SOURCE to 5))
            again.status shouldBe 403
            again.code shouldBe "SRT-004"
            s.stored(removed, null) shouldBe s.expected() // the earlier value, not the refused one

            val voided = s.bareTenant()
            held(s, voided, s.admin("tenant.admin", GrantScope.TENANT, voided.value.toString()))
            s.grants.voidPrincipal(voided, who)
            refused(s, voided, "TENANT", null)
        }

    @Test
    fun `a replica with no snapshot is decided as degraded and refused`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val tenant = s.bareTenant() // no grant was ever fed: no snapshot
            refused(s, tenant, "TENANT", null)
        }

    @Test
    fun `a stale replica is DEGRADED and refused for a holder`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val tenant = s.bareTenant()
            held(s, tenant, s.admin("tenant.admin", GrantScope.TENANT, tenant.value.toString()))
            s.grants.stale()
            refused(s, tenant, "TENANT", null)
        }

    @Test
    fun `a stale replica is decided by the fallback and FALLBACK allows`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService
            .start(dir, listOf(WorkloadTokensOnly::class.java, RetentionBeans::class.java, Allowing::class.java))
            .use { s ->
                val tenant = s.bareTenant()
                held(s, tenant, s.admin("tenant.admin", GrantScope.TENANT, tenant.value.toString()))
                s.grants.stale()
                s.setBy(who, tenant, "TENANT", null, years).accepted()
                s.stored(tenant, null) shouldBe s.expected()
            }

    @Configuration(proxyBeanMethods = false)
    class Allowing {
        @Bean
        @Primary
        fun answers(): AuthorityFallback = AuthorityFallback { _, _ -> FallbackResult.Found(AuthorityVerdict(true)) }
    }
}
